#!/usr/bin/env node
// 审计保留策略下限门禁（设计稿 §12.4 越权约束 / §12.7 I-5）：保留窗口不许被悄悄缩短。
// 零依赖纯 ESM，本地与 CI 同一入口：node scripts/checks/audit-retention-guards.mjs
// 为什么需要：`audit-days` 是运行期可改的配置，改小一天就等于把审计留档窗口缩短一天。
//   底座式实现只在 BO 上写 `@Min(0)`，于是 1~29 天也能保存，清理任务照删——
//   合规留档要求（非 0 时 ≥30 天）一旦落到「页面能填什么」上就没有任何保证，必须做成门禁。
// 仓库 public，只报违规位置与数值，不回显配置内容。
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

if (violations.length) {
  console.error('\n✗ 审计保留策略下限门禁失败：');
  for (const v of violations) console.error(`  - ${v}`);
  console.error('\n修法：非 0 的保留天数一律 ≥30 天；0＝不限只在「自动清理跳过、手动清理被拒」的口径下成立。');
  console.error('      确需调整下限属 §12.4 契约改动，先改设计稿再改本脚本，并在豁免处写明出处。');
  process.exit(1);
}

console.log(`✓ 审计保留策略下限门禁通过（下限 ${FLOOR_DAYS} 天在服务层保存前拦截，默认值 / BO 边界 / 前端输入同档）`);
