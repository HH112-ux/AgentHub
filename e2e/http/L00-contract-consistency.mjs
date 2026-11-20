#!/usr/bin/env node
// A0 契约一致性校验：Java 唯一常量源 ↔ 前端 TS 镜像 ↔ 菜单 SQL 权限点，三方逐字对齐。
// 门禁来源：设计稿 §6 四类冻结制品；协作计划 Task 2（镜像与校验）与 Task 4（Java 常量、TS 镜像、菜单脚本同一 PR 同步）。
// 零依赖纯 ESM，本地与 CI 同一入口复跑（.github/workflows/ci.yml 检测到本文件存在即执行）。
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const JAVA_DIR = 'code/back-end/ruoyi-modules/agent-hub-ai/src/main/java/org/dromara/ai/contract';
const TS_FILE = 'code/front-end/src/types/ai-contract.ts';
const MENU_SQLS = ['postgres', 'mysql'].map((d) => `code/back-end/script/sql/${d}/agenthub-ai-menu.sql`);
// 契约类与菜单脚本的 owner 见计划「交付物地图」与 Task 6 冲突表：均为队长，B/C 追加由队长在同一 PR 合并。
const LEAD = '队长';

// 数量与取值形态是 §6 的冻结期望值，不是本轮观测值（同 scripts/checks/schema-dialect-parity.mjs 的 EXPECTED_TABLES 先例）。
// 断言「Java 侧条数 == expected」同时挡住两类事故：解析器漏读声明形态，以及 Java 与 TS 同时增删同一项造成的双侧假绿。
// AiToolImpl 现冻结 11：B 线计划追加 DEPT_QUERY/ANNOUNCEMENT_WRITE/DAILY_DIGEST_WRITE 时，须按 Task 4 在同一 PR 内更新此数与 TS 镜像。
const LEGS = [
  { label: 'SSE 事件类型', java: 'AiEventType.java', ts: 'AiEventType', expected: 13, value: /^[a-z][a-z0-9_]*$/ },
  { label: '降级原因码', java: 'AiDegradeReason.java', ts: 'AiDegradeReason', expected: 11, value: /^[A-Z][A-Z0-9_]*$/ },
  { label: '工具实现标识', java: 'AiToolImpl.java', ts: 'AiToolImplName', expected: 11, value: /^[A-Z][A-Z0-9_]*$/ },
  { label: '权限标识', java: 'AiPermissionKeys.java', ts: 'AiPermissionKey', expected: 132, value: /^ai(:[a-z0-9]+)+$/ },
];

// Java 侧三种声明形态依次尝试：带线串参的枚举（AiEventType/AiDegradeReason 现状）、static final String（AiPermissionKeys/AiToolImpl 现状）、
// 无参枚举（计划 Task 2 Step 3 给出的 AiDegradeReason 样例形态）。不锚定行首：计划样例把两个常量写在一行，锚行首会漏读一半。
// 选错形态或漏读由 LEGS 的期望条数兜底，不会假绿。
const JAVA_FORMS = [
  { name: '带线串参的枚举常量', re: /([A-Z][A-Z0-9_]{1,})\(\s*"([^"]+)"\s*\)/g, pair: (m) => [m[1], m[2]] },
  { name: 'static final String 常量', re: /static\s+final\s+String\s+([A-Z][A-Z0-9_]*)\s*=\s*"([^"]+)"/g, pair: (m) => [m[1], m[2]] },
  { name: '无参枚举常量', re: /([A-Z][A-Z0-9_]{2,})\s*[,;]/g, pair: (m) => [m[1], m[1]] },
];

// 注释一律先剥离：被注释掉的镜像成员、注释里提到的历史取值都不该参与比对（否则门禁会宣称一致而镜像实际缺项）。
const stripJs = (s) => s.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/\/\/[^\n]*/g, '');
const stripSql = (s) => s.replace(/--[^\n]*/g, ' ');

// 计划 Task 2 Step 3：权限常量名由取值按 `:` 转大写蛇形（AI_KNOWLEDGE_LIST），故名字可从值机械推导。
const derivedName = (value) => value.toUpperCase().replace(/[^A-Z0-9]/g, '_');

const parseJava = (src) => {
  const text = stripJs(src);
  for (const form of JAVA_FORMS) {
    const pairs = [...text.matchAll(form.re)].map(form.pair);
    if (pairs.length) return { form: form.name, pairs };
  }
  return { form: null, pairs: [] };
};

const parseTs = (src, typeName) => {
  // body 到「首个分号」或「下一条 type 声明」为止：镜像若缺尾分号也只在本块内解析，条数断言随即报出真实缺失数，
  // 不会越界把下一个类型的成员算进来、把错误指向别处。
  const body = stripJs(src).match(new RegExp(`^(?:export\\s+)?type\\s+${typeName}\\s*=([\\s\\S]*?)(?:;|\\n(?:export\\s+)?type\\s)`, 'm'))?.[1];
  if (body === undefined) return null;
  return [...body.matchAll(/(['"])([^'"]+)\1/g)].map((m) => m[2]);
};

const dupes = (values) => [...new Set(values.filter((v, i) => values.indexOf(v) !== i))];

// 单条契约腿的全部断言：条数、取值形态、名字可推导性、集合差异、声明顺序、两侧重复。
function checkLeg(leg, javaPairs, tsValues) {
  const out = [];
  const javaValues = javaPairs.map((p) => p[1]);
  const bad = (m) => out.push(`${leg.label}：${m}`);
  if (javaValues.length !== leg.expected) {
    bad(`Java 侧解析到 ${javaValues.length} 条，与 §6 冻结的 ${leg.expected} 条不符。` +
      '可能是契约类新增/改名或换了声明形态导致解析漏读——请先按 §6 变更流程确认，再同步更新本脚本 LEGS 与 TS 镜像，不要只改断言数字。');
  }
  if (tsValues === null) return [...out, `${leg.label}：${TS_FILE} 缺少 type ${leg.ts}`];
  if (tsValues.length !== leg.expected) {
    bad(`TS 镜像解析到 ${tsValues.length} 条，与 §6 冻结的 ${leg.expected} 条不符（注释掉的成员不计入镜像）。`);
  }
  for (const [name, value] of javaPairs) {
    if (!leg.value.test(value)) bad(`Java 常量 ${name} 的取值「${value}」不符合 §6 形态 ${leg.value}`);
    if (derivedName(value) !== name) bad(`Java 常量名 ${name} 与取值「${value}」的推导名 ${derivedName(value)} 不一致（计划 Task 2 Step 3）`);
  }
  for (const v of tsValues) if (!leg.value.test(v)) bad(`TS 镜像成员「${v}」不符合 §6 形态 ${leg.value}`);
  const missing = javaValues.filter((v) => !tsValues.includes(v));
  const extra = tsValues.filter((v) => !javaValues.includes(v));
  if (missing.length) bad(`TS 镜像缺少 ${missing.length} 项：${missing.join(', ')}`);
  if (extra.length) bad(`TS 镜像多出 ${extra.length} 项：${extra.join(', ')}`);
  if (dupes(javaValues).length) bad(`Java 侧取值重复：${dupes(javaValues).join(', ')}`);
  if (dupes(tsValues).length) bad(`TS 侧取值重复：${dupes(tsValues).join(', ')}`);
  if (!out.length && javaValues.join() !== tsValues.join()) {
    bad(`取值集合一致但声明顺序不一致——镜像按后端声明顺序重排，保持 diff 可读（镜像文件头声明的即为此规则）。`);
  }
  return out;
}

// 门禁自身的适应度函数：确认上面这些断言真的会红、真实排版不会误红。自检不过等于门禁失效，优先级高于业务比对。
function selfTest() {
  const leg = { label: '自检', java: 'X.java', ts: 'T', expected: 2, value: /^[a-z][a-z0-9_]*$/ };
  const javaEnum = (constants) =>
    `public enum X {\n    ${constants}\n    private final String wire;\n    X(String wire) { this.wire = wire; }\n    public String wire() { return wire; }\n}`;
  const javaStatic = (constants) => `public final class X {\n${constants}\n    private X() {}\n}`;
  const tsMembers = (...values) => `export type T =\n${values.map((v, i) => `  | '${v}'${i === values.length - 1 ? ';' : ''}`).join('\n')}\n`;
  const cases = [
    { it: '完全对齐的输入必须通过', java: javaEnum('A_B("a_b"), C_D("c_d");'), ts: tsMembers('a_b', 'c_d'), want: false },
    { it: '枚举常量一行多个与一行一个都得全量解析', java: javaEnum('A_B("a_b"),\n    C_D("c_d");'), ts: tsMembers('a_b', 'c_d'), want: false },
    { it: 'static final String 形态必须能解析', java: javaStatic('    public static final String A_B = "a_b";\n    public static final String C_D = "c_d";'), ts: tsMembers('a_b', 'c_d'), want: false },
    { it: '镜像用双引号且成员同行也必须能解析', java: javaEnum('A_B("a_b"), C_D("c_d");'), ts: 'export type T = "a_b" | "c_d";', want: false },
    { it: '注释掉的镜像成员必须报缺', java: javaEnum('A_B("a_b"), C_D("c_d");'), ts: `export type T =\n  | 'a_b'   // A_B\n  // | 'c_d'   // C_D 暂时下线\n  ;`, want: true },
    { it: '两侧同时删除同一项必须报条数不符', java: javaEnum('A_B("a_b");'), ts: tsMembers('a_b'), want: true },
    { it: '两侧同时出现非法取值形态必须拦住', java: javaEnum('A_B("a-b"), C_D("c_d");'), ts: tsMembers('a-b', 'c_d'), want: true },
    { it: '取值集合一致但顺序不一致必须报', java: javaEnum('A_B("a_b"), C_D("c_d");'), ts: tsMembers('c_d', 'a_b'), want: true },
    { it: 'Java 常量名与取值推导不符必须报', java: javaEnum('AB("a_b"), C_D("c_d");'), ts: tsMembers('a_b', 'c_d'), want: true },
    { it: '镜像行尾注释含分号不得截断语句', java: javaEnum('A_B("a_b"), C_D("c_d");'), ts: `export type T =\n  | 'a_b'   // A_B; 历史说明见 §6\n  | 'c_d';  // C_D`, want: false },
  ];
  const broken = [];
  for (const c of cases) {
    const issues = checkLeg(leg, parseJava(c.java).pairs, parseTs(c.ts, 'T'));
    if (!!issues.length !== c.want) broken.push(`「${c.it}」预期${c.want ? '红' : '绿'}，实际${issues.length ? '红' : '绿'}${issues.length ? `（${issues[0]}）` : ''}`);
  }
  return { broken, total: cases.length };
}

const failures = [];
const notes = [];

const self = selfTest();
if (self.broken.length) {
  console.error('✗ 契约一致性检查器自检失败——门禁本身失效，业务比对结果不可信：');
  for (const b of self.broken) console.error(`  - ${b}`);
  process.exit(1);
}

const read = (rel, owner) => {
  if (!existsSync(join(REPO_ROOT, rel))) {
    failures.push(`${rel} 不存在（归属：${owner}）`);
    return null;
  }
  return readFileSync(join(REPO_ROOT, rel), 'utf8');
};

const tsSrc = read(TS_FILE, '队员 C 前端契约镜像');
const javaSrcs = LEGS.map((leg) => ({ leg, src: read(`${JAVA_DIR}/${leg.java}`, LEAD) }));

// 覆盖清单双向核对：契约目录里出现未登记的 Java 类，或镜像里出现未登记的 TS 类型，都说明门禁正在静默欠覆盖。
// 目录整体缺失时跳过（上面已按文件给出「未入仓/改名」结论），避免以崩栈代替可读红灯。
if (tsSrc && javaSrcs.every((j) => j.src !== null) && existsSync(join(REPO_ROOT, JAVA_DIR))) {
  const listed = [...stripJs(tsSrc).matchAll(/^(?:export\s+)?type\s+(\w+)\s*=/gm)].map((m) => m[1]);
  const knownTs = LEGS.map((l) => l.ts);
  const unlistedTs = listed.filter((n) => !knownTs.includes(n));
  const missingTs = knownTs.filter((n) => !listed.includes(n));
  const onDiskJava = readdirSync(join(REPO_ROOT, JAVA_DIR)).filter((f) => f.endsWith('.java')).sort();
  const knownJava = LEGS.map((l) => l.java);
  const unlistedJava = onDiskJava.filter((f) => !knownJava.includes(f));
  const missingJava = knownJava.filter((f) => !onDiskJava.includes(f));
  if (unlistedJava.length) failures.push(`契约目录出现未登记的 Java 类：${unlistedJava.join(', ')}——请把它加入本脚本 LEGS 与 TS 镜像（§6 新增制品类别需队长裁定）`);
  if (unlistedTs.length) failures.push(`TS 镜像出现未登记类型：${unlistedTs.join(', ')}——同上，勿留第二条无人校验的镜像`);
  if (missingTs.length) failures.push(`TS 镜像缺少已登记类型：${missingTs.join(', ')}`);
  if (missingJava.length) failures.push(`登记的 Java 类不在磁盘上：${missingJava.join(', ')}`);
}

for (const { leg, src } of javaSrcs) {
  if (src === null || tsSrc === null) continue;
  const { form, pairs } = parseJava(src);
  if (!form) {
    failures.push(`${leg.label}：${JAVA_DIR}/${leg.java} 未解析到任何契约值——契约类改名或换了声明形态，请同步更新本脚本 JAVA_FORMS，不要放宽校验`);
    continue;
  }
  notes.push(`${leg.label} ${pairs.length}/${leg.expected}（${form}）`);
  failures.push(...checkLeg(leg, pairs, parseTs(tsSrc, leg.ts)));
}

// 第三条腿：菜单 SQL 的 perms 集合。计划 Task 2 Step 3 写明「取值以 agenthub-ai-menu.sql 的 sys_menu.perms 为准」，
// Task 4 要求三方同一 PR 同步；此处只读队长的脚本，不改写。
const permsLeg = LEGS.find((l) => l.java === 'AiPermissionKeys.java');
const permsJava = javaSrcs.find((j) => j.leg === permsLeg);
for (const rel of MENU_SQLS) {
  const src = read(rel, LEAD);
  if (src === null || permsJava?.src == null) continue;
  const sqlValues = [...new Set([...stripSql(src).matchAll(/(['"])ai:[^'"]+\1/g)].map((m) => m[0].slice(1, -1)))];
  const javaValues = parseJava(permsJava.src).pairs.map((p) => p[1]);
  const missing = javaValues.filter((v) => !sqlValues.includes(v));
  const extra = sqlValues.filter((v) => !javaValues.includes(v));
  notes.push(`${rel.split('/').at(-2)} perms ${sqlValues.length}`);
  if (missing.length) failures.push(`菜单 SQL（${rel}）缺少 ${missing.length} 个权限标识：${missing.join(', ')}`);
  if (extra.length) failures.push(`菜单 SQL（${rel}）多出 ${extra.length} 个权限标识：${extra.join(', ')}`);
}

if (failures.length) {
  console.error(`✗ 契约一致性校验失败 ${failures.length} 项：`);
  for (const f of failures) console.error(`  - ${f}`);
  console.error('\n按设计稿 §6 与计划 Task 4：Java 常量、TS 镜像、菜单脚本须在同一 PR 内同步，未经队长批准的契约改动一律打回。');
  process.exit(1);
}
console.log(`✓ 检查器自检通过（${self.total} 例）`);
console.log(`✓ 契约一致：${notes.join('，')}`);
