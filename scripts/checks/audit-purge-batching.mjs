#!/usr/bin/env node
// 审计清理 SQL 必须分批：删除语句只按主键批删，禁止按时间整段删；取批必须带批次上限。
// 零依赖纯 ESM，本地与 CI 同一入口复跑。
// 门禁来源：设计稿 §12.4「单事务 ≤5000 行」与 §12.7 I-6（分批删除）、I-7（子记录按父记录联动删除）。
// 为什么需要：一条 `delete from … where create_time < ?` 在开发库上跑得快、看不出问题，
// 到生产大表上就是长事务 + 锁放大，而单测里 mock 掉 Mapper 根本拦不住这种改写——只有静态形状能拦。
// 分批循环本身（累计行数、批次上限、删 0 行即中止）是运行期行为，由必过单测
// AiAuditRetentionServiceImplTest 守住，本脚本只钉住 SQL 形状，不重复自证常量存在。
import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = fileURLToPath(new URL('../..', import.meta.url));
const MAPPER = 'code/back-end/ruoyi-modules/agent-hub-ai/src/main/java/org/dromara/ai/mapper/AiAuditRetentionMapper.java';

const failures = [];

if (!existsSync(`${REPO_ROOT}${MAPPER}`)) {
  console.log(`⚠ 跳过审计清理分批校验：${MAPPER} 未入仓，本门禁在其出现后自动生效`);
  process.exit(0);
}

const text = readFileSync(`${REPO_ROOT}${MAPPER}`, 'utf8');
// 注解与其 SQL 可能写在文本块（"""）里，按 @Delete / @Select 起始切段，段内取第一条语句。
const blocks = [...text.matchAll(/@(Delete|Select)\s*\(\s*(?:"""([\s\S]*?)"""|"((?:[^"\\]|\\.)*)")/g)]
  .map((m) => ({ kind: m[1], sql: (m[2] ?? m[3] ?? '').replace(/\\{/g, '{').replace(/\s+/g, ' ').trim() }));

if (!blocks.length) {
  console.error(`✗ 审计清理分批校验失败：${MAPPER} 里解析不到 @Delete/@Select，脚本与本文件的写法已失配`);
  process.exit(1);
}

let deletes = 0;
let selects = 0;
for (const { kind, sql } of blocks) {
  if (kind === 'Delete') {
    deletes += 1;
    // 删除只允许按主键批删（本表 id 或父表送来的 id 列表），且必须走 foreach 展开。
    if (!/where\s+[a-z_]*id\s+in\s+<foreach/i.test(sql)) {
      failures.push(`@Delete「${sql.slice(0, 60)}…」未按 id 批删，缺 where …id in <foreach>`);
    }
    if (/create_time\s*</i.test(sql)) {
      failures.push(`@Delete「${sql.slice(0, 60)}…」直接按 create_time 整段删除，违反 §12.4 单事务 ≤5000 行`);
    }
  } else {
    selects += 1;
    // 取一批主键必须带批次上限；计数语句不带 limit 属正常。
    if (/^\s*select\s+id\s+from/i.test(sql) && !/limit\s+#\{batch\}/i.test(sql)) {
      failures.push(`@Select「${sql.slice(0, 60)}…」取批主键没有 limit #{batch}，可能一次载入全表`);
    }
  }
}

// 计数口径与取批口径都得存在：只有 delete 没有 select id，说明又退回了整段删。
if (deletes === 0) failures.push(`${MAPPER} 没有 @Delete，清理入口可能已被移除`);
if (!blocks.some((b) => b.kind === 'Select' && /^\s*select\s+id\s+from/i.test(b.sql))) {
  failures.push(`${MAPPER} 没有「select id from …」取批语句，删除路径未分批`);
}

// 逐表对账，清单以 ServiceImpl 的 TablePurge label 为准。
// 上一段的全局断言只能证明「存在某条取批语句」，删掉其中一张表的语句不会变红；
// 而漏一张表恰恰是这里最典型的漂移（清理少跑一张表，行数与留痕都看不出异常）。
const SERVICE = 'code/back-end/ruoyi-modules/agent-hub-ai/src/main/java/org/dromara/ai/service/impl/AiAuditRetentionServiceImpl.java';
const labels = existsSync(`${REPO_ROOT}${SERVICE}`)
  ? [...readFileSync(`${REPO_ROOT}${SERVICE}`, 'utf8').matchAll(/new TablePurge\(\s*"(ai_[a-z0-9_]+)"/g)].map((m) => m[1])
  : [];
if (!labels.length) {
  failures.push(`${SERVICE} 里解析不到 TablePurge 的表清单，逐表对账无从执行（脚本与写法已失配）`);
}
const has = (kind, pattern) => blocks.some((b) => b.kind === kind && pattern.test(b.sql));
for (const table of [...new Set(labels)]) {
  if (!has('Select', new RegExp(`^\\s*select count\\(\\*\\) from ${table}\\b`, 'i'))) {
    failures.push(`${table} 在清理清单里却没有「select count(*) from ${table}」：留痕的行数口径失去来源`);
  }
  if (!has('Select', new RegExp(`^\\s*select id from ${table}\\b.*limit\\s+#\\{batch\\}`, 'i'))) {
    failures.push(`${table} 在清理清单里却没有「select id from ${table} … limit #{batch}」取批语句：该表要么整段删要么不删（§12.7 I-6）`);
  }
  if (!has('Delete', new RegExp(`delete from ${table} where id in <foreach`, 'i'))) {
    failures.push(`${table} 在清理清单里却没有「delete from ${table} where id in (…)」按 id 批删语句（§12.7 I-6）`);
  }
}

// I-7 的机械形式：子记录必须有一条「按父主键批删」的语句，否则父行先走、子行留下就是孤立引用。
// 只看形状不只看「有没有按 id 批删」——把 route_run_id 改成 id 仍能通过前一条断言，孤立引用却回来了。
const CASCADES = [
  { table: 'ai_route_expert_call', column: 'route_run_id' },
  { table: 'ai_trace', column: 'parent_trace_id' },
];
for (const { table, column } of CASCADES) {
  const pattern = new RegExp(`delete from ${table} where ${column} in <foreach`, 'i');
  if (!blocks.some((b) => b.kind === 'Delete' && pattern.test(b.sql))) {
    failures.push(`${MAPPER} 缺「delete from ${table} where ${column} in (…)」：${table} 的子记录没按父记录联动删除（§12.7 I-7）`);
  }
}

if (failures.length) {
  console.error(`\n✗ 审计清理分批校验失败 ${failures.length} 项：`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(
  `✓ 审计清理分批校验通过（${deletes} 条按 id 批删、${selects} 条计数/取批语句，`
  + `清理清单 ${[...new Set(labels)].length} 张表逐表对齐全三条语句）`
);
