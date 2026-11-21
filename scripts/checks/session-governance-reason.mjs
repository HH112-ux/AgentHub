#!/usr/bin/env node
// 会话治理留痕门禁（设计稿 §12.5 / §12.7 I-9）：跨本人范围操作他人会话必须有理由，且理由必须有人收。
// 零依赖纯 ESM，本地与 CI 同一入口：node scripts/checks/session-governance-reason.mjs
// 为什么需要：这条规则的三个环节分处控制器、服务层与前端页面，任何一处被"顺手改一下"都不会有测试红——
//   ① 控制器不再把 reason 交给服务层（或新加一个不带 reason 的批量入口），必填判定就只剩页面那句提示；
//   ② 摘掉 `@Log`，理由填得再认真也不进 `sys_oper_log`，留痕要求只剩"当场弹窗要个理由"；
//   ③ 服务层的理由校验挪到删除语句之后，被拒的操作照样已经把别人的会话删掉了。
//   这三类改动在功能回归里全都看不出来（界面照常、请求照成功），只能静态拦。
// 与 I-5 同口径：能被静态读出来的保证就静态拦，运行期语义（归属判定、批量混入他人时的整批拒绝）归单测。
// 仓库 public，只报违规位置，不回显理由文本与请求内容。
import { readFileSync, existsSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../..', import.meta.url));
const rel = (p) => relative(ROOT, p).replace(/\\/g, '/');
const AI = join(ROOT, 'code/back-end/ruoyi-modules/agent-hub-ai/src/main/java/org/dromara/ai');
const FRONT = join(ROOT, 'code/front-end/src');

const CONTROLLER_PATH = join(AI, 'controller/AiSessionController.java');
const IFACE_PATH = join(AI, 'service/IAiSessionService.java');
const IMPL_PATH = join(AI, 'service/impl/AiSessionServiceImpl.java');
const API_PATH = join(FRONT, 'api/ai/session/index.ts');
const VIEW_PATH = join(FRONT, 'views/ai/agent/session/index.vue');

// 理由长度上限。出处：`IAiSessionService.REASON_MAX_LENGTH`，前端常量与之同档（第 4 段核对）。
const REASON_MAX_LENGTH = 200;
// 三个治理写动作：控制器入口方法 → 服务层方法。出处：§12.5「必要时终止、删除或导出留档」。
const GOVERNANCE_ACTIONS = [
  { handler: 'close', method: 'closeWithValidByIds' },
  { handler: 'remove', method: 'deleteWithValidByIds' },
  { handler: 'export', method: 'buildExportData' }
];
// 服务实现里的归属判定入口；方法体里没有它，说明该动作已不受 I-9 约束。
const GUARD = 'requireReasonForOthers';
// 各动作的第一个副作用：理由校验必须排在它们之前，否则被拒的操作已经把数据改掉了。
const SIDE_EFFECTS = {
  closeWithValidByIds: 'runRegistry.cancel',
  deleteWithValidByIds: 'attachmentReclaimer',
  buildExportData: 'fillDisplayNames'
};

const violations = [];
const fail = (msg) => violations.push(msg);

function read(path) {
  if (!existsSync(path)) {
    fail(`缺文件：${rel(path)}（会话治理留痕的判定对象，缺失即视为规则被整体移除）`);
    return '';
  }
  return readFileSync(path, 'utf8');
}

// 从给定位置起截出大括号配对的主体，避免把同文件其他位置的写法当成本条规则的凭据。
function bodyFrom(src, at) {
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

// 签名之前的注解行（自下而上收集以 `@` 开头的行）。
// 不能按「上一个 `}`」切：`@PutMapping("/{ids}/close")` 这类路径变量里就带大括号，会把注解块切成半截。
function annotationsBefore(src, at) {
  if (at < 0) return '';
  const lines = src.slice(0, at).split('\n');
  // 签名前的缩进会被切成一个空尾行，先丢掉再向上收注解
  while (lines.length && lines[lines.length - 1].trim() === '') lines.pop();
  const picked = [];
  for (let i = lines.length - 1; i >= 0; i -= 1) {
    const line = lines[i].trim();
    if (!line.startsWith('@')) break;
    picked.unshift(line);
  }
  return picked.join('\n');
}

const controller = read(CONTROLLER_PATH);
const iface = read(IFACE_PATH);
const impl = read(IMPL_PATH);
const apiTs = read(API_PATH);
const viewVue = read(VIEW_PATH);

// ---------- 1. 契约面：三个治理动作都收理由，上限常量有单一来源 ----------

if (!new RegExp(`\\bint REASON_MAX_LENGTH\\s*=\\s*${REASON_MAX_LENGTH}\\s*;`).test(iface)) {
  fail(`${rel(IFACE_PATH)} 缺少 REASON_MAX_LENGTH = ${REASON_MAX_LENGTH}，控制器与前端两处上限失去同一来源（§12.7 I-9）`);
}
for (const { method } of GOVERNANCE_ACTIONS) {
  if (!new RegExp(`${method}\\([^)]*String reason\\s*\\)`).test(iface)) {
    fail(`${rel(IFACE_PATH)} 的 ${method} 签名不再收 reason，跨本人必填理由已无人判定（§12.7 I-9）`);
  }
}

// ---------- 2. 控制器：入口带 reason 查询参数、交给服务层、且仍被 @Log 收走 ----------

for (const { handler, method } of GOVERNANCE_ACTIONS) {
  const matched = controller.match(new RegExp(`public\\s+\\S+\\s+${handler}\\(`));
  if (!matched) {
    fail(`${rel(CONTROLLER_PATH)} 找不到 ${handler} 入口，无法核对理由参数（§12.7 I-9）`);
    continue;
  }
  const at = matched.index;
  const params = controller.slice(at, controller.indexOf('{', at));
  if (!/@RequestParam\b[\s\S]*?\bString reason\b/.test(params)) {
    fail(`${rel(CONTROLLER_PATH)} 的 ${handler} 没有带 @RequestParam 的 reason，服务层收不到理由`);
  }
  if (!/IAiSessionService\.REASON_MAX_LENGTH/.test(params)) {
    fail(`${rel(CONTROLLER_PATH)} 的 ${handler} 的 @Size 未引用 IAiSessionService.REASON_MAX_LENGTH，上限会与各调用方漂移`);
  }
  const handlerBody = bodyFrom(controller, at);
  if (handlerBody === null || !handlerBody.includes(`${method}(`) || !/,\s*reason\)/.test(handlerBody)) {
    fail(`${rel(CONTROLLER_PATH)} 的 ${handler} 没有把 reason 交给 ${method}，必填判定只剩页面提示`);
  }
  if (!/@Log\s*\(/.test(annotationsBefore(controller, at))) {
    fail(`${rel(CONTROLLER_PATH)} 的 ${handler} 摘掉了 @Log：理由无人落 sys_oper_log，留痕要求落空（§12.5）`);
  }
}

// ---------- 3. 服务实现：归属判定集中一处，且拦在任何副作用之前 ----------

const guardBody = bodyFrom(impl, impl.indexOf(`private void ${GUARD}(`));
if (guardBody === null) {
  fail(`${rel(IMPL_PATH)} 缺少 ${GUARD}，跨本人必填理由没有统一判定处`);
} else {
  if (!/getUserId\(\)/.test(guardBody)) {
    fail(`${rel(IMPL_PATH)} 的 ${GUARD} 不再比对 LoginHelper.getUserId()，「他人」失去定义`);
  }
  if (!/isBlank\(\s*reason\s*\)/.test(guardBody)) {
    fail(`${rel(IMPL_PATH)} 的 ${GUARD} 不再拒绝空白理由，必填等于可填`);
  }
  if (!guardBody.includes('REASON_MAX_LENGTH')) {
    fail(`${rel(IMPL_PATH)} 的 ${GUARD} 不再校验理由长度上限，接口注解成为唯一约束`);
  }
}

for (const { method } of GOVERNANCE_ACTIONS) {
  const decl = impl.match(new RegExp(`public\\s+\\S+\\s+${method}\\([^)]*String reason\\s*\\)`));
  if (!decl) {
    fail(`${rel(IMPL_PATH)} 的 ${method} 实现签名不再收 reason`);
    continue;
  }
  const methodBody = bodyFrom(impl, decl.index);
  if (methodBody === null) {
    fail(`${rel(IMPL_PATH)} 无法截出 ${method} 的方法体`);
    continue;
  }
  const guardAt = methodBody.indexOf(`${GUARD}(`);
  if (guardAt < 0) {
    fail(`${rel(IMPL_PATH)} 的 ${method} 未调用 ${GUARD}，该治理动作绕过必填理由`);
    continue;
  }
  const effectAt = methodBody.indexOf(SIDE_EFFECTS[method]);
  if (effectAt >= 0 && effectAt < guardAt) {
    fail(`${rel(IMPL_PATH)} 的 ${method} 里 ${SIDE_EFFECTS[method]} 排在理由校验之前，被拒的操作已产生副作用`);
  }
}

// ---------- 4. 前端：写接口把理由发出去，页面三个动作索取理由且上限同档 ----------

for (const fn of ['closeChatSessionById', 'delChatSession']) {
  const at = apiTs.indexOf(`export const ${fn}`);
  if (at < 0) {
    fail(`${rel(API_PATH)} 找不到 ${fn}，无法核对理由是否随请求发出`);
    continue;
  }
  const fnBody = apiTs.slice(at, apiTs.indexOf('};', at) + 2);
  if (!/params:\s*\{\s*reason\s*\}/.test(fnBody)) {
    fail(`${rel(API_PATH)} 的 ${fn} 不再把 reason 随请求发出，后端必填判定永远收到空值`);
  }
}

if (!new RegExp(`const REASON_MAX_LENGTH\\s*=\\s*${REASON_MAX_LENGTH}\\b`).test(viewVue)) {
  fail(`${rel(VIEW_PATH)} 的理由上限与 IAiSessionService.REASON_MAX_LENGTH 不同档（应为 ${REASON_MAX_LENGTH}）`);
}
if (!/exportSessionUrl\s*,\s*\{\s*\.\.\.params,\s*reason\s*\}/.test(viewVue)) {
  fail(`${rel(VIEW_PATH)} 的导出不再把 reason 并进下载请求参数，导出的留档理由丢了`);
}
for (const handler of ['handleClose', 'handleDelete', 'handleExport']) {
  const handlerBody = bodyFrom(viewVue, viewVue.indexOf(`const ${handler} = `));
  if (handlerBody === null) {
    fail(`${rel(VIEW_PATH)} 找不到 ${handler}，无法核对理由索取`);
    continue;
  }
  if (!/(collectReason|promptReason)\(/.test(handlerBody)) {
    fail(`${rel(VIEW_PATH)} 的 ${handler} 不再索取理由，管理员只会被后端拒一次`);
  }
}

if (violations.length) {
  console.error(`会话治理留痕检查未通过（${violations.length} 项，§12.5 / §12.7 I-9）：`);
  for (const item of violations) console.error(`- ${item}`);
  process.exit(1);
}
console.log('会话治理留痕检查通过：三个治理入口带理由、服务层拦在副作用之前、@Log 收痕、前端同档索取（§12.5 / §12.7 I-9）');
