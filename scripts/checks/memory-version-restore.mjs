#!/usr/bin/env node
// 记忆版本恢复门禁（设计稿 §12.2 / §12.7 I-11）：独立权限、二次确认与操作日志三件事必须成套存在。
// 零依赖纯 ESM，本地与 CI 同一入口：node scripts/checks/memory-version-restore.mjs
// 为什么需要：回滚 MEMORY.md 是**覆盖用户长期记忆**的破坏性动作，历史只增不删，一旦执行就没有撤销按钮。
//   接线错了有两种方向，且都不报错：
//   ① 入口权限退回 `ai:memory:edit` → 只要有权改记忆的人就能整篇覆盖历史版本，独立授权形同虚设；
//   ② 确认判据从服务层挪走（只留前端弹窗或入口注解）→ 日后新增的批量 / 渠道入口绕开 Controller 就无声免检，
//      而被拒的请求若排在自动快照之后，还会往版本历史里塞进没人要的行。
//   这三处分散在 Java 常量、控制器、服务实现、双方言菜单脚本与前端页面，单测各看一角，静态门禁才接得住整套。
// 与 I-5 / I-9 同口径：能被静态读出来的保证就静态拦，判定取值（确认位真假、正文逐字一致）归单测。
// 仓库 public，只报违规位置，不回显文件内容与用户数据。
import { readFileSync, existsSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../..', import.meta.url));
const rel = (p) => relative(ROOT, p).replace(/\\/g, '/');
const SRC = join(ROOT, 'code/back-end/ruoyi-modules/agent-hub-ai/src/main/java/org/dromara/ai');
const SQL = join(ROOT, 'code/back-end/script/sql');
const FRONT = join(ROOT, 'code/front-end/src');

const KEYS_PATH = join(SRC, 'contract/AiPermissionKeys.java');
const CONTROLLER_PATH = join(SRC, 'controller/AiMemoryController.java');
const IMPL_PATH = join(SRC, 'service/impl/AiMemoryServiceImpl.java');
const SERVICE_API_PATH = join(SRC, 'service/IAiMemoryService.java');
const FRONT_PAGE_PATH = join(FRONT, 'views/ai/memory/index.vue');
const FRONT_TYPES_PATH = join(FRONT, 'api/ai/memory/types.ts');
const MENU = {
  postgres: join(SQL, 'postgres/agenthub-ai-menu.sql'),
  mysql: join(SQL, 'mysql/agenthub-ai-menu.sql')
};

const PERM = 'ai:memory:version:restore';
// 「长期记忆」菜单主键：新按钮必须挂在它下面，否则角色配置页里找不到这个开关
const PARENT_MENU_ID = '1761400000000020184';

const violations = [];
const fail = (msg) => violations.push(msg);

function read(path) {
  if (!existsSync(path)) {
    fail(`缺文件：${rel(path)}（版本恢复的判定对象，缺失即视为规则被整体移除）`);
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

// 按方法**声明**截 Java 方法体：必须从行首的可见性修饰词起匹配，否则会撞上 `x = foo(` 这类调用点，
// 把调用之后第一个大括号（往往是 for/if 体）当成方法体，检查就成了在看别处的代码（§12.7 I-10 的教训）。
function javaBody(src, methodDecl) {
  const matched = src.match(new RegExp(`^[ \\t]*(?:public|private|protected)[^\\n(]*[ \\t]+${methodDecl}[ \\t]*\\(`, 'm'));
  return matched ? bodyFrom(src, matched.index) : null;
}

// 两个标记都必须在，且 first 先于 second：确认判据排在副作用之前这条契约靠它兜住。
function orderWithin(body, first, second, what, where) {
  if (body === null) {
    fail(`${where} 无法核对${what}（方法体没截到）`);
    return;
  }
  const a = body.indexOf(first);
  const b = body.indexOf(second);
  if (a < 0) fail(`${where} 缺少 ${first}，${what}落空（§12.2）`);
  if (b < 0) fail(`${where} 缺少 ${second}，${what}落空（§12.2）`);
  if (a >= 0 && b >= 0 && a > b) {
    fail(`${where} 的 ${second} 排在 ${first} 之前，${what}已被反转（§12.2 / §12.7 I-11）`);
  }
}

// ---------- 1. 权限标识的唯一常量源 ----------

const keys = read(KEYS_PATH);
if (!new RegExp(`String\\s+AI_MEMORY_VERSION_RESTORE\\s*=\\s*"${PERM}"`).test(keys)) {
  fail(`${rel(KEYS_PATH)} 缺少 AI_MEMORY_VERSION_RESTORE = "${PERM}"：菜单与注解失去同一来源（§12.7 I-11）`);
}

// ---------- 2. 双方言菜单：按钮都登记了，且挂在「长期记忆」下 ----------

for (const [dialect, path] of Object.entries(MENU)) {
  const menu = read(path);
  const row = menu.match(new RegExp(`values \\((\\d+), '[^']*', (${PARENT_MENU_ID}), (\\d+), [^)]*?'${PERM}'`));
  if (!row) {
    fail(`${rel(path)} 未把 ${PERM} 登记为「长期记忆」（菜单主键 ${PARENT_MENU_ID}）下的按钮：`
      + '权限标识在常量里却没人能授予，页面上的恢复按钮对任何人都不可见（§12.2）');
    continue;
  }
  if (row[1] === PARENT_MENU_ID) {
    fail(`${rel(path)} 的 ${PERM} 按钮菜单主键与父菜单相同，成了自引用的行`);
  }
}

// ---------- 3. 控制器：独立权限、留痕切面、把确认位如实交给服务层 ----------

const controller = read(CONTROLLER_PATH);
const restoreEntry = controller.match(/@SaCheckPermission\([^\n]*\)[\s\S]{0,600}?\brestoreVersion\s*\(/);
if (!restoreEntry) {
  fail(`${rel(CONTROLLER_PATH)} 找不到版本恢复入口，权限与留痕无法核对（§12.7 I-11）`);
} else {
  const entry = restoreEntry[0];
  if (!entry.includes(`"${PERM}"`)) {
    fail(`${rel(CONTROLLER_PATH)} 的版本恢复入口不再单独授权（当前不是 ${PERM}）：`
      + '复用「记忆修改」等于把覆盖整篇长期记忆的动作挂在日常编辑权限上，独立审批形同虚设（§12.2）');
  }
  if (!/@Log\s*\(\s*title\s*=\s*"AI长期记忆版本恢复"/.test(entry)) {
    fail(`${rel(CONTROLLER_PATH)} 的版本恢复入口失去 @Log：谁把记忆回滚到哪一版没人留下记录（§12.2）`);
  }
}
const restoreCall = javaBody(controller, 'restoreVersion');
if (restoreCall === null) {
  fail(`${rel(CONTROLLER_PATH)} 截不到 restoreVersion 方法体，确认位传递无法核对`);
} else if (!/Boolean\.TRUE\.equals\(\s*bo\.getConfirmed\(\)\s*\)/.test(restoreCall)) {
  fail(`${rel(CONTROLLER_PATH)} 没有把确认位按「显式 true 才算确认」交给服务层：`
    + '未填字段一旦当成默认同意，二次确认就只剩前端自觉（§12.7 I-11）');
}

// ---------- 4. 服务实现：判据落在唯一写点上，且排在任何副作用之前 ----------

const api = read(SERVICE_API_PATH);
if (!new RegExp(`String\\s+RESTORE_CONFIRM_REQUIRED\\s*=`).test(api)) {
  fail(`${rel(SERVICE_API_PATH)} 缺少 RESTORE_CONFIRM_REQUIRED 常量：拒因文案失去同源，前后端各写一句会漂移（§12.7 I-11）`);
}

const impl = read(IMPL_PATH);
const restoreBody = javaBody(impl, 'restoreVersion');
if (restoreBody === null) {
  fail(`${rel(IMPL_PATH)} 截不到 restoreVersion 方法体，二次确认无法核对（§12.7 I-11）`);
} else {
  if (!/if\s*\(\s*!confirmed\s*\)\s*\{[^}]*throw\s+new\s+ServiceException\(\s*RESTORE_CONFIRM_REQUIRED\s*\)/.test(restoreBody)) {
    fail(`${rel(IMPL_PATH)} 的 restoreVersion 不再在服务层拦未确认请求：注解只覆盖 HTTP 入口，`
      + '日后新增的批量或渠道入口会绕开二次确认（§12.2 / §12.7 I-11）');
  }
  orderWithin(restoreBody, 'if (!confirmed)', 'agentService.getEnabledAgent(',
    '二次确认排在读工作区之前', `${rel(IMPL_PATH)} 的 restoreVersion`);
  orderWithin(restoreBody, 'if (!confirmed)', 'snapshotMemory(',
    '二次确认排在自动快照之前（被拒的请求不该往只增不删的版本历史里塞行）', `${rel(IMPL_PATH)} 的 restoreVersion`);
}

// ---------- 5. 前端：确认位来自勾选，且入口按新权限显隐 ----------

const page = read(FRONT_PAGE_PATH);
if (!page.includes(`v-hasPermi="['${PERM}']"`)) {
  fail(`${rel(FRONT_PAGE_PATH)} 的恢复入口不再按 ${PERM} 显隐：只有编辑权限的用户会看到一个必然失败的按钮（§12.2）`);
}
if (!/el-checkbox[^>]*v-model="restoreForm\.confirmed"/.test(page)) {
  fail(`${rel(FRONT_PAGE_PATH)} 缺少绑定 restoreForm.confirmed 的勾选项：确认位没有用户真正表态的来源（§12.7 I-11）`);
}
if (!/:disabled="!restoreForm\.confirmed"/.test(page)) {
  fail(`${rel(FRONT_PAGE_PATH)} 的「确认恢复」按钮不再按勾选禁用：弹窗一开就能直接覆盖（§12.7 I-11）`);
}
if (!/confirmed:\s*restoreForm\.value\.confirmed/.test(page)) {
  fail(`${rel(FRONT_PAGE_PATH)} 的请求体丢掉 confirmed：页面勾选与接口参数断链，勾了也等于没勾（§12.7 I-11）`);
}
const frontTypes = read(FRONT_TYPES_PATH);
if (!/\bconfirmed:\s*boolean\b/.test(frontTypes)) {
  fail(`${rel(FRONT_TYPES_PATH)} 的恢复参数缺少 confirmed: boolean，调用点漏传不会被类型检查抓到（§12.7 I-11）`);
}

if (violations.length) {
  console.error(`记忆版本恢复检查未通过（${violations.length} 项，§12.2 / §12.7 I-11）：`);
  for (const item of violations) console.error(`- ${item}`);
  process.exit(1);
}
console.log(`记忆版本恢复检查通过：${PERM} 常量与双方言按钮同源、入口独立授权且留痕在册、确认位由勾选进入服务层并排在副作用之前（§12.2 / §12.7 I-11）`);
