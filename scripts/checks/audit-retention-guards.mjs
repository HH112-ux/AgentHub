#!/usr/bin/env node
// 审计保留策略门禁（设计稿 §12.4 / §12.7 I-5 + I-6 + I-7）：保留窗口、清理执行方式与留痕不许被悄悄改掉。
// 零依赖纯 ESM，本地与 CI 同一入口：node scripts/checks/audit-retention-guards.mjs
// 为什么需要：`audit-days` 是运行期可改的配置，改小一天就等于把审计留档窗口缩短一天。
//   底座式实现只在 BO 上写 `@Min(0)`，于是 1~29 天也能保存，清理任务照删——
//   合规留档要求（非 0 时 ≥30 天）一旦落到「页面能填什么」上就没有任何保证，必须做成门禁。
//   同一条理由适用于清理本身：一条 `delete … where create_time < cutoff` 与一条按 5000 行分片的循环
//   在功能测试里看不出差别，只有出数据量的那天才会变成锁表与 WAL 洪峰；父子引用一致同理，
//   它只在「父记录早于截止日、子记录晚于截止日」这一种边界上暴露，人工回归碰不到。
// 仓库 public，只报违规位置与数值，不回显配置内容与 SQL 全文。
import { readFileSync, existsSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../..', import.meta.url));
const rel = (p) => relative(ROOT, p).replace(/\\/g, '/');
const BACK = join(ROOT, 'code/back-end');
const AI = join(BACK, 'ruoyi-modules/agent-hub-ai/src/main/java/org/dromara/ai');

// 保留期下限。出处：设计稿 §12.4「保留期下限 30 天」与 §12.7 C 段 I-5 验收口径。
const FLOOR_DAYS = 30;
// 保留期上限。出处：`AiAuditRetentionBo` 的 `@Max(3650)`（十年封顶，防止误填成极大值把清理关掉）。
const MAX_DAYS = 3650;
// 单条 DELETE 的行数上限。出处：设计稿 §12.4「单事务 ≤5000 行避免长锁」与 §12.7 C 段 I-6 验收口径。
const MAX_ROWS_PER_TX = 5000;
// 父子引用一致的表：键＝子表，值＝必须 join 的父表（`ai_trace` 的父链路在同表，值为自身）。
// 出处：§12.7 C 段 I-7；`ai_trace.parent_trace_id`、`ai_route_expert_call.route_run_id` 见 DDL 注释。
const CHILD_TABLES = { ai_route_expert_call: 'ai_route_run', ai_trace: 'ai_trace' };
// 选择性删除禁令。出处：§12.4「只允许按时间边界删除，禁止按用户、会话或关键词选择性删除」。
const SELECTIVE_DELETE_TOKENS = ['user_id', 'session_id', 'message_id', 'agent_id', 'tool_code', 'tool_name', 'like ', 'keyword'];

const violations = [];
const fail = (msg) => violations.push(msg);

function read(path) {
  if (!existsSync(path)) {
    fail(`缺文件：${rel(path)}（审计保留门禁的判定对象，缺失即视为策略被整体移除）`);
    return '';
  }
  return readFileSync(path, 'utf8');
}

// 截出某个方法的主体（按大括号配对），避免把类里其他位置的数字当作本方法的规则。
function methodBody(src, signature) {
  const at = src.indexOf(signature);
  if (at < 0) return null;
  const open = src.indexOf('{', at);
  if (open < 0) return null;
  let depth = 0;
  for (let i = open; i < src.length; i += 1) {
    if (src[i] === '{') depth += 1;
    else if (src[i] === '}') {
      depth -= 1;
      if (depth === 0) return src.slice(open, i + 1);
    }
  }
  return null;
}

// ---------- 1. 服务层保存路径必须在下限上硬拦，且拦在写入之前 ----------
const SERVICE = join(AI, 'service/impl/AiAuditRetentionServiceImpl.java');
const serviceSrc = read(SERVICE);
const floorConst = serviceSrc.match(/MIN_RETENTION_DAYS\s*=\s*(\d+)/);
if (!floorConst) {
  fail(`${rel(SERVICE)} 找不到保留期下限常量 MIN_RETENTION_DAYS（§12.4 要求非 0 时 ≥${FLOOR_DAYS} 天）`);
} else if (Number(floorConst[1]) < FLOOR_DAYS) {
  fail(`${rel(SERVICE)} 下限常量被放宽为 ${floorConst[1]} 天，低于 §12.4 规定的 ${FLOOR_DAYS} 天`);
}
const saveBody = methodBody(serviceSrc, 'public void saveRetention(');
if (saveBody === null) {
  fail(`${rel(SERVICE)} 找不到 saveRetention 方法体，无法确认保存路径上有下限校验`);
} else {
  const guardAt = saveBody.search(/<\s*MIN_RETENTION_DAYS/);
  const writeAt = saveBody.indexOf('updateAuditRetention(');
  if (guardAt < 0) {
    fail(`${rel(SERVICE)} 的 saveRetention 未按「取值 < MIN_RETENTION_DAYS 即拒绝」校验：1~${FLOOR_DAYS - 1} 天会被直接写入运行期配置`);
  } else if (writeAt >= 0 && guardAt > writeAt) {
    fail(`${rel(SERVICE)} 的 saveRetention 先写配置再查下限，越界值已经落进 Redis`);
  }
}

// ---------- 2. 默认值不得违反下限（0＝不限是唯一例外） ----------
const PROPS = join(AI, 'config/AiProperties.java');
const auditDays = read(PROPS).match(/private int auditDays\s*=\s*(\d+);/);
if (!auditDays) {
  fail(`${rel(PROPS)} 找不到 retention.auditDays 默认值`);
} else {
  const days = Number(auditDays[1]);
  if (days !== 0 && (days < FLOOR_DAYS || days > MAX_DAYS)) {
    fail(`${rel(PROPS)} 默认保留天数 ${days} 违反 §12.4：只能取 0（不限）或 ${FLOOR_DAYS}~${MAX_DAYS}`);
  }
}

// ---------- 3. BO 的注解边界不得比下限更宽（@Min 只能放行 0） ----------
const BO = join(AI, 'domain/bo/AiAuditRetentionBo.java');
const boSrc = read(BO);
const minAt = boSrc.match(/@Min\(value\s*=\s*(-?\d+)/);
const maxAt = boSrc.match(/@Max\(value\s*=\s*(\d+)/);
if (!minAt || !maxAt) {
  fail(`${rel(BO)} 缺 @Min / @Max 边界注解，保留天数失去入参兜底`);
} else {
  if (Number(minAt[1]) < 0) fail(`${rel(BO)} @Min=${minAt[1]} 允许负数保留期`);
  if (Number(maxAt[1]) > MAX_DAYS) fail(`${rel(BO)} @Max=${maxAt[1]} 超出 ${MAX_DAYS} 天封顶`);
  if (Number(maxAt[1]) < FLOOR_DAYS) fail(`${rel(BO)} @Max=${maxAt[1]} 低于 ${FLOOR_DAYS} 天下限，任何合法取值都会被自己拒掉`);
}

// ---------- 4. 前端输入必须与后端同一档下限（否则填得进去、存不下去） ----------
const VUE = join(ROOT, 'code/front-end/src/views/ai/audit/route-run/index.vue');
const vueSrc = read(VUE);
const frontFloor = vueSrc.match(/RETENTION_MIN_DAYS\s*=\s*(\d+)/);
if (!frontFloor) {
  fail(`${rel(VUE)} 没有 RETENTION_MIN_DAYS 下限常量：页面可填 1~${FLOOR_DAYS - 1} 天，保存时才被后端拒绝`);
} else if (Number(frontFloor[1]) !== FLOOR_DAYS) {
  fail(`${rel(VUE)} 前端下限 ${frontFloor[1]} 天与后端 ${FLOOR_DAYS} 天不一致（§12.4）`);
}

// ---------- 5. 清理 SQL 必须按主键分片，且取片受行数上限（§12.4 / I-6） ----------
const MAPPER = join(AI, 'mapper/AiAuditRetentionMapper.java');
const mapperSrc = read(MAPPER);

// 注解里的 SQL 有两种写法（文本块与字符串字面量），都取出来按同一条规则判。
function annotatedSql(src, kind, path) {
  const out = [];
  const re = new RegExp(`@${kind}\\(`, 'g');
  let match;
  while ((match = re.exec(src)) !== null) {
    const tail = src.slice(match.index + match[0].length);
    const block = tail.match(/^\s*"""([\s\S]*?)"""/);
    const plain = tail.match(/^\s*"([^"]*)"/);
    const raw = block ? block[1] : plain ? plain[1] : null;
    if (raw === null) {
      fail(`${rel(path)}:${lineOf(src, match.index)} @${kind} 的 SQL 无法解析（既不是文本块也不是字符串字面量），门禁判不了即不放行`);
      continue;
    }
    // 只剥 MyBatis 动态标签，不用泛化的 `<...>`：SQL 里的 `<` 是时间边界比较，一起剥会把谓词剥没。
    out.push({ sql: raw.replace(/<\/?(?:script|foreach|if|where|set|trim|choose|when|otherwise|selectKey)\b[^>]*>/g, ' ').replace(/\s+/g, ' ').trim(), line: lineOf(src, match.index) });
  }
  return out;
}

function lineOf(src, index) {
  return src.slice(0, index).split('\n').length;
}

const tableOf = (sql) => (sql.match(/\bfrom\s+([a-z0-9_]+)/i) || [])[1];

for (const { sql, line } of annotatedSql(mapperSrc, 'Delete', MAPPER)) {
  if (!/delete\s+from\s+[a-z0-9_]+\s+where\s+id\s+in\b/i.test(sql)) {
    fail(`${rel(MAPPER)}:${line} 的 DELETE 不是「按主键分片」写法：${sql.slice(0, 60)}…（§12.4 要求单事务 ≤${MAX_ROWS_PER_TX} 行，一条按时间全量删的语句没有上界）`);
  }
}

const slices = [];
for (const { sql, line } of annotatedSql(mapperSrc, 'Select', MAPPER)) {
  const table = tableOf(sql);
  if (!table || !table.startsWith('ai_')) continue;
  if (!/#\{cutoff\}/.test(sql)) {
    fail(`${rel(MAPPER)}:${line} 的清理取数没有绑定截止时间参数，脱离「只按时间边界删除」的口径（§12.4）`);
    continue;
  }
  for (const token of SELECTIVE_DELETE_TOKENS) {
    if (sql.toLowerCase().includes(token)) {
      fail(`${rel(MAPPER)}:${line} 的清理谓词里出现 ${token.trim()}，§12.4 禁止按用户 / 会话 / 关键词选择性删除`);
    }
  }
  if (/count\s*\(/i.test(sql)) {
    slices.push({ kind: 'count', table, sql, line });
  } else {
    slices.push({ kind: 'slice', table, sql, line });
    if (!/limit\s+#\{limit\}/i.test(sql)) {
      fail(`${rel(MAPPER)}:${line} 的取片语句没有 limit #{limit}，一片的行数不受 ${MAX_ROWS_PER_TX} 行封顶约束（§12.4 / I-6）`);
    }
  }
}

// ---------- 6. 父子引用一致：子表谓词必须 join 父表（§12.7 I-7） ----------
for (const [child, parent] of Object.entries(CHILD_TABLES)) {
  for (const { kind, table, sql, line } of slices) {
    if (table !== child) continue;
    const joinsParent = parent === child
      ? /join\s+ai_trace\s+\w+\s+on\s+\w+\.id\s*=\s*\w+\.parent_trace_id/i.test(sql)
      : new RegExp(`join\\s+${parent}\\s+\\w+\\s+on\\s+\\w+\\.id\\s*=\\s*\\w+\\.\\w+`, 'i').test(sql);
    if (!joinsParent) {
      fail(`${rel(MAPPER)}:${line} 的 ${child} ${kind === 'count' ? '计数' : '取片'}没有 join 父表 ${parent}：跨截止日的子记录会留下孤立引用（§12.7 I-7）`);
    }
  }
}

// join 键在两个方言的 DDL 里都得真实存在：接口 e2e 要等云端真实栈（§9 H1），
// 列名拼错在这之前只能由门禁拦住，否则第一次自动清理就是一次「删不动也没人知道」。
const JOIN_KEYS = [
  { table: 'ai_route_expert_call', column: 'route_run_id' },
  { table: 'ai_trace', column: 'parent_trace_id' }
];
for (const dialect of ['postgres', 'mysql']) {
  const ddlPath = join(BACK, `script/sql/${dialect}/agenthub-ai-ddl.sql`);
  const ddl = read(ddlPath);
  for (const { table, column } of JOIN_KEYS) {
    // 建表语句在本仓库的 DDL 里是单行写法，按行取整段；判定要落在「列名 + 类型」的列定义上，
    // 只匹配列名会被同句里的索引定义（key xx (route_run_id, attempt_no)）蒙过去。
    const body = (ddl.match(new RegExp(`create table if not exists ${table}\\b[^\\n]*`, 'i')) || [])[0];
    if (!body) {
      fail(`${rel(ddlPath)} 找不到表 ${table} 的建表语句，清理的父子联动谓词无列可依（§12.7 I-7）`);
    } else if (!new RegExp(`\\b${column}\\s+(bigint|int|integer|smallint|numeric|decimal|char|varchar|text|timestamp|datetime)\\b`, 'i').test(body)) {
      fail(`${rel(ddlPath)} 的 ${table} 没有 ${column} 列定义，Mapper 里的父子 join 键已与 DDL 漂移`);
    }
  }
}

// ---------- 7. 服务层的批大小与每轮上限 ----------
const rowsConst = serviceSrc.match(/PURGE_BATCH_ROWS\s*=\s*(\d+)/);
if (!rowsConst) {
  fail(`${rel(SERVICE)} 找不到单批行数上限 PURGE_BATCH_ROWS（§12.4 单事务 ≤${MAX_ROWS_PER_TX} 行）`);
} else {
  const rows = Number(rowsConst[1]);
  if (rows <= 0 || rows > MAX_ROWS_PER_TX) {
    fail(`${rel(SERVICE)} 的单批行数上限是 ${rows}，超出 §12.4 规定的 ≤${MAX_ROWS_PER_TX} 行`);
  }
}
const loopBody = methodBody(serviceSrc, 'private TablePurge purgeTable(');
if (loopBody === null) {
  fail(`${rel(SERVICE)} 找不到分批循环 purgeTable，清理退回了一条语句删全量的写法（§12.4 / I-6）`);
} else {
  if (!/PURGE_MAX_BATCHES/.test(loopBody)) {
    fail(`${rel(SERVICE)} 的分批循环没有 PURGE_MAX_BATCHES 上限，单轮可以无限删下去`);
  }
  if (!/while\s*\(/.test(loopBody)) {
    fail(`${rel(SERVICE)} 的 purgeTable 没有循环取片，单批上限形同虚设（§12.4 / I-6）`);
  }
}

// ---------- 8. 清理留痕：手动走切面、自动投事件（§12.4 清理执行记录） ----------
const CONTROLLER = join(AI, 'controller/AiAuditRetentionController.java');
const controllerSrc = read(CONTROLLER);
const purgeAt = controllerSrc.indexOf('public R<AiAuditRetentionVo> purge()');
if (purgeAt < 0 || !/@Log\(title\s*=\s*"审计数据清理"/.test(controllerSrc.slice(0, purgeAt))) {
  fail(`${rel(CONTROLLER)} 的手动清理没有 @Log 注解，清理动作不留操作日志（§12.4 清理执行记录）`);
}
const TASK = join(AI, 'core/audit/AiAuditRetentionTask.java');
const taskSrc = read(TASK);
const runBody = methodBody(taskSrc, 'public void runNow()');
// 完成与异常两档都要留痕：只留一条调用路径的记录等于「清坏了没人知道」。
const recordCalls = runBody === null ? 0 : (runBody.match(/recordPurge\(/g) || []).length;
if (recordCalls < 2) {
  fail(`${rel(TASK)} 的 runNow 只投递 ${recordCalls} 次清理记录（应覆盖「清完」与「抛异常」两档）：定时任务清掉的数据在「操作日志」页查不到（§12.4 / I-6）`);
}
if (!/publishEvent\(/.test(taskSrc) || !/setJsonResult\(/.test(taskSrc)) {
  fail(`${rel(TASK)} 缺少 OperLogEvent 投递（publishEvent / setJsonResult），自动清理记录落不了库`);
}

if (violations.length) {
  console.error('\n✗ 审计保留策略门禁失败：');
  for (const v of violations) console.error(`  - ${v}`);
  console.error('\n修法：非 0 的保留天数一律 ≥30 天；0＝不限只在「自动清理跳过、手动清理被拒」的口径下成立。');
  console.error(`      清理必须按主键分片（单条 DELETE ≤${MAX_ROWS_PER_TX} 行）、子表 join 父表截止时间，且手动与自动两档都留清理记录。`);
  console.error('      确需调整任一条属 §12.4 契约改动，先改设计稿再改本脚本，并在豁免处写明出处。');
  process.exit(1);
}

console.log(`✓ 审计保留策略门禁通过（下限 ${FLOOR_DAYS} 天拦在保存前；清理按 ≤${MAX_ROWS_PER_TX} 行分片、子表 join 父表；手动与自动均留清理记录）`);

