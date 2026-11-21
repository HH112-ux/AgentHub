# AgentHub 一周全量 · 交付计划（队长单线执行）

> **分工变更**：三人排班已于 2026-11-20 取消，裁决记录见[设计稿 §8.1](../specs/2026-11-20-agenthub-decomposition-design.md)。本文件的「后端面 / 前端面」是工作面而非责任人，**均为队长单线执行**；`feature/lead-contracts`、`feature/backend-core`、`feature/frontend-shell` 三条长期分支未启用，实际交付改为按制品面开 PR：后端全树、前端全树、契约与门禁重冻。任务范围、验收标准与 §7.3 降级口径全部保留未动。

> **For agentic workers:** REQUIRED SUB-SKILL: 用 superpowers:executing-plans 按任务逐条执行本计划；步骤用 `- [ ]` 复选框跟踪。逐功能的 TDD 细化任务不再单列计划文件，直接由设计稿 §7.1 交付清单与 §12.7 C 段增量表驱动。

**Goal:** 在 5 个工作日内单线交付《设计稿》§7.1 的一周全量范围（40 表、34 页、98 按钮权限、全部能力域），并按 §10.2 发版门禁上线。

**Architecture:** 契约先行 + 工作面并行（架构/契约/权限 HITL/RAG/MCP/安全/审计发布，后端业务与集成，前端 34 页与介绍页三条工作面）。跨面协作靠 D1 冻结的代码侧常量与 SQL 完成，前端按契约 mock、不等后端；集成只在每日固定窗口发生。单线执行下「并行」指工作面交替而非人力并行，契约面仍是所有改动的收敛点。

**Tech Stack:** JDK 21、Spring Boot 4.1.1、AgentScope Java 2.0.4、开源中后台底座 6.0.0、Vue 3.5 + TypeScript + Element Plus + Vite + Pinia、pnpm（Node ≥ 20.19）、PostgreSQL 14 / MySQL 8.0、Redis、Milvus + etcd、MinIO/OSS、Nginx、Qoder Sites。

**Spec:** [docs/superpowers/specs/2026-11-20-agenthub-decomposition-design.md](../specs/2026-11-20-agenthub-decomposition-design.md)

## Global Constraints

以下取值逐字取自设计稿，每个任务的隐含前提：

- 排期：一周＝**全量目标**，不做范围削减；只允许 §7.3 的客观降级，由队长当日 20:00 前裁定并记录。
- 部署：真实系统前端与 API **同源于云服务器 Nginx**；PG 栈 `:8080`、MySQL 栈 `:8081`；Qoder Sites 仅承载介绍页、跳转链接与公开问答，不放密钥、不接触业务数据。
- 内核：业务主链路内核为云服务器上的 AgentScope Java 2.0.4；云端 Agent 不得接入业务知识库、业务工具、会话数据或 PII。
- 命名：新建文件与目录一律 ASCII 小写 kebab-case，名称不得含外部项目名（设计稿 §3.2）；环境变量前缀 `AGENTHUB_*`；Redis 键前缀 `agenthub:ai:`；Maven 模块 `agent-hub-ai`，包 `org.dromara.ai`。
- 数据：40 张 `ai_` 表双方言同步维护且幂等（`if not exists` / `on conflict do nothing`）。
- 接口：路由 `/ai/{域}/{资源}`；每接口带 `ai:{模块}:{动作}` 权限标识并同步登记菜单脚本；RSA 加解密白名单排除流式与文件流。
- 事件：SSE 事件必须携带 `type/sessionId/turnId/seq/payload`；确认卡片刷新后必须可还原。
- 降级：只允许 §6.3 冻结的 11 个原因码：`EXPERT_FAILED`、`EXPERT_TIMEOUT`、`EXPERT_EMPTY`、`ROUTE_CONTRACT_INVALID`、`BUDGET_EXCEEDED`、`BM25_NOT_READY`、`RERANK_UNAVAILABLE`、`SAFETY_ENGINE_UNAVAILABLE`、`MEDIA_FAILED`、`ADMIN_TERMINATED`、`STALE_REAPED`。
- RAG 基线：hit@1 ≥ 75.0%、hit@3 ≥ 89.3%、hit@5 ≥ 98.2%、MRR@5 ≥ 0.829（56 用例 / 10 篇语料）。
- 密钥：不入库不入仓；模型密钥密文落库不回显；测试机密放 `.env.local`（已 gitignore）。
- Git：远程固定 `origin`＝`https://github.com/HH112-ux/AgentHub.git`；日常开发不直推 `main`，走 `feature/<scope>` / `fix/<scope>` + PR + 至少一名非作者评审；提交用约定式 `<type>: <中文简述>`。
- 契约：§6 与 §12（含 §12.7 效力修正）冻结后改动需队长批准，并同步后端、前端、契约三条工作面。
- 节拍：站会 09:30（≤15 分钟，只讲当日验收点/阻塞/契约变更申请）；联调窗口 14:00–17:00；验收 18:00；降级裁定 20:00 前。

---

## 交付物地图（本计划创建或修改的文件）

| 文件 | 单一职责 | Owner |
|---|---|---|
| `code/back-end/script/sql/postgres/agenthub-ai-ddl.sql` | PG 侧 40 表 DDL | 队长 |
| `code/back-end/script/sql/mysql/agenthub-ai-ddl.sql` | MySQL 侧 40 表 DDL | 队长 |
| `code/back-end/script/sql/{postgres,mysql}/agenthub-ai-menu.sql` | 菜单 + 98 按钮权限标识（按模块分节追加） | 队长（B/C 各自模块段落追加） |
| `code/back-end/script/sql/{postgres,mysql}/agenthub-ai-seed.sql` | 内置只读工具、示例模型、示例知识库等种子 | 队长 |
| `.../org/dromara/ai/contract/AiEventType.java` | SSE 事件类型唯一常量源 | 队长 |
| `.../org/dromara/ai/contract/AiDegradeReason.java` | 降级原因码唯一常量源 | 队长 |
| `.../org/dromara/ai/contract/AiToolImpl.java` | 工具 impl 名唯一常量源 | 队长（B 追加常量） |
| `.../org/dromara/ai/contract/AiPermissionKeys.java` | 权限标识唯一常量源 | 队长 |
| `code/front-end/src/api/ai/stream.ts` | SSE 客户端 + 事件类型镜像（TS 联合） | 前端面 |
| `code/front-end/src/types/ai-contract.ts` | 事件载荷、原因码、权限标识的 TS 镜像 | 前端面 |
| `e2e/http/L00-contract-consistency.mjs` | 校验 Java 常量与 TS 镜像逐字一致 | 前端面（队长） |
| `deploy/deploy-pg/.env.example`、`deploy/deploy-mysql/.env.example` | 双栈环境变量清单 | 队长 |
| `docs/superpowers/plans/2026-11-20-week1-daily-log.md` | 每日验收结果与降级记录（滚动追加，不改写历史） | 队长 |
| [设计稿 §12.7](../specs/2026-11-20-agenthub-decomposition-design.md) | 契约重冻后的效力修正与增量补齐清单 | 队长 |
| [每日执行日志 D3 行](2026-11-20-week1-daily-log.md) | 三面 PR 的交付读数与本次契约变更明细 | 队长 |

---

### Task 1: 三线分支与底座导入基线

**Files:**
- Create: 分支 `feature/lead-contracts`、`feature/backend-core`、`feature/frontend-shell`
- Create: `code/back-end/`、`code/front-end/`（底座 6.0.0 导入）
- Modify: `docs/superpowers/plans/2026-11-20-week1-daily-log.md`

**Interfaces:**
- Produces: 各工作面的短任务分支与首个基线提交；后续所有 PR 的目标基线。

- [x] **Step 1: 同步 main 并建任务分支（单线执行，三条长期分支未启用）**

```bash
git fetch origin && git switch main && git pull --ff-only origin main
git switch -c feature/<scope>   # 一个分支只承载一个独立任务，做完即开 PR 合入 main
```
预期：`git status` 干净，任务分支从最新 `origin/main` 起。原计划的 `feature/lead-contracts`、`feature/backend-core`、`feature/frontend-shell` 三条长期分支不启用——单线下长分支会把返工攒到最后，改成短分支 + 频繁合入。

- [x] **Step 2: 队长提供底座制品（D1 已完成）**

开源中后台底座 6.0.0 的后端与前端发行包已放入 `vendor/`（不入库，`.gitignore` 已补 `vendor/`），解包为 `vendor/back-end` 与 `vendor/front-end`，sha256 与解包事实见[每日执行日志 D1 备注](2026-11-20-week1-daily-log.md)。

- [ ] **Step 3: B 导入后端并做首个基线提交**

```bash
cp -a vendor/back-end/. code/back-end/
```

导入后先做三件裁剪，再编译：

1. `ruoyi-admin/pom.xml` 放开被注释的 `org.postgresql:postgresql`——PG 栈（`:8080`）没有它起不来，MySQL 栈不受影响。
2. 从 `ruoyi-modules/pom.xml` 去掉 `<module>ruoyi-ai</module>`，`script/sql/ry_ai.sql` 不加载，前端 `views/ai/chat` 与底座自带 `aichat` 菜单一并删除：该模块 Java 包根与 §3.2 冻结的 `org.dromara.ai` 同名（split package），其页面占住 `src/views/ai/` 命名空间。它的 `sai_*` 表与我们的 40 张 `ai_*` 不重名，删除无数据迁移。
3. `mvn` 的 `profiles.active` 保持 `prod`，但**不改底座 `application-prod.yml` 里的明文默认值**——运行期值由 `deploy/{stack}/config/application-agenthub.yml` 挂载覆盖，机密只在 `.env`。

```bash
cd code/back-end && ./mvnw -o -q -DskipTests package
git add code/back-end && git commit -m "chore: 导入中后台底座 6.0.0 后端并裁剪自带 ai 模块"
```
预期：`BUILD SUCCESS`；`code/back-end/pom.xml` 中 `<modules>` 含 `ruoyi-modules/agent-hub-ai`（无则本步新建空模块并注册）；`code/back-end/ruoyi-modules/` 下不再有 `ruoyi-ai`。

- [ ] **Step 4: C 导入前端并做首个基线提交**

```bash
cp -a vendor/front-end/. code/front-end/
cd code/front-end && pnpm install && pnpm build
git add code/front-end && git commit -m "chore: 导入中后台底座 6.0.0 前端"
```
预期：`dist/` 产物生成、`pnpm build` 退出码 0。

- [ ] **Step 5: 建每日执行日志文件**

创建 `docs/superpowers/plans/2026-11-20-week1-daily-log.md`，内容只有表头，后续每天追加一行：

```markdown
# 一周全量 · 每日执行日志

| 日 | 当日验收点 | 达成 | 未达项 | §7.3 降级裁定 | 契约变更 |
|---|---|---|---|---|---|
| D1 | 双栈可起可登录 / 40 表双方言幂等 / 模型连通 / echo 流打通 | | | | |
```

- [ ] **Step 6: 三条分支各自推送并开 PR（互不阻塞）**

```bash
git push -u origin feature/backend-core
gh pr create --base main --title "chore: 三线基线与底座导入" --body "验证：mvn package 通过、pnpm build 通过"
```
预期：PR 由另一名成员评审通过后合并；`main` 得到可构建骨架。

---

### Task 2: D1 契约冻结四件套（本计划的阻塞枢纽，12:00 前必须合并）

**Files:**
- Create: `.../org/dromara/ai/contract/` 四个常量类、双方言 DDL 与菜单脚本
- Create: `code/front-end/src/types/ai-contract.ts`、`e2e/http/L00-contract-consistency.mjs`
- Test: `e2e/http/L00-contract-consistency.mjs`（本身即契约测试）

**Interfaces:**
- Produces: 三条工作面共享的唯一事实源。D1 冻结读数为事件 13 型、原因码 11 个；后端与前端全树入仓后按设计稿 D11（§12.7）重冻为 SSE 事件 16 类、降级原因词表 8 项、权限标识 131 个、工具实现 30 个、40 表 DDL，重冻明细见[每日执行日志](2026-11-20-week1-daily-log.md) D3 行；权限标识现值 132 个（§12.7 I-11 于 2026-11-21 按 §12.2 原口径加回 `ai:memory:version:restore`）。

- [ ] **Step 1: 先写契约一致性检查（此时必然失败——TS 镜像还不存在）**

创建 `e2e/http/L00-contract-consistency.mjs`（零依赖纯 ESM）：

```js
import { readFileSync } from 'node:fs';

const JAVA = 'code/back-end/ruoyi-modules/agent-hub-ai/src/main/java/org/dromara/ai/contract';
const TS = 'code/front-end/src/types/ai-contract.ts';

const javaStrings = (file) =>
  [...readFileSync(`${JAVA}/${file}`, 'utf8').matchAll(/"([a-z0-9_]+)"/g)].map((m) => m[1]);
const javaIdents = (file) =>
  [...readFileSync(`${JAVA}/${file}`, 'utf8').matchAll(/^\s{4}([A-Z][A-Z0-9_]+)\s*\(/gm)].map((m) => m[1]);
const tsLiterals = (name) => {
  const src = readFileSync(TS, 'utf8');
  const body = src.match(new RegExp(`type ${name} =([^;]+);`, 's'))?.[1];
  if (!body) throw new Error(`TS 缺少 type ${name}`);
  return [...body.matchAll(/'([^']+)'/g)].map((m) => m[1]);
};

const diffs = [];
const cmp = (label, a, b) => {
  const missing = a.filter((x) => !b.includes(x));
  const extra = b.filter((x) => !a.includes(x));
  if (missing.length || extra.length) diffs.push({ label, missing, extra });
};

cmp('SSE 事件类型', javaStrings('AiEventType.java'), tsLiterals('AiEventType'));
cmp('降级原因码', javaIdents('AiDegradeReason.java'), tsLiterals('AiDegradeReason'));
cmp('权限标识', javaStrings('AiPermissionKeys.java'), tsLiterals('AiPermissionKey'));

if (diffs.length) {
  console.error('契约不一致：', JSON.stringify(diffs, null, 2));
  process.exit(1);
}
console.log('契约一致：事件 / 原因码 / 权限标识 三方逐字对齐');
```

- [ ] **Step 2: 运行确认失败**

Run: `node e2e/http/L00-contract-consistency.mjs`
Expected: FAIL——`TS 缺少 type AiEventType`（或文件不存在报错）。这是预期红灯。

- [ ] **Step 3: 队长写 Java 常量类**

```java
// org/dromara/ai/contract/AiEventType.java
public enum AiEventType {
    TEXT_DELTA("text_delta"), ROUTE_HINT("route_hint"),
    EXPERT_START("expert_start"), EXPERT_RESULT("expert_result"),
    TOOL_CALL_START("tool_call_start"), TOOL_CALL_END("tool_call_end"),
    CONFIRM_REQUEST("confirm_request"), CONFIRM_RESULT("confirm_result"),
    PROGRESS("progress"), SOURCES("sources"),
    STOPPED("stopped"), TURN_DONE("turn_done"), TURN_ERROR("turn_error");
    private final String wire;
    AiEventType(String wire) { this.wire = wire; }
    public String wire() { return wire; }
}
```

```java
// org/dromara/ai/contract/AiDegradeReason.java
public enum AiDegradeReason {
    EXPERT_FAILED, EXPERT_TIMEOUT, EXPERT_EMPTY, ROUTE_CONTRACT_INVALID,
    BUDGET_EXCEEDED, BM25_NOT_READY, RERANK_UNAVAILABLE,
    SAFETY_ENGINE_UNAVAILABLE, MEDIA_FAILED, ADMIN_TERMINATED, STALE_REAPED
}
```

`AiPermissionKeys.java` 为 `public static final String` 常量，值形如 `"ai:knowledge:list"`，常量名由值按 `:` 转大写蛇形（`AI_KNOWLEDGE_LIST`），因此每段只允许 `[a-z0-9]`，不得含 `-`（原 `ai:audit:retention:dry-run` 已改名 `ai:audit:retention:preview`）；取值以 `agenthub-ai-menu.sql` 的 `sys_menu.perms` 为准，共 132 条，其中含设计稿 §12 新增的 12 个标识（`ai:health:check`、`ai:audit:retention:view|edit|purge|unlimited`、`ai:session:governance:list|terminate|delete|export`、`ai:memory:version:restore`、`ai:attachment:recycle`、`ai:runtime:unattended:edit`）。`AiToolImpl.java` 同理，值为 `ai_tool.impl_name` 的大写蛇形（内置 11 个：`GET_CURRENT_TIME`、`ROUTE_DECISION`、`MEMORY_SEARCH`、`TODO_WRITE` 等）；B 线新增的底座业务工具按同一风格追加（`DEPT_QUERY`、`ANNOUNCEMENT_WRITE`、`DAILY_DIGEST_WRITE`）。

> 本步与 Step 4 的词表是**冻结前的意图稿**，不是现行登记名：实现落库时按「模块 = URL 资源段」重命名，`ai:audit:retention:view|purge|unlimited`、`ai:session:governance:*`、`ai:attachment:recycle`、`ai:runtime:unattended:edit` 均未引入，现行取值以[设计稿 §6.1「重冻后的登记名」](../specs/2026-11-20-agenthub-decomposition-design.md)与 `AiPermissionKeys.java` 为准；`ai:memory:version:restore` 是唯一按本稿登记的（§12.7 I-11，2026-11-21 队长批准）。

- [ ] **Step 4: C 写 TS 镜像（字符串必须与 Step 3 逐字相同）**

```ts
// code/front-end/src/types/ai-contract.ts
export type AiEventType =
  | 'text_delta' | 'route_hint' | 'expert_start' | 'expert_result'
  | 'tool_call_start' | 'tool_call_end' | 'confirm_request' | 'confirm_result'
  | 'progress' | 'sources' | 'stopped' | 'turn_done' | 'turn_error';

export type AiDegradeReason =
  | 'EXPERT_FAILED' | 'EXPERT_TIMEOUT' | 'EXPERT_EMPTY' | 'ROUTE_CONTRACT_INVALID'
  | 'BUDGET_EXCEEDED' | 'BM25_NOT_READY' | 'RERANK_UNAVAILABLE'
  | 'SAFETY_ENGINE_UNAVAILABLE' | 'MEDIA_FAILED' | 'ADMIN_TERMINATED' | 'STALE_REAPED';

export type AiPermissionKey =
  | 'ai:health:check' | 'ai:audit:retention:view' | 'ai:audit:retention:edit'
  | 'ai:audit:retention:purge' | 'ai:audit:retention:unlimited'
  | 'ai:session:governance:list' | 'ai:session:governance:terminate'
  | 'ai:session:governance:delete' | 'ai:session:governance:export'
  | 'ai:memory:version:restore' | 'ai:attachment:recycle' | 'ai:runtime:unattended:edit';
```

- [ ] **Step 5: 跑契约测试确认转绿**

Run: `node e2e/http/L00-contract-consistency.mjs`
Expected: PASS——`契约一致：事件 / 原因码 / 权限标识 三方逐字对齐`

- [ ] **Step 6: 队长写双方言 DDL 与菜单脚本并验证幂等**

```bash
docker compose -f deploy/deploy-pg/docker-compose.yml up -d db redis
psql -f code/back-end/script/sql/postgres/agenthub-ai-ddl.sql  # 连跑两次
psql -c "\dt ai_*" | grep -c 'ai_'                             # 期望 ≥ 40，且第二次无报错
```
Expected: 第二次执行仅报 `already exists` 跳过、无 ERROR；表数 40。MySQL 栈同理跑 `agenthub-ai-ddl.sql`。

- [ ] **Step 7: 合并与广播**

```bash
git add code/back-end/ruoyi-modules/agent-hub-ai/src/main/java/org/dromara/ai/contract \
        code/back-end/script/sql code/front-end/src/types/ai-contract.ts e2e/http
git commit -m "feat(contract): 冻结事件协议、原因码、权限标识与双方言 DDL"
```
Expected: PR 合并后队长在站会宣布「契约冻结完成」，此后改动一律走 Task 4 的变更流程。

---

### Task 3: 每日节拍执行（D1–D5 重复）

**Files:**
- Modify: `docs/superpowers/plans/2026-11-20-week1-daily-log.md`

- [ ] **Step 1: 09:30 站会（≤15 分钟，每人只讲三句）**
  当日验收点是否变化、当前阻塞、是否有契约变更申请。队长记录进日志表「契约变更」列。

- [ ] **Step 2: 12:00 三线各自跑本线快检**

```bash
cd code/back-end && ./mvnw -o -q -pl ruoyi-modules/agent-hub-ai -am test
cd code/front-end && pnpm lint && pnpm build
node e2e/http/L00-contract-consistency.mjs
```
Expected: 三者退出码全 0。任一红灯，责任人当天 14:00 前修复，不得带红灯进入联调窗口。

- [ ] **Step 3: 14:00–17:00 联调窗口（一次一个主题）**
  D1 联调「登录 + echo 流」；D2「写工具 HITL」；D3「知识库检索带来源」；D4「定时任务出日报 + MCP 调用」；D5「34 页巡检 + e2e 全量」。窗口外的接口改动不进 main。

- [ ] **Step 4: 18:00 按当日验收点核对并写日志**
  队长执行验收命令、把结果填入日志表「达成 / 未达项」两列，未达项写清责任人。

- [ ] **Step 5: 20:00 前完成降级裁定（仅当有未达项）**
  只能从设计稿 §7.3 表中选择，把「触发条件—降级动作—保底口径—原因」四行写进日志表「§7.3 降级裁定」列。**不允许口头砍需求、不允许无声滑期。**

- [ ] **Step 6: 21:00 三线合并到 main**

```bash
git fetch origin main && git rebase origin/main && git push
```
Expected: 每日收尾时 `main` 处于「可构建、可起栈、可登录」状态。

---

### Task 4: 契约变更流程（冻结后唯一允许的改动路径）

- [ ] **Step 1: 提出方在站会或 PR 描述里提交变更申请，五个字段齐全**
  `影响面（事件/表/接口/权限/impl）`、`为什么必须改`、`三线改动清单`、`兼容性（是否需要数据迁移）`、`回滚方式`。

- [ ] **Step 2: 队长裁定**
  批准 → 同一 PR 内 Java 常量、TS 镜像、契约测试、菜单脚本四处**同步改完**，跑 `node e2e/http/L00-contract-consistency.mjs` 通过后合并；驳回 → 记入日志，提出方按原契约实现或用 §7.3 降级。

- [ ] **Step 3: 同步通知**
  队长在站会宣布变更点，并把结论追加到日志表「契约变更」列。任何未经此流程的契约改动，评审一律打回。

---

### Task 5: 联调依赖矩阵与解耦约定

| 依赖 | 责任人 | 解法（不等对方） |
|---|---|---|
| C 需要全部后端接口 | C | 按 `src/types/ai-contract.ts` 的 Vo 类型在 `src/api/ai/__mock__/{domain}.ts` 造 mock，页面先跑 mock 交付；接口就绪后只改一个 `VITE_USE_MOCK` 开关 |
| B 需要 34 页交互验证 | B | 用 `e2e/http/L{nn}-*.mjs` 直接打接口断言，不依赖页面 |
| 双方都需要 DDL | 队长 | D1 12:00 前冻结，之后 B/C 只在自己的表段追加索引脚本 |
| C 需要权限点显隐 | 队长 + C | 菜单脚本先行，C 用 `ai:retrieval:test` 这类字面量硬编进页面，`L00` 保证与后端常量一致 |
| 三方都依赖 `.env.example` | 队长 | 环境变量新增由队长在 D1 统一列出，中途新增走 Task 4 |

**硬规则**：mock 文件只存在于 `__mock__` 目录且不得进生产构建（`vite build --mode production` 前由 `pnpm lint` 的导入检查拦截）。

---

### Task 6: 冲突高发文件与追加式修改约定

| 文件 | Owner | 他人怎么改 |
|---|---|---|
| `agenthub-ai-ddl.sql`（双方言） | 队长 | 只提 issue 由队长落笔；B/C 不得直接改 |
| `agenthub-ai-menu.sql`（双方言） | 队长 | 按模块分节（`-- ===== ai:knowledge =====`），各人只追加自己那节，节顺序按 §4 表格模块名排序 |
| `Ai*` 常量类 | 队长 | B/C 新增常量走同一 PR 由队长合并 |
| `src/router/index.ts` 与菜单路由 | C | B 不碰前端；新页面路由由 C 一次加一节 |
| `pom.xml` / `package.json` 新增依赖 | 各自 | **新增依赖必须站会报备队长批准**，禁止顺手引入大版本升级 |
| `deploy/*/docker-compose.yml`、`.env.example` | 队长 | C 需改 Nginx 静态目录时提 issue |
| `e2e/http/L{nn}-*.mjs` | 按层号归属 | 一层一文件一人负责，天然不冲突 |

---

### Task 7: 逐日交付任务表（复选框即验收清单）

每人每条任务后面的命令即验收方式；跑不通不算完成。

#### D1 基线与契约

- [ ] **队长** 云服务器与中间件起栈、HTTPS 与域名、四类契约冻结（Task 1–2）、DDL 双方言幂等
  `docker compose -f deploy/deploy-pg/docker-compose.yml up -d --build`（MySQL 栈同）
- [ ] **后端面** 底座裁剪、模型配置（密文/连通性/整行复制/引用拦截）、会话与消息落库、SSE echo 主链路
  `./mvnw -o -pl ruoyi-modules/agent-hub-ai test`
- [ ] **前端面** 前端骨架（登录/路由/权限显隐/模板化表格与表单引擎）、`stream.ts` SSE 客户端、工作台消息流
  `pnpm build && node e2e/http/L00-contract-consistency.mjs`
- [ ] **当日验收**：双栈可起可登录、40 表双方言幂等、模型连通性通过、echo 流打通、契约测试绿

#### D2 权限 HITL + 编排 + 工作台交互

- [ ] **队长** 三层权限与 HITL 暂停/恢复、信任槽位、写工具去重；安全（敏感词/PII/注入/六场景与降级）
  `./mvnw -o -pl ruoyi-modules/agent-hub-ai test -Dtest='*Permission*,*HITL*,*Safety*'`
- [ ] **后端面** 工具两段式与内置工具集、工具标签、小Z `route_decision`、专家装配缓存与指纹、暂存件 72 小时回收任务与配额
  `./mvnw -o -pl ruoyi-modules/agent-hub-ai test -Dtest='*Tool*,*Route*,*Assembly*,*Attachment*'`
- [ ] **前端面** 工作台全部卡片（工具/专家/路由/确认/来源/进度）、资源选择器与附件上传、模型配置页、专家配置页（AI 辅助生成/9 模板/克隆/体检）
  `pnpm exec playwright test e2e/ui/workbench.spec.ts`
- [ ] **当日验收**：写工具走完「需确认→允许→落库」且拒绝路径无副作用；一句话路由到专家直答；输入侧敏感词阻断并留痕

#### D3 RAG + 记忆/技能/工作区 + 上下文工程

- [ ] **队长** RAG 全链（解析/切片/向量化/BM25/RRF/Rerank/装配/检索测试）+ 评测脚本与基线报告
  `node e2e/rag/run.mjs --report e2e/rag/report-$(date +%Y%m%d).md`
- [ ] **后端面** 长期记忆（§12.2 抽取/净化/水位/合并/版本恢复）、工作区、技能仓库、压缩与卸载、编排预算、用量配额、对话偏好
  `./mvnw -o -pl ruoyi-modules/agent-hub-ai test -Dtest='*Memory*,*Skill*,*Compaction*,*Usage*'`
- [ ] **前端面** 知识库三页（库/文档切片/检索测试）、工具注册页、记忆三页签、工作区、技能页、模型运行时四页
  `pnpm exec playwright test e2e/ui/knowledge.spec.ts e2e/ui/memory.spec.ts`
- [ ] **当日验收**：文档入库→检索命中→回答带来源卡片；评测报告产出；记忆跨会话生效；长会话触发压缩不爆 Token

#### D4 MCP + 渠道 + 定时任务与日报

- [ ] **队长** MCP 出口（口令 fail-closed、只读暴露热生效、自演示）与客户端（白名单/归一化/回参逐块脱敏）、子智能体与场景包后端、无人值守白名单
  `./mvnw -o -pl ruoyi-modules/agent-hub-ai test -Dtest='*Mcp*,*Subagent*,*Scenario*'`
- [ ] **后端面** 四渠道（飞书/企微/GitHub Webhook + 钉钉/飞书长连接、身份与会话映射、媒体降级、回调幂等）、定时任务与日报生成、计划模式与任务清单
  `./mvnw -o -pl ruoyi-modules/agent-hub-ai test -Dtest='*Channel*,*Task*,*Digest*'`
- [ ] **前端面** MCP 两页、渠道三页签、场景包/子智能体页、定时任务页与执行记录弹窗、日报页、首登检查清单
  `pnpm exec playwright test e2e/ui/channel.spec.ts e2e/ui/task.spec.ts`
- [ ] **当日验收**：MCP `initialize`/`tools/list`/`tools/call` 通过且错误口令被拒；飞书回调端到端一条消息进得来出得去；定时任务产出日报；无人值守写默认拒绝

#### D5 审计 + 测试 + 发布

- [ ] **队长** 审计六类落库与保留清理（§12.4）、链路追踪、健康自检（§12.3）、生产密钥门禁；双栈上线 + Nginx + 介绍页发布；发版五道门禁执行
  `node e2e/http/L00-contract-consistency.mjs && ./mvnw -o -pl ruoyi-admin -am test`
- [ ] **后端面** 审计接口与统计、编排运行统计卡、Reaper 中断回收、单测补齐与 4 类集成测试、e2e 全量回归配合
  `AGENTHUB_IT=true ./mvnw -o -pl ruoyi-admin -am test`
- [ ] **前端面** 审计六类页面（消息/工具/权限/追踪/编排运行/日报）、保留管理页、健康自检页、Sites 介绍页与云端 Agent 体验、34 页浏览器巡检
  `pnpm exec playwright test e2e/ui --reporter=line`
- [ ] **当日验收**：设计稿 §10.2 九条全绿（含 34 页无 console.error、e2e 零 FAIL、ERROR 计数 0、健康自检 0 FAIL、演示故事端到端）

---

### Task 8: 质量门禁分工（谁跑什么、什么时候）

| 门禁 | 命令 | 责任人 | 频率 |
|---|---|---|---|
| AI 模块单测 | `cd code/back-end && ./mvnw -o -pl ruoyi-modules/agent-hub-ai test` | B（队长线代码由队长） | 每次 PR 必跑，合并前贴输出 |
| 双栈集成测试 | `AGENTHUB_IT=true ./mvnw -o -pl ruoyi-admin -am test` | B | 每日 12:00 |
| 契约一致性 | `node e2e/http/L00-contract-consistency.mjs` | C | 每日 12:00 + 任何契约 PR |
| 接口 e2e 分层 | `node e2e/http/L01-*.mjs … L21-*.mjs` | 队长出清单，B/C 按层认领 | D5 全量，其余每日主链路 |
| 浏览器巡检 | `pnpm exec playwright test e2e/ui --reporter=line` | C | D3 起每日 |
| RAG 评测 | `node e2e/rag/run.mjs --report e2e/rag/report-$(date +%Y%m%d).md` | 队长 | D3、D5 |
| 前端构建 | `pnpm lint && pnpm build` | C | 每次 PR |
| 日志与体检 | 末 500 行 ERROR 计数 0；`GET /ai/health/check` 0 FAIL | 队长 | D5 发布前 |

---

### Task 9: D5 发布与回滚值班

**发布顺序（队长主导，B/C 就位）**

- [ ] 1. 队长冻结代码：`main` 打 tag `v1.0.0`，此后只收 fix。
- [ ] 2. 队长发布 PG 栈：`docker compose -f deploy/deploy-pg/docker-compose.yml up -d --build`，等 `GET /ai/health/check` 0 FAIL。
- [ ] 3. 队长发布 MySQL 栈（`:8081`），重复健康自检。
- [ ] 4. C 发布前端产物到 Nginx 静态目录（与后端同版本），验证同源 API 无 CORS、SSE 不断流。
- [ ] 5. C 发布 Sites 介绍页，验证「进入系统」跳转可登录。
- [ ] 6. 队长跑发版五道门禁并记录结果到日志（D5 行）。
- [ ] 7. 全员演示彩排一遍（小Z 路由 → 专家带来源 → 写工具确认 → 落库 → 审计可查；允许与拒绝两路径）。
- [ ] 8. 队长开启演示账号 + IP 白名单，确认无鉴权写链路不暴露公网。

**回滚触发与步骤**

触发（任一即回滚，队长裁定）：健康自检出现 FAIL、登录或 SSE 主链路不可用、写工具绕过确认、双方言任一栈起不来。

- [ ] 1. Nginx 切回上一版静态产物目录（C）。
- [ ] 2. 容器镜像回退到上一 tag：`docker compose up -d --force-recreate app`（队长）。
- [ ] 3. 数据库不改结构（DDL 幂等且只追加），若本次含迁移脚本则先执行回滚脚本再启动旧镜像（B）；回滚原因与影响写进日志。

---

### Task 10: 阻塞上报与接管顺序

- [ ] **Step 1: 30 分钟规则** — 任一阻塞超过 30 分钟未解，立刻在站会外的即时通道上报，不等 18:00。
- [ ] **Step 2: 接管矩阵** — 队长阻塞由 B 接管部署与契约裁定；B 阻塞由队长接管编排与渠道、C 的前端任务不受影响；C 阻塞由队长接管介绍页、B 用 e2e 自证接口。
- [ ] **Step 3: 单人请假半天** — 当日验收点不降标准，改由接管人交付；连续两天缺人必须走 §7.3 降级并在日志记录。

---

## 自审结论（写完后按 spec 复核）

- **Spec 覆盖**：§7.1 全量清单 → Task 7 的 D1–D5 逐条对应；§10.2 九条验收 → Task 7 D5 + Task 9；§12 六条细则 → Task 7 的 D2（会话治理/附件回收）、D3（记忆）、D5（健康自检/审计保留）；§11 前置条件 → Task 1 Step 2 与 Task 7 D1。无遗漏。
- **占位符扫描**：无「待定/后续补充」；所有命令、文件名、常量值与验收数字均已给全。
- **类型一致性**：事件 13 型、原因码 11 个、权限标识 12 个在 Java、TS、契约测试三处字面一致（由 `L00` 机器校验）；表数 40、端口 8080/8081、RAG 四项基线与 spec 完全一致。
- **待补**：设计稿 §12.7 C 段的 I-1~I-11 共 11 项实现欠账（健康自检 40 表与缓存、审计保留下限与分批、会话并发保护与留痕、附件状态列与配额、记忆版本权限）逐条开 `feature/<scope>` 分支交付；I-12 底座示例菜单裁剪属产品面取舍，待队长批准；本计划只覆盖节拍与验收。
