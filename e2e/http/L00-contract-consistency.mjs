#!/usr/bin/env node
// L00 契约一致性（设计稿 §6 契约冻结的机械执行）：实现 ↔ org.dromara.ai.contract 常量 ↔ 双方言脚本 三方对账。
// 零依赖纯 ESM，本地与 CI 同一入口：node e2e/http/L00-contract-consistency.mjs
// 只报违规位置（仓库 public，CI 日志公网可见）。
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../..', import.meta.url));
const BACK = join(ROOT, 'code/back-end');
const AI = join(BACK, 'ruoyi-modules/agent-hub-ai/src/main/java/org/dromara/ai');
const CONTRACT = join(AI, 'contract');
const FRONT_API = join(ROOT, 'code/front-end/src/api/ai');
const DIALECTS = ['mysql', 'postgres'];

const violations = [];
const fail = (msg) => violations.push(msg);
const rel = (p) => relative(ROOT, p).replace(/\\/g, '/');

function sources(dir, exts, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) sources(p, exts, out);
    else if (exts.some((e) => name.endsWith(e))) out.push(p);
  }
  return out;
}
const javaFiles = sources(AI, ['.java']);

// 按引号与括号深度切分 VALUES 元组；SQL 里 '' 是单引号转义，幂等尾巴「on duplicate / on conflict」不参与取值。
function readTuple(text, from) {
  let depth = 0;
  let inQuote = false;
  for (let i = from; i < text.length; i += 1) {
    const ch = text[i];
    if (ch === "'") {
      if (inQuote && text[i + 1] === "'") { i += 1; continue; }
      inQuote = !inQuote;
      continue;
    }
    if (inQuote) continue;
    if (ch === '(') depth += 1;
    else if (ch === ')') {
      depth -= 1;
      if (depth === 0) return [text.slice(from + 1, i), i];
    }
  }
  return [null, from];
}
function splitValues(body) {
  const parts = [];
  let cur = '';
  let inQuote = false;
  let depth = 0;
  for (let i = 0; i < body.length; i += 1) {
    const ch = body[i];
    if (ch === "'") {
      if (inQuote && body[i + 1] === "'") { cur += "'"; i += 1; continue; }
      inQuote = !inQuote;
      cur += ch;
      continue;
    }
    if (!inQuote && ch === '(') depth += 1;
    if (!inQuote && ch === ')') depth -= 1;
    if (ch === ',' && !inQuote && depth === 0) { parts.push(cur.trim()); cur = ''; continue; }
    cur += ch;
  }
  parts.push(cur.trim());
  return parts;
}
function parseInserts(sqlText, table, column) {
  const hits = [];
  const head = new RegExp(`^insert into ${table} \\([^)]*\\) values \\(`, 'i');
  sqlText.split('\n').forEach((line, i) => {
    const trimmed = line.trim();
    if (!head.test(trimmed)) return;
    const cols = trimmed.slice(trimmed.indexOf('(') + 1, trimmed.indexOf(') values')).split(',').map((s) => s.trim());
    const idx = cols.indexOf(column);
    if (idx < 0) return;
    const open = trimmed.indexOf(') values (') + ') values ('.length;
    const [tuple] = readTuple(trimmed, open - 1);
    if (tuple === null) return;
    const values = splitValues(tuple);
    if (idx >= values.length) return;
    hits.push({ value: values[idx].replace(/^'|'$/g, ''), line: i + 1 });
  });
  return hits;
}

// ---------- A. 权限标识：注解 ⊆ 菜单脚本 = AiPermissionKeys ----------
const menuByDialect = {};
for (const dialect of DIALECTS) {
  const path = join(BACK, `script/sql/${dialect}/agenthub-ai-menu.sql`);
  if (!existsSync(path)) {
    fail(`缺双方言菜单脚本：${rel(path)}`);
    menuByDialect[dialect] = [];
    continue;
  }
  menuByDialect[dialect] = parseInserts(readFileSync(path, 'utf8'), 'sys_menu', 'perms');
}
const permsOf = (dialect) => new Set(menuByDialect[dialect].map((r) => r.value).filter((v) => /^ai:/.test(v)));
const menuMysql = permsOf('mysql');
const menuPg = permsOf('postgres');
for (const v of menuMysql) if (!menuPg.has(v)) fail(`权限 ${v} 只在 MySQL 菜单脚本登记，PostgreSQL 侧缺失（§5 双方言同步）`);
for (const v of menuPg) if (!menuMysql.has(v)) fail(`权限 ${v} 只在 PostgreSQL 菜单脚本登记，MySQL 侧缺失（§5 双方言同步）`);

// 命名形态：ai:{模块}[:{子资源}]:{动作}，每段小写字母数字，段内允许连字符，总段数 2~4。
const SEG = /^[a-z][a-z0-9]*(-[a-z0-9]+)*$/;
for (const v of menuMysql) {
  const segs = v.split(':');
  if (segs.length < 2 || segs.length > 4 || segs[0] !== 'ai' || !segs.every((s) => SEG.test(s))) {
    fail(`权限标识「${v}」不符合 ai:{模块}[:{子资源}]:{动作} 形态（§3.2 权限表）`);
  }
}

const annotated = [];
for (const f of javaFiles) {
  const src = readFileSync(f, 'utf8');
  src.split('\n').forEach((line, i) => {
    if (!/@SaCheckPermission\b/.test(line)) return;
    for (const m of line.matchAll(/"(ai:[^"]+)"/g)) annotated.push({ value: m[1], where: `${rel(f)}:${i + 1}` });
  });
}
for (const a of annotated) if (!menuMysql.has(a.value) && !menuPg.has(a.value)) {
  fail(`${a.where} 接口声明权限 ${a.value}，但双方言菜单脚本均未登记，任何角色都无法被授予`);
}

const keyPath = join(CONTRACT, 'AiPermissionKeys.java');
const keyValues = new Set([...readFileSync(keyPath, 'utf8').matchAll(/public static final String \w+ = "(ai:[^"]+)"/g)].map((m) => m[1]));
for (const v of menuMysql) if (!keyValues.has(v)) fail(`菜单权限 ${v} 未登记进 contract/AiPermissionKeys.java`);
for (const v of keyValues) if (!menuMysql.has(v)) fail(`${rel(keyPath)} 登记的 ${v} 在菜单脚本中不存在（常量类须与脚本逐字对齐）`);

// 前端按钮权限（v-hasPermi / hasPermi）必须能在菜单脚本里取到，否则按钮对所有人都不可见。
const frontSources = sources(join(ROOT, 'code/front-end/src'), ['.vue', '.ts']);
if (frontSources.length) {
  for (const f of frontSources) {
    const src = readFileSync(f, 'utf8');
    src.split('\n').forEach((line, i) => {
      if (!/hasPermi/i.test(line)) return;
      for (const m of line.matchAll(/"(ai:[^"]+)"|'(ai:[^']+)'/g)) {
        const v = m[1] ?? m[2];
        if (!menuMysql.has(v)) fail(`${rel(f)}:${i + 1} 前端按 ${v} 控制按钮，但菜单脚本未登记该权限`);
      }
    });
  }
}

// ---------- B. SSE 事件：枚举 = 后端发射点 =（前端在架时）前端消费面 ----------
const emitted = new Map();
for (const f of javaFiles) {
  const src = readFileSync(f, 'utf8');
  src.split('\n').forEach((line, i) => {
    const m = /\bEVENT_[A-Z0-9_]+\s*=\s*"([a-z][a-z_]*)"/.exec(line);
    if (m) emitted.set(m[1], `${rel(f)}:${i + 1}`);
  });
}
// 阶段值走 phase 事件的 payload，不入事件类型枚举；此处只对齐信封 type。
const typePath = join(CONTRACT, 'AiEventType.java');
const typeWire = new Set([...readFileSync(typePath, 'utf8').matchAll(/^\s*[A-Z][A-Z0-9_]*\("([a-z_]+)"\)/gm)].map((m) => m[1]));
for (const [w, where] of emitted) if (!typeWire.has(w)) fail(`${where} 发射 SSE 事件 ${w}，但 contract/AiEventType.java 未冻结该类型`);
for (const w of typeWire) if (!emitted.has(w)) fail(`${rel(typePath)} 冻结的事件类型 ${w} 在实现里没有发射点（死契约）`);
if (existsSync(FRONT_API)) {
  const typesFile = join(FRONT_API, 'chat/types.ts');
  const src = readFileSync(typesFile, 'utf8');
  const block = /interface ChatStreamEvent\s*\{[\s\S]*?\btype:\s*([\s\S]*?);/.exec(src);
  if (!block) {
    fail(`${rel(typesFile)} 里找不到 ChatStreamEvent.type 联合，前端消费面无法对账`);
  } else {
    const consumed = new Set([...block[1].matchAll(/'([a-z_]+)'/g)].map((m) => m[1]));
    for (const c of consumed) if (!typeWire.has(c)) fail(`${rel(typesFile)} 声明消费 ${c}，但后端从不发射（前端分支不可达）`);
    for (const w of typeWire) if (!consumed.has(w)) fail(`后端发射 ${w}，但 ${rel(typesFile)} 的 ChatStreamEvent.type 联合未列出`);
  }
}

// ---------- C. 工具实现标识：枚举 = 工厂常量 = 双方言种子 ----------
const factoryPath = join(AI, 'core/agent/AiToolFactory.java');
const factoryImpls = new Map();
[...readFileSync(factoryPath, 'utf8').matchAll(/String\s+(IMPL_[A-Z0-9_]+)\s*=\s*"([a-z0-9_]+)"/g)].forEach((m) => factoryImpls.set(m[2], m[1]));
const implPath = join(CONTRACT, 'AiToolImpl.java');
const implValues = new Set([...readFileSync(implPath, 'utf8').matchAll(/public static final String \w+ = "([a-z0-9_]+)"/g)].map((m) => m[1]));
for (const v of factoryImpls.keys()) {
  if (!implValues.has(v)) fail(`AiToolFactory 的装配键 ${v} 未登记进 contract/AiToolImpl.java`);
}
for (const v of implValues) {
  if (!factoryImpls.has(v)) fail(`${rel(implPath)} 登记的实现标识 ${v} 在 AiToolFactory 里没有对应 IMPL_* 常量`);
}
for (const dialect of DIALECTS) {
  const path = join(BACK, `script/sql/${dialect}/agenthub-ai-seed.sql`);
  const rows = parseInserts(readFileSync(path, 'utf8'), 'ai_tool', 'impl_name');
  if (!rows.length) fail(`${rel(path)} 里没有 ai_tool 预置行，工具契约无从对账`);
  for (const r of rows) {
    if (!factoryImpls.has(r.value)) fail(`${rel(path)}:${r.line} 预置工具实现 ${r.value} 在 AiToolFactory 中无装配分支（装配期必然降级）`);
    if (!implValues.has(r.value)) fail(`${rel(path)}:${r.line} 预置工具实现 ${r.value} 未登记进 contract/AiToolImpl.java`);
  }
}

// ---------- D. 降级原因：代码字面量必须落在受控词表内，且列宽与截断上限一致 ----------
const reasonPath = join(CONTRACT, 'AiDegradeReason.java');
const prefixes = [...readFileSync(reasonPath, 'utf8').matchAll(/^\s*[A-Z][A-Z0-9_]*\("([^"]+)"\)/gm)].map((m) => m[1]);
for (const f of javaFiles) {
  const src = readFileSync(f, 'utf8');
  src.split('\n').forEach((line, i) => {
    const m = /degradeReason\s*=\s*"([^"]+)"/.exec(line);
    if (!m) return;
    const literal = m[1];
    if (!prefixes.some((p) => literal === p || literal.startsWith(p))) {
      fail(`${rel(f)}:${i + 1} 降级原因「${literal}」不在 contract/AiDegradeReason.java 词表内，运维无法按前缀归类`);
    }
  });
}
const ddlPath = join(BACK, 'script/sql/mysql/agenthub-ai-ddl.sql');
const ddlWidth = /degrade_reason varchar\((\d+)\)/.exec(readFileSync(ddlPath, 'utf8'))?.[1];
const recorder = readFileSync(join(AI, 'core/agent/AiRouteRecorder.java'), 'utf8');
const codeMax = /DEGRADE_REASON_MAX\s*=\s*(\d+)/.exec(recorder)?.[1];
if (!ddlWidth || !codeMax) {
  fail('未取到 degrade_reason 列宽或 AiRouteRecorder.DEGRADE_REASON_MAX，截断保护无法对账');
} else if (ddlWidth !== codeMax) {
  fail(`degrade_reason 列宽 varchar(${ddlWidth}) 与 AiRouteRecorder.DEGRADE_REASON_MAX=${codeMax} 不一致，落库会被静默截断`);
}
for (const dialect of DIALECTS) {
  const pg = readFileSync(join(BACK, `script/sql/${dialect}/agenthub-ai-ddl.sql`), 'utf8');
  const w = /degrade_reason varchar\((\d+)\)/.exec(pg)?.[1];
  if (w !== ddlWidth) fail(`${dialect} 方言的 degrade_reason 列宽 ${w ?? '缺失'} 与 MySQL 的 varchar(${ddlWidth}) 不一致`);
}

// ---------- E. 状态字典：种子取值 = 实体 STATUS_* 常量（设计稿 §12.7） ----------
// 为什么需要：字典取值是管理台渲染状态的唯一来源，但种子当初按设计稿词表写成 UPPER 串，
// 代码落库却是 lower 串或 char(1) 码——页面只会显示原始码或空白，且只有点开列表才暴露。
// 取值以实体常量为单一来源：服务层若再写字面量，改名时只改一边也无人报警。
const STATUS_DICTS = [
  ['ai_session_status', 'domain/AiSession.java'],
  ['ai_message_status', 'domain/AiMessage.java'],
  ['ai_task_run_status', 'domain/AiAgentTaskRun.java'],
  ['ai_route_run_status', 'domain/AiRouteRun.java'],
  ['ai_expert_call_status', 'domain/AiRouteExpertCall.java'],
];
// sys_dict_* 是多行 VALUES：扫到 insert 头之后进入词表块，遇到分号结束，块内逐行带真实行号取元组。
function dictRowsOf(text, table) {
  const rows = [];
  let inBlock = false;
  text.split('\n').forEach((line, i) => {
    if (!inBlock) {
      inBlock = new RegExp(`^\\s*insert (?:ignore )?into ${table}\\b`, 'i').test(line);
      return;
    }
    if (/;\s*$/.test(line)) inBlock = false;
    const m = table === 'sys_dict_type'
      ? /^\s*\(\d+, '(?:[^']|'')*', '([a-z_]+)'/.exec(line)
      : /^\s*\(\d+, \d+, '(?:[^']|'')*', '((?:[^']|'')*)', '([a-z_]+)'/.exec(line);
    if (!m) return;
    rows.push(table === 'sys_dict_type'
      ? { type: m[1], line: i + 1 }
      : { value: m[1].replace(/''/g, "'"), type: m[2], line: i + 1 });
  });
  return rows;
}
const dictTypes = new Map();
const dictData = new Map();
for (const dialect of DIALECTS) {
  const path = rel(join(BACK, `script/sql/${dialect}/agenthub-ai-seed.sql`));
  const text = readFileSync(join(BACK, `script/sql/${dialect}/agenthub-ai-seed.sql`), 'utf8');
  for (const r of dictRowsOf(text, 'sys_dict_type')) dictTypes.set(`${dialect}#${r.type}`, { ...r, dialect });
  for (const r of dictRowsOf(text, 'sys_dict_data')) {
    const key = `${dialect}#${r.type}#${r.value}`;
    if (dictData.has(key)) fail(`${path}:${r.line} 字典 ${r.type} 的取值「${r.value}」重复登记`);
    dictData.set(key, r);
    const t = `${dialect}#${r.type}`;
    if (!dictTypes.has(t)) fail(`${path}:${r.line} 字典项属于词表 ${r.type}，但 sys_dict_type 里没有登记它`);
  }
  for (const r of dictTypes.values()) {
    if (r.dialect !== dialect) continue;
    if (![...dictData.keys()].some((k) => k.startsWith(`${dialect}#${r.type}#`))) {
      fail(`${path}:${r.line} 字典类型 ${r.type} 没有任何字典项（页面只会拿到裸码）`);
    }
  }
}
for (const [type, entity] of STATUS_DICTS) {
  const want = new Set(
    [...readFileSync(join(AI, entity), 'utf8').matchAll(/public static final String STATUS_\w+ = "([^"]+)"/g)].map((m) => m[1])
  );
  if (!want.size) fail(`${entity} 里没有 STATUS_* 常量，状态字典 ${type} 失去对账基准`);
  for (const dialect of DIALECTS) {
    const path = rel(join(BACK, `script/sql/${dialect}/agenthub-ai-seed.sql`));
    const got = new Map([...dictData].filter(([k]) => k.startsWith(`${dialect}#${type}#`)).map(([, v]) => [v.value, v]));
    if (!got.size) fail(`${path} 状态字典 ${type} 在 ${dialect} 方言里没有任何字典项，而 ${entity} 会写入 ${[...want].join('/')}`);
    for (const value of want) {
      if (!got.has(value)) fail(`${path} 状态字典 ${type} 缺少 ${entity} 写入的取值「${value}」（已登记：${[...got.keys()].join('/') || '空'}）`);
    }
    for (const [value, r] of got) {
      if (!want.has(value)) fail(`${path}:${r.line} 状态字典 ${type} 的取值「${value}」没有任何代码写入（${entity} 的 STATUS_* 里没有它）`);
    }
  }
}

if (violations.length) {
  console.error(`\n✗ 契约一致性失败 ${violations.length} 项：`);
  for (const v of violations) console.error(`  - ${v}`);
  console.error('\n按 §6 契约变更流程处理：要么改实现与常量对齐，要么由队长批准改契约并同步三条任务线。');
  process.exit(1);
}
console.log(
  `✓ 契约一致性通过（权限 注解 ${annotated.length} → 菜单 ${menuMysql.size} = 常量 ${keyValues.size}；` +
  `SSE ${typeWire.size} 类；工具 ${implValues.size} 个；降级原因词表 ${prefixes.length} 项、列宽 varchar(${ddlWidth})；` +
  `状态字典 ${STATUS_DICTS.length} 类 ${[...dictData.values()].filter((r) => STATUS_DICTS.some(([t]) => t === r.type)).length / DIALECTS.length} 项 × ${DIALECTS.length} 方言）`
);
