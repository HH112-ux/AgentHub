# AgentHub 介绍页 · Sites 发布任务清单

> **状态**：后端能力已开启（`01a20845`，QODER_PAT 已由平台签发）、完整草稿已发布（`published: true`，Function `app` 已 active）；页面骨架 + 云端问答 + 「进入系统」跳转均在 `feature/intro-qca-chat`（已推 origin，含 `origin/main` 合并）。**剩余**：线上问答实测被站点访问模式挡住（私有 = 登录墙，见 Task 3c），以及上线后必须钉 `PINNED_MODEL_ID`。
>
> **For agentic workers:** 步骤用 `- [ ]` 复选框跟踪；每个代码任务完成后按 AGENTS.md 做代码审查、推送分支，合入走 PR + 非作者评审。

**Goal:** 把 AgentHub 产品介绍页发布到 Qoder Sites（介绍项目 + 「进入系统」跳转链接到云服务器真实系统），并按需接入云端 Agent 在线体验问答。

**Spec:** [设计稿 §5（D5 部署形态）](../specs/2026-11-20-agenthub-decomposition-design.md)；开发规范见 [AGENTS.md](../../../AGENTS.md)。

## 约束（逐字取自 AGENTS.md / 设计稿）

- Qoder Sites 仅承载产品介绍页、跳转链接及公开内容的在线体验与问答，不承载真实系统页面，不保存密钥。
- 云端 Agent 不得接入业务知识库、业务工具、会话数据或 PII。
- 密钥只在服务端：`QODER_PAT` 平台托管，仅 Function 内 `Deno.env.get('QODER_PAT')` 读取，不进前端、不进仓库、不进聊天。
- 介绍页公开内容可以线上体验；真实系统必须登录，跳转链接由队长提供云服务器地址后填入。
- Git：一个分支只承载一个独立任务；提交用约定式 `<type>: <中文简述>`；不直推 `main`。
- 站点地址、Project/Site ID 均为站点工具返回的固定值，不得手写 `.qoder.site` 描述符。

## 已定基线

| 项 | 值 |
|---|---|
| 分支 | `feature/intro-site`（骨架 `b970689` 已推送） |
| Task 2 分支 | `feature/intro-qca-chat`（从 `origin/feature/intro-site` 切出，已推 origin 并合并 `origin/main`） |
| 站点地址 | `agenthub-qq5kdpsfufg.qoder.zone`（访问模式 `private`） |
| Project ID | `01a206be-26b5-7ccc-b43e-21d01846ab16` |
| Site ID | `01a206be-26b9-797d-ac35-8e6c4aaefc8b` |
| bootstrap 草稿 | 已被完整草稿取代（action `4aa84a6a-c030-4593-8e2b-8ba625c00fc6`） |
| 已发布草稿 | action `2e58db55-fdca-4dc1-97fb-fa70e42a929f`（`published: true`，2026-11-23） |

## 分支约定

`feature/intro-site` 作为本次交付的集成分支；后续每个小任务从它切独立分支开发，PR 合回它；全部验收后整体 PR 合入 `main`（非作者评审）。新开分支一律从 `origin/feature/intro-site` 切（含骨架），不要从 `main` 切。

---

## Task 1: 站点能力决策（无代码，不建分支）

- [x] 确认开启 Qoder 后端（用户 2026-11-23 明确确认，含平台侧自动创建 QODER PAT）
- [x] 确认问答范围：**每次访问新会话，不引入站点数据库**；刷新页面即丢弃对话

## Task 2: 云端 Agent 问答接入（分支 `feature/intro-qca-chat`）

前置：Task 1 选择「开启后端」。

> **契约偏差（改动 `functions/chat.mjs` 前先读）**
>
> 原计划让 Function 用官方 `createChatHandler` 契约，实测该契约不可用于本任务：它的 `create` 承诺
> 「刷新不得新建云端 Session」、`send` 承诺「先落投递意图记录」，两者都必须有持久层，与 Task 1 的
> 「无数据库」决定直接冲突。实际落地改为：
>
> - 不使用 `createChatHandler`；`functions/chat.mjs` 自建薄层，一次 HTTP 请求对应一个一次性云端
>   Session，上文由浏览器随请求携带（上限 8 条），服务端零存储。
> - `ensureCloudResources` 要求的 `coordinator` 本应是跨实例持久锁，这里降级为进程内锁。后果：
>   Agent/Environment **首次创建**被多实例并发命中时可能建出重复资源，之后 `findResources` 扫到多于一份
>   会抛 `ai_initialization_conflict`，问答持续失败直到人工到 Qoder 云端归档多余资源。当前站点
>   `access_mode=private`、可访问者只有项目组三人，接受此风险；**站点改公开访问前必须先补站点数据库**。
> - 否掉「浏览器保管云端 Session ID」的方案：那会让访客能提交他人 Session 读取其内容。
> - 首次上线后必须把 `chat.mjs` 的 `PINNED_MODEL_ID` 填成实际启用的模型 id（取自 Function 日志的
>   `chat_models_discovered`）。留空时每次冷启动按模型列表排序取首个，模型目录变动会让 Agent 定义
>   指纹漂移并在云端留下孤儿资源。

- [x] 2a. `ensure_backend('qoder')` 启用站点后端能力（2026-11-23，operation `01a20845-bf70-7382-bed0-ccd90e0af6b3`：`provision_backend`/`issue_qoder_pat`/`sync_secret` 三步均成功；`get_site(include:["backend"])` 回 `status: ready`、capabilities `functions+qoder+storage`。平台 Secret 不出现在 `secrets` 列表属预期，该列表看不到平台托管的 `QODER_PAT`）
- [x] 2b. Function 骨架：`functions/index.ts` 入口 + `functions/chat.mjs` SSE 业务层（偏差见上，未用 `createChatHandler`）
- [x] 2c. 云端 Agent 初始化集成：官方 `cloud-agents.mjs` + `ensureCloudResources`（QODER_PAT 仅 `Deno.env.get` 服务端读取，不进前端/仓库/日志）
- [x] 2d. 前端接线：`site.js` 改 ES 模块，问答区接 `/functions/v1/app/api/chat` SSE 流式渲染，替换「接入中」占位；`sse-client.js` 为官方分帧助手副本
- [x] 2e. 本地验证 → 代码审查 → 推送分支（提交 `25052d8`，已推 origin 并跟踪 `origin/feature/intro-qca-chat`；合并 `origin/main`（deploy/compose 线）无冲突；推送前重跑单测 14/14；diff 内 grep 无密钥值，仅服务端 `Deno.env.get('QODER_PAT')` 读取点）
  - [x] `node --test tests/chat.test.mjs`：14 项全过（入参校验、密钥缺失不触碰云端、逐帧投影、上游失败不透传原文、并发只建一份资源、寿命超时）
  - [x] `dev/local-server.mjs` 无凭据假云端预览：真浏览器实测两轮问答、历史接续、限流分支、半句中断分支、长回答压缩
  - [ ] `deno check functions/index.ts`：本机未安装 Deno，尚未执行（见清单末尾待决项）

## Task 3: 完整草稿与发布（无代码改动，站点工具操作）

- [x] 3a. 用同一 projectId 准备完整草稿（action `2e58db55-fdca-4dc1-97fb-fa70e42a929f`，`projectRoot D:\AgentHub\code\intro-site` + `webDirectory dist` + `functionDirectory functions` + `databaseAccess none`，**未在 `secretNames` 声明 `QODER_PAT`**（平台托管）；首次返回 `descriptorStatus: pending`，同 action 同参数重试后 `written`）
- [x] 3b. 读草稿状态：`canPublish: true`、`blockers: []` 后 `publish_site`（publish operation `01a20846-d94c-77b6-b1ea-f4701c25d724` → `succeeded`/`committed: true`）
- [ ] 3c. 验证 `published: true`（已确认，`readiness: publication_completed`、Function `app` `is_active: true`、`database_access: none`），**浏览器实测未完成**：`access_mode=private` 使站点在登录墙之后——未认证访问首页 `401`、`POST /functions/v1/app/api/chat` `403`，内置浏览器落在「登录后即可访问」。真实问答调用与 `chat_models_discovered` 日志需要先解决访问方式（见队长按下表）。
- [ ] 3c-1. 首次线上问答成功后，把 `chat.mjs` 的 `PINNED_MODEL_ID` 填成日志里的模型 id，再走一次 prepare + publish（不改这条 = 每次冷启动按列表排序取首个，模型目录变动会让 Agent 指纹漂移并在云端留孤儿资源）。
- [ ] 3d. Desktop 交接：`show_publish_confirmation` 打开工作台，交付最终地址

## Task 4: 队长素材接入（分支 `fix/intro-assets`，素材到位后做）

- [x] 4a. `SYSTEM_URL` 已填 `https://47.238.161.7`（2026-11-23 实测：`http` 308 跳 `https`，`https` 返回 200 且标题为 `AgentHub`，证书为当日签发的自签名 `CN=agenthub`）。为避免两条线互相覆盖，该改动随 Task 2 分支一起提交，不再另开 `fix/intro-assets`。**遗留**：自签名证书会让访客第一次打开撞浏览器全屏安全警告，是否加页面提示待队长定；换域名挂正式证书后需同步更新此常量。
- [ ] 4b. 三张截图替换占位（对话工作台 / 专家管理 / 健康自检）
- [ ] 4c. 「开发文档」链接确认（现指向 GitHub 仓库，如队长另有文档站则替换）

## 队长待办（阻塞项）

| 项 | 归属 | 影响 |
|---|---|---|
| ~~云服务器系统 URL~~ | 队长 | 已到位（Task 4a 已填） |
| **站点访问范围** | 队长 | 现 `private` = 登录墙，与「介绍给别人看」冲突。选 `public` 需队长明确授权；改公开前建议先补「云端资源冲突自愈」补丁（见 Task 2 偏差第 3 条），否则并发首问可能永久打挂问答 |
| 界面截图素材 | 队长 | Task 4b 占位替换 |
| 站点域名确认 | 队长 | 站点地址是否保持 `.qoder.zone`；同时决定 `SYSTEM_URL` 是否换域名、访客是否继续撞自签名证书警告 |
| 本机是否安装 Deno | 用户 | Task 2e 的 `deno check` 入口类型校验，当前未执行；线上 Function 已 `is_active`，实际加载未报错 |
