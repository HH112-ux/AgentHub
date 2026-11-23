# AgentHub 介绍页 · Sites 发布任务清单

> **状态**：站点已公开发布并完成线上验收（`access_mode=public`，无需登录）：两次真实问答均 200 逐帧流式、第二轮带历史接续正常、「进入系统」在新标签打开 `https://47.238.161.7`；`PINNED_MODEL_ID` 已按线上日志钉成 `auto`，云端重复资源冲突自愈补丁随 `6dbe532` 同批上线。分支 `feature/intro-qca-chat` 已推 origin。**剩余**：界面截图素材（Task 4b）与自签名证书警告提示（Task 4a 遗留）。
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
| 站点地址 | `agenthub-qq5kdpsfufg.qoder.zone`（访问模式 `public`，2026-11-23 队长授权后改） |
| Project ID | `01a206be-26b5-7ccc-b43e-21d01846ab16` |
| Site ID | `01a206be-26b9-797d-ac35-8e6c4aaefc8b` |
| bootstrap 草稿 | 已被完整草稿取代（action `4aa84a6a-c030-4593-8e2b-8ba625c00fc6`） |
| 已发布草稿 | 当前线上为 action `a2c7a006-6f38-4ebc-a566-9174519f1b5e`（含模型钉住 + 冲突自愈）；首个公开前草稿 action `2e58db55-fdca-4dc1-97fb-fa70e42a929f` |

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
>   会抛 `ai_initialization_conflict`。2026-11-23 已按队长「改成公开」的决定补上自愈（见 Task 3e），
>   冲突时改为挑确定 id 复用，问答不再永久失败；**残余风险**：重复资源仍会留在云端（需人工归档），
>   且自愈挑到的可能是旧定义的那一份；要彻底消除仍需站点数据库做跨实例锁。
> - 否掉「浏览器保管云端 Session ID」的方案：那会让访客能提交他人 Session 读取其内容。
> - ~~首次上线后必须把 `PINNED_MODEL_ID` 填成实际启用的模型 id~~ 已钉 `auto`（见 Task 3c-1）。留空时
>   每次冷启动按模型列表排序取首个，模型目录变动会让 Agent 定义指纹漂移并在云端留下孤儿资源。

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
- [x] 3c. 线上实测完成（改公开后，action `a2c7a006-6f38-4ebc-a566-9174519f1b5e`，publish operation `01a2084e-7275-7df0-b7c0-d66f0252bf4e` succeeded/committed）：
  - 未认证访问首页 `200`、标题 `AgentHub · 企业级 AI 业务运行平台`；`/functions/v1/app/api/chat` 两次真实提问 `200`、`content-type: text/event-stream`，逐帧 `delta` + `done` 终态（4.4 s / 5.3 s），Function 请求日志两条均 200。
  - 真浏览器实测：输入提问后气泡逐段增长、发送键在回答期间禁用、结束后恢复；第二轮带上一轮历史，回答正确接续且守住「只介绍公开产品信息」边界。
  - 「进入系统」按钮实测 `window.open('https://47.238.161.7', '_blank', 'noopener')`（为不把预览页跳走，用临时桩捕获），当前页不跳转。
  - 踩坑记一笔：Qoder shell 的 curl 走注入的 `HTTPS_PROXY=127.0.0.1:59487`，不带 `Origin` 会被站点网关判 `sites_gateway_origin_forbidden`（403）；加 `Origin: https://<站点域名>` 并 `env -u HTTPS_PROXY` 直连才通。
- [x] 3c-1. `PINNED_MODEL_ID` 已钉 `auto`（取自线上 `chat_models_discovered`：目录无 `enabled` 标记故走全量排序回退，首项 `auto` 即实际建成 Agent 所用模型）。复验：钉住之后线上再问两次，日志里 `chat_models_discovered` 不再新增，说明确实跳过了模型发现。
- [x] 3d. Desktop 交接：`show_publish_confirmation` 已对工作台打开（两个 action 先后各开一次）。
- [x] 3e. 冲突自愈补丁（公开访问的前置条件）：`ensureBinding` 捕获 `ai_initialization_conflict` 后按 `qsites_scope`+`qsites_kind` 列出本站资源、取排序后第一个确定 id 复用，把「云端留下重复资源 → 问答永久失败」降级为继续可用；多余资源仍需人工到云端归档。配 2 个单测（自愈成功、无可自愈绑定时保留原失败），共 16 项全过。

## Task 4: 队长素材接入（分支 `fix/intro-assets`，素材到位后做）

- [x] 4a. `SYSTEM_URL` 已填 `https://47.238.161.7`（2026-11-23 实测：`http` 308 跳 `https`，`https` 返回 200 且标题为 `AgentHub`，证书为当日签发的自签名 `CN=agenthub`）。为避免两条线互相覆盖，该改动随 Task 2 分支一起提交，不再另开 `fix/intro-assets`。**遗留**：自签名证书会让访客第一次打开撞浏览器全屏安全警告，是否加页面提示待队长定；换域名挂正式证书后需同步更新此常量。
- [ ] 4b. 三张截图替换占位（对话工作台 / 专家管理 / 健康自检）
- [ ] 4c. 「开发文档」链接确认（现指向 GitHub 仓库，如队长另有文档站则替换）

## 队长待办（阻塞项）

| 项 | 归属 | 影响 |
|---|---|---|
| ~~云服务器系统 URL~~ | 队长 | 已到位（Task 4a 已填） |
| ~~站点访问范围~~ | 队长 | 已定并生效：2026-11-23 授权改成 `public`（`confirmPublic=true`，`auth_integration_status: not_required`），冲突自愈补丁同批上线 |
| 界面截图素材 | 队长 | Task 4b 占位替换 |
| 站点域名确认 | 队长 | 站点地址是否保持 `.qoder.zone`；同时决定 `SYSTEM_URL` 是否换域名、访客是否继续撞自签名证书警告 |
| 本机是否安装 Deno | 用户 | Task 2e 的 `deno check` 入口类型校验，当前未执行；线上 Function 已连续返回 200，实际加载未报错 |
