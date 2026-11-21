#!/usr/bin/env node
// 附件生命周期门禁（设计稿 §12.6 / §12.7 I-10）：状态机四态、配额开关与到期回收的接线必须成套存在。
// 零依赖纯 ESM，本地与 CI 同一入口：node scripts/checks/attachment-lifecycle-guards.mjs
// 为什么需要：附件回收的后果是**删对象存储里的文件**，接线错了有两种方向，且都不报错：
//   ① 扫描丢了 status 谓词 → BOUND（正在会话里被查看）的附件文件被当成到期件删掉，用户看到的是图片打不开；
//   ② 登记侧不写 expire_at 或回收索引没建 → 到期件永远扫不到 / 每轮全表扫，附件无声残留到磁盘满。
//   这两种都只有起栈接上真实库与桶才会暴露，本地跑不出；而单测看的是判定取值，看不到 DDL、字典与调度接线。
// 与 I-5 同口径：能被静态读出来的保证就静态拦，运行期语义（游标推进、归属判定、配额计数）归单测。
// 状态取值以 Java 常量为唯一来源（第 1 段读出），DDL 默认值与字典项都往它对齐；本脚本只做**定点**对账，
// 不做全表字典对账（队长裁定 §12.7 I-16 已撤除机械字典对账，出处：设计稿 §12.7 A 段末段）。
// 仓库 public，只报违规位置，不回显文件内容与用户数据。
import { readFileSync, existsSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../..', import.meta.url));
const rel = (p) => relative(ROOT, p).replace(/\\/g, '/');
const SRC = join(ROOT, 'code/back-end/ruoyi-modules/agent-hub-ai/src/main/java/org/dromara/ai');
const SQL = join(ROOT, 'code/back-end/script/sql');

const ENTITY_PATH = join(SRC, 'domain/AiMessageAttachment.java');
const RECLAIMER_PATH = join(SRC, 'core/chat/AiAttachmentReclaimer.java');
const TASK_PATH = join(SRC, 'core/chat/AiAttachmentReclaimTask.java');
const SERVICE_PATH = join(SRC, 'service/impl/AiChatAttachmentServiceImpl.java');
const SESSION_SERVICE_PATH = join(SRC, 'service/impl/AiSessionServiceImpl.java');
const ADMIN_PATH = join(SRC, 'controller/AiChatAttachmentAdminController.java');
const DDL = {
  postgres: join(SQL, 'postgres/agenthub-ai-ddl.sql'),
  mysql: join(SQL, 'mysql/agenthub-ai-ddl.sql')
};
const SEED = {
  postgres: join(SQL, 'postgres/agenthub-ai-seed.sql'),
  mysql: join(SQL, 'mysql/agenthub-ai-seed.sql')
};

// 状态机的推进方向（§12.6）：第一个值即登记时的初始态，DDL 的 default 必须是它。
const LIFECYCLE = ['PENDING', 'BOUND', 'ORPHANED', 'RECYCLED'];
// 回收扫描必须能走这个索引，否则每小时一轮全表扫（§12.6 优先回收口径）
const RECLAIM_INDEX = 'idx_ai_message_attachment_reclaim';
const DICT_TYPE = 'ai_attachment_status';

const violations = [];
const fail = (msg) => violations.push(msg);

function read(path) {
  if (!existsSync(path)) {
    fail(`缺文件：${rel(path)}（附件生命周期的判定对象，缺失即视为规则被整体移除）`);
    return '';
  }
  return readFileSync(path, 'utf8');
}

// 从给定位置起截出大括号配对的主体（含签名到收尾 `}`），避免把同文件其他位置的写法当成本条规则的凭据。
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
// 把调用之后第一个大括号（往往是 for/if 体）当成方法体，检查就成了在看别处的代码。
function javaBody(src, methodDecl) {
  const matched = src.match(new RegExp(`^[ \t]*(?:public|private|protected)[^\n(]*[ \t]+${methodDecl}[ \t]*\\(`, 'm'));
  return matched ? bodyFrom(src, matched.index) : null;
}

// 两个标记都必须在，且 first 先于 second：回收的顺序与「先删对象再改库」都靠这条兜。
function orderWithin(body, first, second, what, where) {
  if (body === null) {
    fail(`${where} 无法核对${what}（方法体没截到）`);
    return;
  }
  const a = body.indexOf(first);
  const b = body.indexOf(second);
  if (a < 0) fail(`${where} 缺少 ${first}，${what}落空（§12.6）`);
  if (b < 0) fail(`${where} 缺少 ${second}，${what}落空（§12.6）`);
  if (a >= 0 && b >= 0 && a > b) {
    fail(`${where} 的 ${second} 排在 ${first} 之前，${what}已被反转（§12.6 / §12.7 I-10）`);
  }
}

// ---------- 1. 状态取值的唯一来源：实体常量 ----------

const entity = read(ENTITY_PATH);
for (const state of LIFECYCLE) {
  if (!new RegExp(`String\\s+STATUS_${state}\\s*=\\s*"${state}"`).test(entity)) {
    fail(`${rel(ENTITY_PATH)} 缺少 STATUS_${state} = "${state}"：DDL 默认值、字典与扫描谓词失去同一来源（§12.7 I-10）`);
  }
}
if (!/\bprivate\s+String\s+status;/.test(entity) || !/\bprivate\s+LocalDateTime\s+expireAt;/.test(entity)) {
  fail(`${rel(ENTITY_PATH)} 必须同时带 status 与 expireAt 字段，到期判定否则只能靠 create_time 猜（§12.6）`);
}

// ---------- 2. 双方言 DDL：列、默认值与回收索引成套 ----------

for (const [dialect, path] of Object.entries(DDL)) {
  const ddl = read(path);
  // 双方言都把这张表写成一行；用 [^\n]* 而不是配平括号，是为了不把紧随其后的索引语句读进来当成本表的列
  const table = ddl.match(/create table if not exists ai_message_attachment \(([^\n]*)/);
  if (!table) {
    fail(`${rel(path)} 找不到 ai_message_attachment 建表语句（${dialect}）`);
    continue;
  }
  const cols = table[1];
  if (!new RegExp(`status varchar\\(16\\) not null default '${LIFECYCLE[0]}'`).test(cols)) {
    fail(`${rel(path)} 的 ai_message_attachment.status 不是 "not null default '${LIFECYCLE[0]}'"：`
      + '历史行/漏写行的归属状态失去兜底，未发送件会被当成已发送而永不回收（§12.6）');
  }
  if (!/\bexpire_at\s+(timestamp|datetime)\b/.test(cols)) {
    fail(`${rel(path)} 的 ai_message_attachment 缺 expire_at 列，回收扫描没有到期依据（§12.6）`);
  }
  if (!new RegExp(`${RECLAIM_INDEX}[^)]*\\(status, expire_at\\)`).test(ddl)) {
    fail(`${rel(path)} 缺 ${RECLAIM_INDEX} (status, expire_at)：每小时一轮的到期扫描会全表扫（§12.7 I-10）`);
  }
}

// ---------- 3. 字典：四个状态都有中文口径（定点对账，不做全表校验） ----------

for (const [dialect, path] of Object.entries(SEED)) {
  const seed = read(path);
  if (!seed.includes(`'${DICT_TYPE}'`)) {
    fail(`${rel(path)} 缺字典类型 ${DICT_TYPE}，页面与审计日志看不到状态中文口径（§12.7 A 段）`);
    continue;
  }
  for (const state of LIFECYCLE) {
    if (!new RegExp(`'${state}',\\s*'${DICT_TYPE}'`).test(seed)) {
      fail(`${rel(path)} 的 ${DICT_TYPE} 缺 ${state} 项：该状态在库里可写却无字典口径（§12.7 I-10）`);
    }
  }
}

// ---------- 4. 登记 / 发送：写入生命周期，配额拦在落库之前 ----------

const service = read(SERVICE_PATH);
const registerBody = javaBody(service, 'registerChecked');
if (registerBody === null) {
  fail(`${rel(SERVICE_PATH)} 找不到 registerChecked，附件登记的写入点无法核对`);
} else {
  if (!registerBody.includes('setStatus(AiMessageAttachment.STATUS_PENDING)')) {
    fail(`${rel(SERVICE_PATH)} 登记时不写 STATUS_PENDING：行的状态只剩 DDL 默认值，配额与回收都按状态取片（§12.6）`);
  }
  if (!/setExpireAt\(\s*pendingExpireAt\(\)\s*\)/.test(registerBody)) {
    fail(`${rel(SERVICE_PATH)} 登记时不写 expire_at：暂存件永远扫不到，只能无声残留（§12.6）`);
  }
  orderWithin(registerBody, 'assertPendingQuota(', 'baseMapper.insert(', '配额校验拦在落库之前', rel(SERVICE_PATH));
}
const bindBody = javaBody(service, 'bindToMessage');
if (bindBody === null || !bindBody.includes('setStatus(AiMessageAttachment.STATUS_BOUND)')) {
  fail(`${rel(SERVICE_PATH)} 的 bindToMessage 不再把附件转 STATUS_BOUND：已发送附件仍占配额、仍会被当暂存件删掉（§12.6）`);
}
// 两条 TTL 开关在登记侧与回收侧必须同源同判：只看一个开关会导致「写了期限却永不扫」或反之
const ttlSwitch = javaBody(service, 'pendingExpireAt');
if (ttlSwitch === null || !ttlSwitch.includes('getPendingAttachmentTtlHours()') || !/<=\s*0/.test(ttlSwitch)) {
  fail(`${rel(SERVICE_PATH)} 的 pendingExpireAt 不再按 getPendingAttachmentTtlHours() <= 0 判开关，与回收侧失去同步（§12.6）`);
}

// ---------- 5. 回收：扫描谓词成套，且先删对象再改库 ----------

const reclaimer = read(RECLAIMER_PATH);
const batchBody = javaBody(reclaimer, 'batch');
if (batchBody === null) {
  fail(`${rel(RECLAIMER_PATH)} 找不到 batch 扫描条件构造处（§12.7 I-10）`);
} else {
  if (!batchBody.includes('AiMessageAttachment::getStatus')) {
    fail(`${rel(RECLAIMER_PATH)} 的到期扫描未按 status 过滤：BOUND（正在会话里查看的）附件文件会被当到期件删掉（§12.6）`);
  }
  if (!batchBody.includes('AiMessageAttachment::getExpireAt')) {
    fail(`${rel(RECLAIMER_PATH)} 的到期扫描未按 expire_at 取片：宽限期失去意义，转孤立的当场就能被删（§12.6）`);
  }
  if (!/orderByAsc\(\s*AiMessageAttachment::getExpireAt,\s*AiMessageAttachment::getId\s*\)/.test(batchBody)) {
    fail(`${rel(RECLAIMER_PATH)} 的扫描不再按 (expire_at, id) 稳定排序：游标推进会重复扫或漏扫同一批（§12.6）`);
  }
}
const pendingBody = javaBody(reclaimer, 'reclaimPending');
if (pendingBody === null || !/ttlHours\s*<=\s*0/.test(pendingBody)) {
  fail(`${rel(RECLAIMER_PATH)} 的 reclaimPending 不再按 ttlHours <= 0 关闭：与登记侧开关失去同源，「关闭」变成只关一半（§12.6）`);
}
orderWithin(javaBody(reclaimer, 'reclaim'), 'deleteObjectQuietly(', 'deletePhysicallyByIds(',
  '对象先于库行删除（否则文件失联而记录仍在）', `${rel(RECLAIMER_PATH)} 的 reclaim`);
orderWithin(javaBody(reclaimer, 'recycle'), 'deleteObjectQuietly(', 'attachmentMapper.update(',
  '对象先于留档状态落库（否则文件在、记录已标已回收）', `${rel(RECLAIMER_PATH)} 的 recycle`);
const orphanBody = javaBody(reclaimer, 'markOrphaned');
if (orphanBody === null) {
  fail(`${rel(RECLAIMER_PATH)} 找不到 markOrphaned，孤立态无人写入（§12.5 删除语义）`);
} else {
  if (!orphanBody.includes('setStatus(AiMessageAttachment.STATUS_ORPHANED)')) {
    fail(`${rel(RECLAIMER_PATH)} 的 markOrphaned 不写 STATUS_ORPHANED：删除后只剩逻辑删除标志，对象无人回收（§12.6）`);
  }
  if (!orphanBody.includes('getOrphanAttachmentTtlHours()')) {
    fail(`${rel(RECLAIMER_PATH)} 的 markOrphaned 不再按留档宽限写 expire_at：导出留档窗口失去依据（§12.6）`);
  }
  if (!orphanBody.includes('STATUS_PENDING') || !orphanBody.includes('STATUS_BOUND')) {
    fail(`${rel(RECLAIMER_PATH)} 的 markOrphaned 未把范围圈在 PENDING / BOUND：RECYCLED 会被拉回待回收，'已回收'不再是终态（§12.6）`);
  }
  // 只查「圈了 PENDING/BOUND」不够：把 RECYCLED 也加进范围同样能通过那条断言，却是把终态改回在制品
  if (orphanBody.includes('STATUS_RECYCLED')) {
    fail(`${rel(RECLAIMER_PATH)} 的 markOrphaned 把 RECYCLED 纳入转孤立范围：对象文件已删的行会被重新判为待回收（§12.6）`);
  }
}

// ---------- 6. 调度接线：自动回收真的会跑，且先出血的是占配额的暂存件 ----------

const task = read(TASK_PATH);
if (!/@EventListener\(\s*ApplicationReadyEvent\.class\s*\)/.test(task)) {
  fail(`${rel(TASK_PATH)} 缺少应用就绪监听：自动回收只剩运维接口能手动触发（§12.6）`);
}
const startBody = javaBody(task, 'start');
if (startBody === null || !startBody.includes('scheduleAtFixedRate')) {
  fail(`${rel(TASK_PATH)} 的 start 不再排周期任务：每小时自动回收不会发生（§12.6）`);
} else if (/^\s*return\s*;/m.test(startBody)) {
  fail(`${rel(TASK_PATH)} 的 start 里出现无条件提前 return：调度被开关整体关掉，孤立件将永不回收（§12.7 I-10）`);
} else if (/if\s*\([^)]*(?:[a-zA-Z]*[tT]tlHours|Hours\(\))/.test(startBody)) {
  // 暂存阈值只该关「暂存件那一遍」：把它挪到调度入口上，孤立件与暂存件就被同一个开关捆死了
  fail(`${rel(TASK_PATH)} 的 start 把周期调度挂在了 TTL 开关上：暂存阈值 <=0 时孤立件也将永不回收（§12.6 / §12.7 I-10）`);
}
if (!/@PreDestroy/.test(task)) {
  fail(`${rel(TASK_PATH)} 缺少 @PreDestroy：调度线程池不随应用关闭，滚动发布会挂住优雅停机（§12.6）`);
}
orderWithin(javaBody(task, 'runNow'), 'reclaimPending(', 'reclaimOrphans(',
  '暂存件先于孤立件回收（占配额的那批先出血）', rel(TASK_PATH));

// ---------- 7. 删除点接线：孤立态由会话/整轮删除产生，不是孤立函数 ----------

const sessionService = read(SESSION_SERVICE_PATH);
const deleteRound = javaBody(sessionService, 'deleteChatRound');
if (deleteRound === null || !deleteRound.includes('orphanByMessageIds(')) {
  fail(`${rel(SESSION_SERVICE_PATH)} 的 deleteChatRound 未把附件转孤立：整轮删除后对象文件无人回收（§12.5 删除语义）`);
}
if (deleteRound !== null) {
  orderWithin(deleteRound, 'pendingConfirm()', 'orphanByMessageIds(',
    '附件推进回收排在各项守卫之后', `${rel(SESSION_SERVICE_PATH)} 的 deleteChatRound`);
}
const deleteSessions = javaBody(sessionService, 'deleteWithValidByIds');
if (deleteSessions === null || !deleteSessions.includes('orphanBySessionIds(')) {
  fail(`${rel(SESSION_SERVICE_PATH)} 的 deleteWithValidByIds 未把附件转孤立：删会话等于把文件永久留在桶里（§12.5 删除语义）`);
}

// ---------- 8. 运维入口：立即回收一轮仍受权限保护、仍回报孤立件统计 ----------

const admin = read(ADMIN_PATH);
const reclaimHandler = javaBody(admin, 'reclaim');
if (reclaimHandler === null) {
  fail(`${rel(ADMIN_PATH)} 找不到 reclaim 入口，验收无法立即跑一轮回收`);
} else {
  if (!reclaimHandler.includes('recycled')) {
    fail(`${rel(ADMIN_PATH)} 的 reclaim 不再回报 recycled：孤立件的回收结果被统计成 0，验收会误判（§12.6）`);
  }
}
if (!/@SaCheckPermission\(\s*"ai:chat:attachment:reclaim"\s*\)/.test(admin)) {
  fail(`${rel(ADMIN_PATH)} 的 reclaim 失去 ai:chat:attachment:reclaim 权限校验：删对象存储文件的动作变成匿名可触发（§12.7 I-10）`);
}

if (violations.length) {
  console.error(`附件生命周期检查未通过（${violations.length} 项，§12.6 / §12.7 I-10）：`);
  for (const item of violations) console.error(`- ${item}`);
  process.exit(1);
}
console.log('附件生命周期检查通过：四态常量同源、双方言列与回收索引成套、字典齐项、扫描按状态与期限取片、先删对象再改库、周期任务与删除点接线齐备（§12.6 / §12.7 I-10）');
