# AgentHub 项目需求与开发计划

> 版本 v2.1 · 2026-11-20 · 团队执行版（一周全量，§12 六条补充细则已由队长裁决）
> 规范来源：本仓库《开发总览手册.md》+ 项目决策记录（§1）。
> **一周目标 = 全量目标**：34 个功能页、98 按钮权限、40 张 `ai_` 表、全部能力域，均在本周内交付并上线。

---

## 1. 项目定位与目标

在开源中后台底座（RBAC、工作流、调度、文件存储、消息推送）之上，以 AgentScope Java 2.0.4 为智能体内核，构建**可运营、可审计、可控、可评测**的企业级 AI 业务运行平台：用户用自然语言提问，入口智能体「小Z」判断意图并路由到领域专家，专家在受控权限内调用工具与知识库完成作业，写操作经人工确认后落地。

- **一周目标（=全量目标）**：手册全部功能交付——6 大能力域、34 个功能页、98 按钮权限、40 张表双方言、编排与 HITL、RAG 混合检索与评测、长期记忆、技能仓库、工作区、子智能体、场景包、MCP 双向、四渠道、定时任务与日报、六场景安全过审、六类审计、四层测试与发版门禁。介绍页上线 Qoder Sites，真实系统（含前端）在云服务器可访问。
- **一周后**：按 §9 做硬化、补测与二期演进，不再有"一期未做的手册功能"。

### 决策记录

| # | 决策 | 内容 |
|---|---|---|
| D1 | 项目性质 | AgentHub 全新自建，手册为需求规格，独立 git 仓库（基线 `c81386c`） |
| D2 | 技术栈 | JDK 21 + Spring Boot 4.1.1 + AgentScope Java 2.0.4 + 中后台底座 6.0.0 + Vue 3.5 / TypeScript / Element Plus / Vite / pnpm（Node ≥ 20.19） |
| D3 | 系统前端部署 | **真实系统前端静态产物部署在云服务器，与 API 同源**（Nginx 伺服）。同源消除 CORS 与跨站鉴权复杂度，前后端同版本发布、不会错配 |
| D4 | 服务端部署 | **云服务器**：应用、PostgreSQL 14 / MySQL 8.0、Redis、Milvus + etcd、对象存储、调度、MCP 出口、渠道长连接。整套系统可私有化部署，不依赖 Sites 运行 |
| D5 | Qoder Sites 定位 | **仅承载产品介绍页与跳转链接**（「进入系统」「开发文档」），以及基于云端 Agent 的在线体验与平台使用问答；不承载真实系统页面，不接触业务数据 |
| D6 | 排期 | **一周硬性，且一周目标为全量目标**；不做范围削减式排期，只做 §7.3 的客观降级触发 |
| D7 | 团队 | 用户为队长。原定 3 人分工（队长 + 后端 B + 前端 C）已于 2026-11-20 取消，改为**队长单线全量交付**，范围与验收标准不变，裁决记录见 §8.1 |
| D8 | 拆解毒点 | 契约先行 + 垂直切片；手册 13.1 的 M1~M5 仅作对外排期参考 |
| D9 | 智能体内核与云端 Agent 边界 | **业务主链路内核为 AgentScope Java 2.0.4，跑在云服务器**，不外包给托管 Agent：三档授权与 HITL 需要工具调用前的同步决策钩子、业务工具需在服务端执行并复核权限、审计与编排预算需内核级可观测、私有化与数据边界不允许业务数据出域。**云端 Agent 仅用于 Sites 介绍页的在线体验与平台使用问答**，不接触知识库正文、工具回参与任何业务数据 |
| D10 | 文档隔离 | 全部产出物不引用其他仓库的路径、类名与测试产物 |
| D11 | 契约重冻 | 全量代码入仓后按**路线 A**重冻：§6/§12 与实现逐条对账，实现正确的改本文件（效力修正见 §12.7 B 段），契约正确的列为增量项（§12.7 C 段）。数据面唯一变更为状态字典按实体常量重登记，并由 `e2e/http/L00-contract-consistency.mjs` E 段做机械对账 |

---

## 2. 部署形态

### 2.1 各层位置

| 层 | 部署位置 | 内容 |
|---|---|---|
| 产品介绍页 | **Qoder Sites** | 平台介绍、能力清单、架构与界面截图、「进入系统」「开发文档」跳转链接、在线体验与使用问答（云端 Agent） |
| **系统前端** | **云服务器 Nginx** | Vue 3 构建产物：工作台 + 34 页管理台，**与 API 同源伺服** |
| 服务端 | 云服务器 | Spring Boot 单进程单 jar（含 AI 能力模块） |
| 数据与中间件 | 云服务器 | PostgreSQL 14（默认）/ MySQL 8.0、Redis ≥6、Milvus + etcd、MinIO 或云 OSS |
| 常驻能力 | 云服务器 | 应用内 60 秒调度、Reaper 中断回收、MCP 出口 `/mcp`、飞书/钉钉长连接外连 |
| 本地开发 | 开发机 | Vite dev server + proxy 指向云服务器 API |

### 2.2 拓扑

```
浏览器 ──HTTPS──▶ Qoder Sites（产品介绍页 + 在线体验问答 + 跳转链接）
                     │ 「进入系统」跳转（仅链接，不发起业务 API 调用）
                     ▼
              云服务器（公网 IP + 域名 + 证书）
                Nginx：同源伺服前端静态产物 + /prod-api 反代 + SSE 长连接配置
                  ├─ 应用服务（Spring Boot，内含 AgentScope 内核）
                  ├─ PG 栈 :8080 ｜ MySQL 栈 :8081（双方言，可并存）
                  ├─ Redis（会话状态 / HITL 待确认 / 锁 / 限流 / 运行期配置）
                  ├─ Milvus + etcd（向量集合 + BM25 集合）
                  └─ 对象存储（附件 / 文档原文 / 大结果落盘）
```

### 2.3 部署与安全约束（违反即演示失败）

1. **云服务器必须 HTTPS**：证书是 D1 硬前置。
2. **前后端同源**：无需 CORS 白名单；若将来拆分前端到独立域名，再启用白名单且禁止 `*`。
3. **SSE 鉴权**：同源下用会话 cookie；若改用查询参数建流，只能放一次性短时效票据，**禁止把长期 token 放 URL**。
4. **渠道回调走云服务器**：飞书/企微/GitHub Webhook 需公网可达 + 验签/解密 + 渠道口令；长连接渠道主动外连，免公网回调。
5. **MCP 出口在云服务器**：`/mcp`，Bearer 或专用口令，fail-closed，留空拒所有请求。
6. **密钥只在服务端**：模型密钥、渠道凭据、MCP 请求头密文落库；Sites 侧不放任何密钥。
7. **云端 Agent 数据边界**：仅接触介绍页公开内容与平台使用文档，**不得**接入业务知识库、业务工具、会话数据或任何 PII。
8. **跳转链接**：介绍页 → 工作台（云服务器）、开发文档；管理页深链需鉴权回跳。
9. **公网暴露收敛**：介绍页公开；系统必须登录，演示期采用演示账号 + IP 白名单，写工具确认链路不得无鉴权暴露公网。

---

## 3. 工程结构

```
AgentHub
├── code/back-end            # Spring Boot 多模块 Maven
│   ├── ruoyi-admin          # Web 服务入口
│   ├── ruoyi-common         # 通用能力
│   ├── ruoyi-api            # 跨模块契约（Service 接口 + DTO）
│   ├── ruoyi-modules
│   │   ├── ruoyi-system     # RBAC、字典、日志、OSS、消息（底座）
│   │   ├── ruoyi-workflow   # 工作流（底座）
│   │   └── agent-hub-ai     # AI 能力主体（包 org.dromara.ai）
│   └── script/sql           # 双方言建库脚本，幂等（命名见 §3.2）
├── code/front-end           # Vue 3 + TS + Element Plus（云服务器 Nginx 同源伺服）
├── code/intro-site          # Qoder Sites 产品介绍页工程（含云端 Agent 体验与问答）
├── deploy/deploy-pg         # PG 栈 Compose（云服务器）
├── deploy/deploy-mysql      # MySQL 栈 Compose（云服务器）
├── e2e/http                 # 接口 e2e（Node.js 纯 ESM 零依赖，对真实栈断言）
├── e2e/ui                   # 浏览器 e2e（Playwright，34 页巡检）
└── docs                     # 本文与架构/开发/配置文档
```

### 3.1 命名基线（强制）

手册正文与附录沿用了外部项目的模块名、环境变量前缀与缓存键前缀，**AgentHub 一律改写为自有命名**，产出物不得出现外部名称：

| 面 | AgentHub 命名 |
|---|---|
| Maven 模块 | `agent-hub-ai`（包 `org.dromara.ai`） |
| 环境变量前缀 | `AGENTHUB_*`（密钥加密根 `AGENTHUB_AI_CRYPTO_KEY`、守卫降级 `AGENTHUB_SECURITY_GUARD_MODE`、集成测试开关 `AGENTHUB_IT`、时区 `AGENTHUB_AI_CHAT_CURRENT_TIME_ZONE`） |
| Redis 键前缀 | `agenthub:ai:`（AgentScope 状态 `agenthub:ai:agentscope`、HITL 待确认 `agenthub:ai:hitl`、锁 `agenthub:ai:lock`、运行期配置 `agenthub:ai:config`） |
| 代码侧包装 | 工厂 `AiToolFactory`、MCP 回参脱敏包装 `GuardingMcpClientWrapper`、状态存储 `RedisAgentStateStore`、生产守卫 `ProductionConfigGuard`、回收 `Reaper` |

### 3.2 文件与目录命名规范（强制）

**总则**：除面向业务读者的基线文档（《开发总览手册.md》，保持原名不动）外，全部新建文件与目录使用 **ASCII 小写 + kebab-case**；Java 类型 PascalCase、方法/变量 camelCase、常量 SCREAMING_SNAKE；一个文件只有一个职责，文件名即其职责名词。**任何文件名不得含外部项目名或产品代号**，AI 主体统一 `agent-hub-ai` / `agenthub-ai`。

| 面 | 规范 | 示例 |
|---|---|---|
| SQL 建库（AI 模块） | `script/sql/{dialect}/agenthub-ai-ddl.sql`，双方言同路径同文件名 | `script/sql/postgres/agenthub-ai-ddl.sql`、`script/sql/mysql/agenthub-ai-ddl.sql` |
| SQL 底座基线 | `script/sql/base/{dialect}/ruoyi-{vue,job,workflow}-base.sql`，属底座 MIT 分发件（出处见 [开源许可边界](../../open-source-licenses.md)），不受本表小写 kebab 约束，也不参与双方言对账；initdb 序号 `10`/`11`/`12` 固定先于 `20`~`40` | `script/sql/base/postgres/ruoyi-vue-base.sql` |
| SQL 种子与菜单 | `agenthub-ai-seed.sql`（字典/内置工具/示例库）、`agenthub-ai-menu.sql`（菜单与 98 按钮权限），按权限标识分组顺序追加 | `script/sql/postgres/agenthub-ai-menu.sql` |
| SQL 迁移 | `agenthub-ai-migrate-v{n}-{kebab-scope}.sql`，1.0.0 基线不产出迁移 | `agenthub-ai-migrate-v2-hitl-index.sql` |
| Java 包 | `org.dromara.ai.{domain}`，`{domain}` 与权限标识的 `{模块}` 段一一对应 | `org.dromara.ai.knowledge`、`org.dromara.ai.channel` |
| 契约常量 | `org.dromara.ai.contract.Ai{面}`，一类一面，取值即线上值，改动走 §6.5 契约变更流程 | `AiEventType`、`AiPermissionKeys`、`AiToolImpl`、`AiDegradeReason` |
| Java 类型后缀 | `Controller` / `Service` / `ServiceImpl` / `Mapper` / `Bo` / `Vo` / `Entity` / `Factory` / `Wrapper` / `Guard` / `Handler` | `KnowledgeBaseController`、`AiToolFactory` |
| 后端测试 | 单测 `{Class}Test.java`（`mvn test` 可跑）；集成 `{Feature}IT.java`（`AGENTHUB_IT=true`）；同包镜像目录 | `RouteDecisionServiceTest.java`、`HybridSearchIT.java` |
| 前端页面 | `src/views/ai/{domain}/{page}/index.vue`，`{domain}` 与 `{page}` 与 §4 的 34 页清单一一映射 | `src/views/ai/knowledge/search-test/index.vue` |
| 前端 API 与类型 | `src/api/ai/{domain}.ts` + 同名 `{domain}.types.ts`；流式客户端固定 `src/api/ai/stream.ts` | `src/api/ai/mcp.ts`、`src/api/ai/stream.ts` |
| 前端组件与状态 | 组件 PascalCase 目录 `src/components/ai/{Component}.vue`；Pinia store `src/store/modules/ai-{domain}.ts` | `src/components/ai/ConfirmCard.vue`、`src/store/modules/ai-chat.ts` |
| 接口 e2e | `e2e/http/L{nn}-{kebab-scope}.mjs`，`nn` 为手册层号 01~21；`L00` 固定留给 §6.5 的契约对账，不占层号 | `e2e/http/L00-contract-consistency.mjs`、`e2e/http/L05-hitl-confirm.mjs` |
| 浏览器 e2e | `e2e/ui/{domain}-{page}.spec.ts`，覆盖 34 页巡检 | `e2e/ui/audit-tool-call.spec.ts` |
| RAG 评测 | `e2e/rag/cases.jsonl`（用例集）、`e2e/rag/run.mjs`（跑分）、`e2e/rag/report-{YYYYMMDD}.md`（报告，不入库） | `e2e/rag/report-20261126.md` |
| CI 门禁脚本 | `scripts/checks/{scope}.mjs`，零依赖纯 ESM，本地与 CI 同一入口复跑；新增强制规则一律落脚本再登记进工作流，不写"只有文档的门禁"。每条必过步骤还要在「CI 门禁覆盖」小结表里有一行，缺依赖时的跳过要写明缺哪个文件 | `scripts/checks/schema-dialect-parity.mjs`、`scripts/checks/menu-component.mjs`、`scripts/checks/gitignore-hygiene.mjs` |
| Java 源文件 | `.java` 叶子名必须与 public 类型名逐字一致（JLS §7.6），PascalCase 属语言强制而非项目偏好，豁免本表小写 kebab 校验；包目录段仍全小写，其它扩展名不放行 | `.../org/dromara/ai/contract/AiEventType.java` |
| 交付配置文件 | GitHub 强制约定名，不受本表小写 kebab 约束且豁免命名校验：`.github/workflows/ci.yml`、`.github/pull_request_template.md`（大小写不敏感，须位于默认分支方生效） | `.github/pull_request_template.md` |
| 部署 | `deploy/{stack}/docker-compose.yml` + `.env.example`；Nginx 配置 `deploy/{stack}/nginx/agenthub.conf`；app 镜像固定 `code/back-end/Dockerfile` + 同目录 `.dockerignore`（构建上下文即后端根，`app.build.context` 指过去，口径见 [配置文档 §7](../../configuration.md)）；`{stack}` ∈ `deploy-pg` / `deploy-mysql` | `deploy/deploy-pg/nginx/agenthub.conf` |
| 文档 | 规格与计划 `docs/superpowers/{specs,plans}/YYYY-MM-DD-{kebab-topic}-{design\|plan}.md`；长期文档固定名 `docs/architecture.md`、`docs/development.md`、`docs/configuration.md` | 本文即 `...-decomposition-design.md` |
| 配置键 | 运行期配置沿用手册点分 kebab（`memory-flush-mode`、`reaper.stale-minutes`）；环境变量一律 `AGENTHUB_*` SCREAMING_SNAKE | `AGENTHUB_AI_CRYPTO_KEY` |
| 提交与分支 | 约定式提交 `<type>: <中文简述>`；分支 `feature/<scope>` / `fix/<scope>`，`<scope>` 用 `{domain}` 名 | `feat(knowledge): 混合检索 RRF 融合` |

**落地要求**：新增文件先在上表定位命名；不符合本表的存量文件名（含 e2e、SQL、前端目录）在 D1 契约冻结前一次性改齐，之后走 §6 的契约变更流程。

---

## 4. 功能需求（全量清单）

本周全部交付。下表"片"列为工程归属编号，**逐日与人手分配以 §8 为准**。

### 4.1 智能体与对话

| 功能 | 要点 | 片 |
|---|---|---|
| 任务工作台 | 会话列表（新建/置顶/重命名/删除）、消息流、Markdown 渲染、工具调用卡片、专家卡片、路由提示条、内联确认卡片、进度播报、参考来源抽屉、重新生成（仅最后一轮只读回答）、消息操作、附件气泡 | A1/A2/A3 |
| 入口智能体小Z | 全平台唯一入口（唯一索引强制单行）：名称/编码（不可改）/描述/系统提示词/绑定模型/角色模型槽位/配额/归属部门/可见范围/最大迭代/状态；内置协议工具 `route_decision`（`action=route` 选专家 + 任务片段，`action=self` 自答）；可用资源来自会话级资源集，不在入口页绑定 | A3 |
| 专家配置 | 列表与筛选、查看/修改/复制/删除；表单含描述（参与路由提示词）、系统提示词、绑定模型、自动路由、工具集 + 三档授权、已记住工具、知识库、技能、子智能体、角色槽位、配额、可见范围；提示词 AI 辅助生成、9 类领域模板、克隆、保存前体检（只建议不硬拦） | A3 |
| 任务管理（会话治理） | 跨用户分页检索、消息与工具明细、批量终止、批量删除（含 Redis 记忆清理）、导出留档；终止与清理的操作口径见 §12.5 | A12 |
| 定时任务 | 任务名称/智能体/运行身份/Cron/提示词/配额；立即执行一次；执行记录弹窗；60 秒派发 + `last_run_time` 原子抢占去重 | A10 |
| 计划模式与任务清单 | 计划模式先只读设计、人工确认后执行；`todo_write` 可见待办；两项默认关闭 | A6 |
| 技能仓库 | `ai_skill`（SKILL.md 正文）+ `ai_skill_resource`；按智能体绑定装配；内置 `load_skill_through_path` / `read_skill` 按需加载；装配期注入"技能优先"纪律 | A7 |
| 长期记忆 | 按用户隔离（**默认开启** `memory-enabled=true`）：记忆配置（开关/注入上限/抽取策略/合并间隔/保留天数/手动合并）、记忆内容（文件清单/版本历史/显式记忆）、运行健康（近 1/7 天汇总）；细则见 §12.2 | A7 |
| 工作区文件 | AGENTS.md 与附加上下文文件在线编辑；共享默认 / 用户覆盖 / 当前生效三层；清除覆盖需二次确认 | A7 |
| 子智能体 | 用途说明、隔离/共享工作区、模型、步数上限、只读工具白名单；平台级资产，多对多绑定 | A9 |
| 场景包 | 按角色/部门打包业务工具（逐工具三档授权，包内决策优先）、外部 MCP 工具（白名单）、技能；生效范围公开/本部门/仅本人 | A9 |
| 对话偏好 | 用户级时区/语言，作用于该用户全部对话与专家任务；页面展示平台默认/我已设置/实际生效三段值 | A6 |
| 首登检查清单 | 管理员首登 4 项检查（对话模型密钥/向量模型密钥/文档解析与向量化/发送首条消息），服务端实时推导，可暂时隐藏 | A1 |
| 附件多模态 | 图片单张 ≤512KB 装配期转 Base64（需视觉模型，上传前置校验）；文档单文件 ≤5MB 解析为纯文本内联（超长首尾截断），不落库不切片；单条消息 ≤5 个；流式中不可上传；仅放行解析器支持的后缀；暂存件生命周期与 72 小时回收见 §12.6 | A1 |

### 4.2 模型与推理

| 功能 | 要点 | 片 |
|---|---|---|
| 模型配置 | 标识/名称/提供商/接入地址（OpenAI 兼容）/密钥（密文落库不回显，留空保持原值）/参数/状态；连通性测试不必保存；整行复制（密钥密文原样搬运，名称唯一化）；被引用时拦截删除 | A1 |
| 用量与配额 | 按日期/智能体/模型聚合 Token 与费用；日 Token/日请求/月 Token 上限；导出明细 | A6 |
| 上下文压缩 | 条数/Token 双阈值触发、保留尾部条数、压缩前落盘、卸载 jsonl、大工具结果落盘仅回填首尾预览 | A6 |
| 编排预算 | 专家轮 Token 与墙钟预算，运行期可调（Redis 即时生效）；触顶即停、降级自答并如实声明 | A6 |

### 4.3 知识库与检索（RAG）

| 功能 | 要点 | 片 |
|---|---|---|
| 知识库管理 | 名称/编码、向量模型、向量维度、切分策略、检索方式（vector / hybrid）、生效范围；hybrid 保存触发 BM25 索引异步构建，列表展示索引状态（未构建/构建中/可用/失败），可重建 | A4 |
| 文档与切片 | 上传入 OSS 登记、解析重建切片、向量化重建索引、切片分页、纯文本预览、删除级联清理 | A4 |
| 检索测试 | Top-K 与相似度阈值可调，实时命中片段与分数；混合检索 RRF 融合、可选 Rerank、上下文装配（去重/相邻合并/Token 预算）、参数透传做 A/B | A4 |
| 检索链路 | 查询 →（可选改写）→ 稠密向量 + BM25 召回 → RRF 融合 →（可选重排）→ 权限过滤 → 去重/相邻合并/预算装配 → 下发模型；未配密钥/非 2xx/超时/结构异常一律降级原顺序；BM25 未就绪自动降级纯向量 | A4 |
| RAG 评测 | hit@1/3/5、Recall@5、MRR@5、平均耗时；基线 hit@1 ≥ 75.0%、hit@3 ≥ 89.3%、hit@5 ≥ 98.2%、MRR@5 ≥ 0.829；正负例并重；独立评测库不污染生产库 | A4 |

### 4.4 工具与集成

| 功能 | 要点 | 片 |
|---|---|---|
| 业务工具注册 | 元数据（编码/名称/类型只读·写入/实现标识/参数 JSON Schema/启停/MCP 暴露/说明）+ 代码侧工厂两段式；标签管理（名称唯一、级联解绑）；按标签筛选、仅看未打标签 | A2 |
| MCP 服务端（出口） | `/mcp` 端点、口令校验 fail-closed、暴露工具清单（仅只读，热生效）、内置 `get_system_info` 自演示工具、启动幂等登记自演示客户端连接 | A8 |
| MCP 客户端（接入） | 外部 Server 名称/传输/地址/白名单/请求头密文/超时/用途/状态；测试连接并列工具；工具名归一化；回参逐块脱敏（装配期最外层包装） | A8 |
| 渠道接入 | 飞书/企微/钉钉/GitHub；渠道配置（凭据留空保持原值，回调地址与长连接提示，连接状态，按手机号查外部标识）、外部身份映射、会话映射三页签；Webhook 验签 + 长连接外连双形态；入站文本/图片/可解析文档；语音视频仅用平台转写；媒体失败降级标记文本；渠道触发默认拒绝写工具 | A11 |

### 4.5 审计与观测

| 功能 | 要点 | 片 |
|---|---|---|
| 会话与消息审计 | 跨会话检索消息明细（关键词/智能体/用户/时间），导出留档 | A12 |
| 工具调用明细 | 工具名、发起智能体（按明细行自身归属）、触发用户、会话、状态、耗时、入参/出参；按工具统计次数/成功/失败/失败率/平均耗时；多维筛选 | A12 |
| 权限决策日志 | ALLOW/ASK/DENY 全量留痕 + 人工确认处置结果 | A2 |
| 链路追踪 | 一次任务一条：执行期模型版本（含槽位回退说明）、上下文清单、状态迁移时间线 | A12 |
| 编排运行 | 每轮编排一行：路由决策（动作/目标专家/理由/决策原文）、任务片段、专家调用明细（输出原文留证，状态含已回答/未完成/调用失败/超时/待确认）；统计卡；`?sessionId=` 深链 | A3 |
| 平台运行日报 | 每日 08:00 生成昨日日报五块（用量配额/定时成败/待办积压/权限决策/登录）；数字服务端现取、模型只出日期与正文；按日期 upsert；只读页 + 近 7 天缺失日期提示 | A10 |
| 健康自检 | 一次全量分支检查（FAIL/WARN/INFO 三档），检查项与判定标准见 §12.3；发布门禁与每日随日报刷新 | A12 |
| 审计保留管理 | 保留配置查看、dry-run 预检、带理由的手动清理与清理记录；越权约束见 §12.4 | A12 |

### 4.6 安全合规

| 功能 | 要点 | 片 |
|---|---|---|
| 敏感词库 | 普通词与正则、分类、启停 | A5 |
| 安全事件 | 敏感词/PII 命中与处置明细，按场景/动作/用户/时间检索 | A5 |
| 安全合规配置 | 总开关、各场景处置策略（只记录/脱敏放行/阻断）、检测引擎（本地词库/外部 API，缺配置自动降级本地，调用失败按未命中放行）、PII 脱敏独立开关（手机号/身份证/邮箱/银行卡/IP）、提示词注入防护 | A5 |
| 覆盖场景 | 输入 / 输出 / 渠道出站 / 工具结果 / 知识库入库 / MCP 出口；业务工具与外部 MCP 回参逐块脱敏 | A5 |
| 无人值守白名单 | 定时任务与渠道链路写工具默认一律拒绝（按 `tool_type` 代码强制）；仅白名单可自动执行；管理端显式 DENY 仍拒绝；变更需权限并填理由 | A10 |
| 生产密钥门禁 | 出厂示例密钥在 prod 下拒绝启动并列出入项；非 prod 可降级告警 | A5 |

### 4.7 底座通用能力（继承，不重复建设）

RBAC 用户/角色/菜单/字典、OSS 对象存储、统一消息推送、工作流、规则编排、操作日志、通知公告。

---

## 5. 数据模型

40 张 `ai_` 表，**本周全部交付**，双方言脚本幂等（`if not exists` / `on conflict do nothing`）。主键与 `create_dept/create_by/create_time/update_by/update_time/del_flag` 沿用底座约定；双方言脚本 `script/sql/postgres/agenthub-ai-ddl.sql` 与 `script/sql/mysql/agenthub-ai-ddl.sql`（种子与菜单见 §3.2），同步维护。

| 分组 | 表 |
|---|---|
| 模型 | `ai_model_config` |
| 知识库 | `ai_knowledge_base`、`ai_document`、`ai_chunk` |
| 智能体 | `ai_entry_agent`、`ai_expert_agent`、`ai_agent_tool`、`ai_agent_tool_trust`、`ai_agent_kb`、`ai_agent_skill`、`ai_agent_subagent` |
| 会话 | `ai_session`（`status`：RUNNING/AWAITING_CONFIRM/IDLE/STOPPED）、`ai_session_resource`、`ai_message`、`ai_message_attachment`（`status`：PENDING/BOUND/ORPHANED/RECYCLED + `expire_at`） |
| 工具 | `ai_tool`、`ai_tool_tag`、`ai_tool_tag_rel` |
| MCP | `ai_mcp_client` |
| 技能 | `ai_skill`、`ai_skill_resource` |
| 子智能体 | `ai_subagent` |
| 渠道 | `ai_channel`、`ai_channel_identity`、`ai_channel_thread` |
| 定时任务 | `ai_agent_task`、`ai_agent_task_run` |
| 用量 | `ai_usage_stat` |
| 日报 | `ai_daily_digest` |
| 审计 | `ai_tool_call_log`、`ai_route_run`、`ai_route_expert_call`、`ai_permission_decision_log`、`ai_trace` |
| 场景包 | `ai_scenario_pack`、`ai_scenario_pack_tool`、`ai_scenario_pack_mcp_tool`、`ai_scenario_pack_skill` |
| 安全 | `ai_sensitive_word`、`ai_safety_event` |

**长期记忆与工作区不建表**，落在工作区目录（`workspace-root=./data/agent-workspace`）按用户分目录：

```
{workspace-root}/{userId}/AGENTS.md                        # 人格约定（注入 system prompt）
{workspace-root}/{userId}/memory/MEMORY.md                 # 长期记忆正文（注入源）
{workspace-root}/{userId}/memory/daily/{yyyy-MM-dd}.md     # 抽取流水（90 天）
{workspace-root}/{userId}/memory/explicit.md               # 显式记忆（人工补充与排除项）
{workspace-root}/{userId}/memory/state.json                # 抽取水位
{workspace-root}/{userId}/memory/versions/{ts}-{tag}.md    # 只增版本快照
{workspace-root}/{userId}/plans/                           # 计划模式产物
{workspace-root}/{userId}/offload/                         # 压缩前历史与大工具结果卸载
```

文件写操作一律「临时文件 + rename」原子替换；版本快照只增不删，恢复不破坏历史。

---

## 6. 接口与事件契约（D1 冻结，冻结后改动需队长批准并同步三线）

### 6.1 接口约定

- 路由 `/ai/{域}/{资源}`；每接口带 `ai:{模块}:{动作}` 权限标识并同步登记菜单脚本（98 按钮权限的前端显隐由此驱动，前端不等后端）。
- 入参 Bo / 出参 Vo / 实体三层，MapStruct-Plus 映射；统一响应体沿用底座。
- 对话两步：先 `POST /ai/chat/session/{id}/message` 落库取稳定 `messageId`，再 `GET /ai/chat/stream` 建流取回答；HITL 暂停轮用 `GET /ai/chat/resume` 恢复。
- 唯一免登录端点为渠道回调 `/ai/channel/callback/**`，安全由验签/解密/渠道口令承担。
- RSA 接口加解密需白名单，明确排除流式与文件流。
- **§12 裁决新增的权限标识**（D1 一并登记菜单与按钮脚本）：`ai:health:check`、`ai:audit:retention:view` / `:edit` / `:purge` / `:unlimited`、`ai:session:governance:list` / `:terminate` / `:delete` / `:export`、`ai:memory:version:restore`、`ai:attachment:recycle`。
- **重冻后的登记名（2026-11-20）**：上一条是按能力意图写的暂定名，实现落库时按「模块 = URL 资源段」的硬规范改成了下面的名字，菜单与常量登记、前端 `hasPermi` 引用一律用后者；上一条保留作意图说明，不再作为登记名：
  `ai:health:query` / `ai:health:probe`（自检与按需探测）、`ai:audit:route:query`（保留配置读取，复用审计查询）/ `ai:audit:retention:edit` / `ai:audit:retention:cleanup`（`POST /ai/audit/retention/purge` 的执行入口）、`ai:session:list` / `:query` / `:edit`（结束会话即治理里的终止）/ `:remove` / `:export` 与 `ai:session:state:reap`（闲置回收手动触发）、`ai:memory:query` / `ai:memory:edit`（`POST /ai/memory/versions/{versionId}/restore` 用编辑档，不再单开 `:restore`）、`ai:chat:attachment:reclaim`。「永久豁免档」当前没有独立权限，见 §12.4。

### 6.2 SSE 事件协议

每条事件携带 `type / sessionId / turnId / seq / payload`。事件类型：

| 类型 | 载荷要点 |
|---|---|
| `text_delta` | 正文增量文本 |
| `route_hint` | 自答 / 路由专家 / 已指定专家 |
| `expert_start` / `expert_result` | 专家名、状态、重试次数、失败原因、原始结论 |
| `tool_call_start` / `tool_call_end` | 工具名、来源标签、状态、耗时、入参、结果摘要 |
| `confirm_request` / `confirm_result` | 工具、入参、允许本次 / 记住该工具 / 拒绝 |
| `progress` | 思考 / 调用工具 / 咨询专家 / 汇总 |
| `sources` | 命中片段与出处 |
| `stopped` | 中断原因 |
| `turn_done` / `turn_error` | 状态与用量 / 结构化错误 |

**硬约束**：确认卡片刷新后可还原 ⇒ 编排事件必须可持久化或可重建，落 `ai_message` 的编排事件快照字段。专家侧工具调用在主会话以"工具调用 N 次"汇总，明细进审计页；入口智能体自调用逐条渲染。

**命名对齐（2026-11-20 随契约重冻登记）**：实现落地的线上事件名与本表用词有出入，重冻以实现的 16 项为准，本表保留作意图说明。逐项对应：

| 本表（设计用词） | 线上名（`AiEventType`） | 说明 |
|---|---|---|
| `text_delta` | `text_delta` | 一致 |
| `route_hint` | `route_decision` | 同一语义，改名 |
| `expert_start` / `expert_result` | `expert_start` / `expert_result` | 一致 |
| `tool_call_start` / `tool_call_end` | `tool_call_start` / `tool_result` | 入参流式增量另有 `tool_call_delta` |
| `confirm_request` / `confirm_result` | `tool_confirm` | 确认结果只经 `POST /ai/chat/confirm` 返回，不再回推独立事件 |
| `progress` | `phase` | 取值见 `AiPhaseSupport` |
| `sources` | `references` | 一致语义 |
| `stopped` | `aborted` | 用户停止与管理员终止共用 |
| `turn_done` / `turn_error` | `done` / `error` | 一致语义 |
| —— | `start` | 本轮开始，回 assistant 消息 id |
| —— | `route_fallback` / `answer_reset` | 回落自答与正文作废成对出现，前端据此清空已渲染片段 |

16 项的完整枚举见 §6.5。

### 6.3 工具与装配契约

- `ai_tool.impl_name` ↔ 代码侧工厂常量，是元数据与代码之间**唯一允许的引用面**；注册页不产生裸 SQL/Shell/HTTP 万能工具。
- 三档授权枚举：`0` 允许 / `1` 需确认 / `2` 禁止；优先级 会话直选 > 场景包 > 智能体绑定；禁止档装配期不注册（模型不可见）。
- 装配缓存键：`智能体 × 会话资源集签名`；资源内容变更由装配指纹触发重建；改选择后下一轮生效；同资源组合多会话共享实例。
- 降级必须带**结构化原因码**，否则"降级并如实声明"无法统一实现。本轮冻结码表：`EXPERT_FAILED` / `EXPERT_TIMEOUT` / `EXPERT_EMPTY` / `ROUTE_CONTRACT_INVALID` / `BUDGET_EXCEEDED` / `BM25_NOT_READY` / `RERANK_UNAVAILABLE` / `SAFETY_ENGINE_UNAVAILABLE` / `MEDIA_FAILED` / `ADMIN_TERMINATED` / `STALE_REAPED`。
- **原因码落地口径（2026-11-20 重冻）**：`ai_route_run.degrade_reason` 存的是给人读、也给运维筛选的中文文案（`varchar(500)`），编排侧的冻结码按 `AiDegradeReason` 的 8 个固定前缀归类——`EXPERT_FAILED` / `EXPERT_TIMEOUT` / `EXPERT_EMPTY` 合为「专家结论不可用：+ 现场摘要」（续跑与断点恢复各有前缀，见 §6.5 词表），`ROUTE_CONTRACT_INVALID` 拆为 action 越界与候选非法两条，`BUDGET_EXCEEDED` 由 `AiBudgetGuard` 拼「超出编排预算（+ 明细」。其余四码不经该列：`RERANK_UNAVAILABLE` 以 `RerankOutcome.degradedOrder` 原位返回、`SAFETY_ENGINE_UNAVAILABLE` 由 `AiSafetyGuard` 降级为本地词库并告警、`BM25_NOT_READY` 走索引任务与告警日志、`MEDIA_FAILED` 在多媒体装配点降级，四者统一在健康自检报告（`AiHealthCheckServiceImpl`，15 项）里可见；`ADMIN_TERMINATED` / `STALE_REAPED` 属会话终止而非编排回落，走 `aborted` 事件与会话状态列。列宽由 `AiRouteRecorder.DEGRADE_REASON_MAX` 与两方言 DDL 对账，摘要按 headroom 截断。
- 写工具：HITL 由档位驱动、执行期复核业务权限、操作人取自运行上下文（Reactor 线程无 Sa-Token 上下文）、数据权限走不显式挂数据权限注解的方法并显式归属校验、无人值守默认拒绝、非幂等操作要求幂等键、专家链路写工具短期去重（TTL 可配）。
- 内置无害只读工具（当前时间/当前用户/当前部门/当前权限）无需绑定即注册并默认放行；用户级"记住该工具"按 `(userId, sessionId)` 槽位每轮并入，不写进共享实例。

### 6.4 三层权限模型

① 工具可见面（装配期：智能体绑定 ∪ 会话资源集 ∪ 平台内置只读，禁止档不注册）→ ② 三档授权（运行期 ALLOW/ASK/DENY）→ ③ 业务权限复核（执行期按提问用户复核菜单权限）。

### 6.5 契约事实源与对账（2026-11-20 重冻）

§6.1~§6.4 原先只把契约写在表格和段落里，改一边不改另一边的情况无法被发现。本次重冻的原则是**以已落地的实现取值反向冻结**：每个契约面指定唯一一处常量源，另加一条本地与 CI 都能跑的机械断言；文档与实现不一致的地方在 §6.2、§6.3 逐项标注，不为了对齐旧表格去改实现。

| 契约面 | 唯一常量源（`org.dromara.ai.contract`） | 对账的另一/两侧 | 当前口径 |
|---|---|---|---|
| SSE 事件类型 | `AiEventType`（16 项，`wire()` 即线上名） | 后端各 `EVENT_*` 发射常量、前端 `api/ai/chat/types.ts` 的 `ChatStreamEvent['type']` 联合 | 三方逐字相等且双向无多余 |
| 权限标识 | `AiPermissionKeys`（131 项） | `@SaCheckPermission` 实参、双方言 `agenthub-ai-menu.sql` 的 `perms`、前端 `hasPermi` 引用 | 注解 ⊆ 菜单 = 常量；MySQL 与 PostgreSQL 集合相等 |
| 工具实现标识 | `AiToolImpl`（30 项） | `AiToolFactory` 的 `IMPL_*` 常量、双方言种子脚本的 `ai_tool.impl_name` | 三方集合相等（§6.3 的「唯一引用面」由此可执行） |
| 降级原因 | `AiDegradeReason`（8 项中文前缀，`prefix()`） | 代码里 `degradeReason = "…"` 的字面量 | 字面量必须命中某个前缀；异常兜底分支直接落 `rootCauseMessage(e)`，无固定前缀，不入词表 |

菜单登记口径：139 行 = 7 目录 + 34 页面 + 98 按钮，`perms` 去重后 131 个 `ai:` 标识（`ai:health:probe` 在页面与按钮各登记一次）。§6.1 段首「98 按钮权限」按此口径理解。

对账入口：`e2e/http/L00-contract-consistency.mjs`（零依赖 ESM，本地与 CI 同一命令），已登记为 `.github/workflows/ci.yml` 的必过步骤；§3.2 另由 `scripts/checks/menu-component.mjs` 把菜单 `component` 与前端页面文件对齐，同样登记为必过步骤。

**实现与 §6.2 的已知偏差（保留，不算缺陷）**：

- 事件帧是扁平 JSON（统一由 `AiSseSupport.envelope` 构造，`type` 之外按事件类型直接挂字段），未落地本节早先写的 `sessionId / turnId / seq / payload` 四层包装；SSE 也没带 `id:` 字段，浏览器原生 `Last-Event-ID` 续传不可用。
- 「确认卡片刷新后可还原」这条硬约束当前的实现方式是 REST 回放，而不是 `ai_message` 的事件快照列：待确认轮次由 `GET /ai/chat/session/{sessionId}/pending` 还原，历史与工具明细由 `.../messages`、`.../toolCalls`、`.../routeRuns` 还原。包装字段与 `id:` 若将来要补，属 §9 硬化项，需按本节流程走契约变更。

---

## 7. 一周交付范围

### 7.1 全量交付清单

- **后端**：40 表双方言、31+ 控制器、编排内核（小Z 路由 / 专家装配缓存 / 降级 / 预算）、工具两段式与内置工具集、三层权限与 HITL、RAG 全链、长期记忆、技能/工作区/子智能体/场景包、MCP 双向、四渠道、定时任务与日报、六场景安全过审、六类审计、用量配额、上下文压缩、计划模式与任务清单、生产密钥门禁、Reaper。
- **前端**：34 个功能页 + 工作台全部交互 + 98 按钮权限显隐 + 首登检查清单。
- **部署**：PG / MySQL 双栈可并存、HTTPS、Nginx 同源伺服、Sites 介绍页上线。
- **测试**：AI 模块核心类单测（JaCoCo 可生成）、4 类集成测试（真库双方言）、接口 e2e 全量、34 页浏览器巡检、RAG 评测达基线。

### 7.2 本周明确不做（手册一期边界外）

跨组织 A2A 协作、模型微调与独立训练、移动端原生 App、复杂跨部门层级式多 Supervisor 编排、实时语音通话。

### 7.3 客观降级触发（不预先砍范围，只在验收未达时由队长当日裁定）

每日 18:00 按 §8.2 当日验收点核对。未达项当日 20:00 前由队长从本清单选择降级并记录原因，**不允许无声滑期**：

| 触发 | 降级动作 | 保底口径 |
|---|---|---|
| MySQL 栈起栈受阻 | 仅 PG 栈上线，双方言脚本仍同步交付并过静态校验 | 脚本双方言不降级 |
| RAG 语料或调参不足 | 交付评测脚本与实测报告，不卡基线数值 | 检索命中与来源可演示 |
| 渠道长连接受阻 | 保 Webhook 形态（飞书/企微/GitHub），钉钉与飞书长连接降级为配置面 + 状态展示 | 至少一条渠道端到端真通 |
| 六场景过审未全覆盖 | 保输入 / 输出 / 工具结果三场景，其余场景配置面交付并标注未启用 | 输入侧阻断与 PII 脱敏必须可用 |
| 低频管理页未完成 | 保 18 个高频页（工作台/模型/专家/知识库三页/工具/MCP 两页/渠道/定时/审计六页/安全三页/记忆/工作区），其余页挂"待接入"占位并隐藏菜单 | 已交付页不允许有假数据 |
| e2e 未全绿 | 保演示主链路与 HITL 两层零 FAIL，其余层出报告并列失败清单 | 主链路不允许带 FAIL 上线 |

---

## 8. 交付形态与节奏（2026-11-20 改为队长单线全量交付）

### 8.1 决策记录

原计划的三人分工（队长 + 后端 B + 前端 C）在同日取消，改为**队长一人单线全量交付**：三人排班的前提是三条任务线能并行推进共享契约，实际契约面（40 表双方言、SSE、权限、工具 impl）在实现里仍在动，并行只会把返工放大。取消分工不动的是范围与验收标准——§4 全量清单、§7.1 一周交付清单、§10 验收口径全部保留，§7.3 的客观降级触发也保留。

### 8.2 交付切分

单线不等于单块提交。本次交付按影响面切成三条 PR 线，每条一个分支，串行合入 `main`：

| 线 | 分支命名 | 内容 | 合入前置 |
|---|---|---|---|
| 后端 | `feature/backend-full-code` | 底座 + `ruoyi-modules/agent-hub-ai` 全量 AI 能力域 | CI 必过（含 AI 模块单测） |
| 前端 | `feature/frontend-full-code` | 管理台前端 + 34 个 AI 页面 + SSE 客户端 | CI 必过（含前端构建） |
| 契约与门禁 | `chore/contract-refreeze` | §6.5 常量类、双方言 SQL、部署与 initdb 链、`scripts/checks` 与 `ci.yml` 登记、设计稿同步 | CI 必过 + L00 契约对账 |

分支之间只允许通过 `main` 汇合：任一条线合入后，其余分支先 `git merge origin/main` 复跑门禁再推。

### 8.3 节奏与检查点

§8.2 的 D1~D5 排班表不再按人分列，改为交付顺序上的检查点，每个检查点必须当场可验证：

| 检查点 | 内容 | 当场验证方式 |
|---|---|---|
| C1 底座与栈 | 双栈起栈、initdb 链、登录 | `docker compose up -d` 后 40 表 / 131 perms / 中文无损，能登进管理台 |
| C2 契约 | 四类契约重冻并钉死 | `node e2e/http/L00-contract-consistency.mjs` 与 `scripts/checks/*` 全绿 |
| C3 后端能力域 | AI 模块单测 | `mvn -pl ruoyi-modules/agent-hub-ai -am test` 通过，且执行顺序无关 |
| C4 前端 | 构建 + 菜单可达 | `pnpm build` 通过，`scripts/checks/menu-component.mjs` 全命中 |
| C5 联调 | 真实栈冒烟 | 对话流打通、SSE 逐帧不缓冲、写工具确认与恢复、审计落库 |

§9 的接口 e2e 分层、浏览器巡检、RAG 评测仍按「一周后硬化」执行，不在本次门禁内。

### 8.4 协作规则

- 契约变更必须队长批准；单线交付期由队长开 PR，CI 必过后合入 `main`（队长已获直接合并授权），AGENTS.md 的「非作者评审」在他人可得时照常执行。
- 双方言 SQL 同步改；新增接口必须带权限标识并登记菜单脚本；新增工具/配置项/接口同步更新 docs。
- 密钥不入库不入仓：模型密钥走页面密文落库或环境变量，测试机密放 `.env.local`（已 gitignore）。
- 写工具、装配、路由改动必须补单测；提交遵循约定式提交（feat/fix/docs/test/refactor/chore）。
- 降级只能走 §7.3，由队长当日记录，不允许口头砍需求。

---

## 9. 一周后的硬化与演进

一周内 13 个工程片（A0~A12）全部交付；一周后不再排"手册未做功能"，只做硬化与演进。

| 顺序 | 硬化项 |
|---|---|
| H1 | 单测覆盖率提升与 JaCoCo 门禁纳入 CI；e2e 从人工执行改为流水线硬门禁。注：`.github/workflows/ci.yml` 已先落最小集（双方言、命名、密钥三项恒定门禁 + 制品入仓后自动生效的单测/构建/契约作业），本项只补覆盖率与需真实栈的 e2e 硬门禁 |
| H2 | 降级项回补（§7.3 触发过的每一项，按记录逐项清零） |
| H3 | 可观测性增强：OpenTelemetry GenAI span、Prometheus + Grafana、每会话成本与满意度 |
| H4 | 评测深化：裁判模型接地性抽检、badcase 回归集、路由与提示词 A/B |
| H5 | 二期演进（手册第十四部分）：A2A 跨组织协作、AG-UI 适配、GraphRAG / CRAG / MMR、技能沉淀闭环、Agent 产物版本化与 LL 网关 |

---

## 10. 验收标准

### 10.1 每日验收

按 §8.2 当日 18:00 验收点逐项核对；未达项当日走 §7.3。

### 10.2 周末（D5）全量验收

1. 双栈（PG :8080 / MySQL :8081）可同时运行，`docker compose up -d --build` 即有表有数据可登录。
2. 34 页浏览器巡检：无 console.error、无 4xx/5xx、关键元素可见。
3. 接口 e2e 全量零 FAIL（未配密钥层按约定 SKIP，主链路与 HITL 不允许 SKIP）。
4. 集成测试双栈真库零失败零错误。
5. RAG 评测报告：hit@1 ≥ 75.0%、hit@3 ≥ 89.3%、hit@5 ≥ 98.2%、MRR@5 ≥ 0.829（若走 §7.3 降级则附实测值与原因）。
6. 安全门禁：出厂示例密钥 prod 拒绝启动；渠道回调验签/解密齐备；写工具无人值守默认拒绝。
7. 日志末 500 行 ERROR 计数 0；健康自检 0 FAIL。
8. 演示故事端到端可演：小Z 路由 → 专家检索带来源 → 写工具确认 → 落库 → 审计可查；允许与拒绝两路径各演一遍。
9. Sites 介绍页可访问，「进入系统」跳转并登录成功；云端 Agent 问答不接触业务数据。

---

## 11. 开工前置条件（checklist）

| # | 前置 | 责任 | 期限 |
|---|---|---|---|
| 1 | 云服务器：规格（Milvus + etcd + 双栈对内存敏感，建议 ≥ 8C16G）、OS、公网 IP、SSH | 队长 | D1 前 |
| 2 | 域名与 HTTPS 证书：系统域名（前端与 API 同域）、Sites 站点域名、证书有效期 | 队长 | D1 前 |
| 3 | 模型密钥：对话、向量、重排、视觉各一套可用密钥（缺视觉则附件图片功能前置校验拦截） | 队长 | D1 |
| 4 | 演示与评测语料：行政公告/值班制度类文档若干 + RAG 评测用例集（基线复现前提） | 队长 | D3 前 |
| 5 | 渠道测试凭据：飞书/企微/钉钉/GitHub 测试应用与回调地址（公网可达） | 队长 | D4 前 |
| 6 | 外部内容安全 API（可选，缺则自动降级本地词库） | 队长 | D2 前 |
| 7 | 介绍页文案与界面截图素材（队长提供并审定）；演示账号与 IP 白名单策略 | 队长 | D4 前 |
| 8 | 演示日期、时长与对象 | 队长 | D1 站会 |
| 9 | 开源中后台底座 6.0.0 制品（后端与前端发行包，供 D1 导入 `code/back-end`、`code/front-end`） | 队长 | D1 前 |

**#9 已就位（2026-11-20）**：底座整树导入 `code/back-end` 与 `code/front-end`，许可与原创边界登记在 [开源许可与原创边界](../../open-source-licenses.md)；底座基线 SQL 整理进 `code/back-end/script/sql/base/{dialect}/`，initdb 链不再依赖仓库外文件（见 [部署与环境变量](../../configuration.md) 第 3 节）。#1~#6、#7~#8 属云环境与素材，仍在联调前逐项确认。

---

## 12. 补充细则裁决（2026-11-20 队长已裁定，纳入契约冻结）

手册未给出可实现口径的 6 项，现已逐条裁决完毕。以下与 §6 同级：**改动需队长批准并同步三条任务线**。

全量代码入仓后按 §1 的 D11 逐条复核了一遍，**与实现冲突处以 §12.7 为准**。

### 12.1 路由判定（A3）——本周不引入独立前置判定

- 路由由入口智能体在轮内调用协议工具 `route_decision` 完成；**不扩** `ai_model_config.model_type` 枚举（保持对话/向量/重排/视觉），角色模型槽位仍只有压缩摘要 / 记忆抽取 / 子智能体三项。
- 质量口径：`ai_route_run` 记动作、目标专家、理由与决策原文；命中率与降级率由编排运行统计卡出数。
- 回落：模型未按契约产出决策 → 兜底自答，原因码 `ROUTE_CONTRACT_INVALID`。
- 前置判定留作契约变更项（需新增 `decision` 类型 + `route` 槽位 + 影子双写标定），不进入本周。

### 12.2 长期记忆抽取 / 合并 / 版本（A7）

默认开关 `memory-enabled=true`（取自手册 A.5；v2.0 本文曾误写为默认关闭，已纠正）。

| 环节 | 裁决口径 |
|---|---|
| 抽取触发 | `always` 每轮结束异步；`throttled`（默认）距上次抽取 ≥ `memory-flush-throttle-minutes=30` 且有新消息；`never` 仅显式通路。显式「记住…」绕过节流，立即写 `explicit.md` |
| 净化（两道） | ① PII：复用安全模块检测器（手机号/身份证/邮箱/银行卡/IP），命中即丢弃该条并记 `ai_safety_event`；② 指令净化：剔除注入句式、工具调用语法与密钥样式串。单条 ≤500 字符 |
| 水位推进 | `memory/state.json` 记 `last_flushed_message_id`；正文落盘成功后原子写水位；失败不推进、下轮重试，同水位 ID 幂等不重复；连续 3 次失败计入运行健康并 WARN，**不阻塞对话** |
| 合并 | 最小间隔 30 分钟 + 维护间隔 1440 分钟；按 ≤`memory-consolidation-max-tokens=4000` 的流水分批，**每批合并后立即推进一批水位**；合并前先写 `versions/{ts}-PRE_MERGE.md` |
| 版本恢复 | 写当前 `ROLLBACK_POINT` 快照 → 以目标版本正文覆盖 `MEMORY.md` → 写 `RESTORE` 快照；验收标准 = 正文与目标版本逐字一致；历史只增不删；需 `ai:memory:version:restore` + 二次确认 + 操作日志 |
| 注入与护栏 | `MEMORY.md` 注入 ≤`memory-max-context-tokens=8000`；显式记忆优先级高于抽取条目；装配期只注册 `memory_search`，`memory_save` / `session_search` **不注册** |
| 保留 | 流水 90 天 → 归档 365 天（`memory-auto-archive-enabled=true`）、会话日志 180 天；由每日维护任务清理，Redis 锁保证多副本单实例执行 |

### 12.3 健康自检（A12）

接口 `GET /ai/health/check`（权限按 §3.2 冻结为 `ai:health:query`，本行原写的 `ai:health:check` 是 D1 重冻前的旧名，已更正）；单项超时 3 秒、总预算 30 秒（未落地，记 §12.7 I-14）；结果缓存 60 秒（已落地，见 §12.7 I-2），这份缓存是全局的，因此检项取数一律用平台全量视图而不是按登录用户可见范围过滤的选项查询（§12.7 I-15）；分支三档，页面按分支展开明细。

| 档 | 检查项 |
|---|---|
| **FAIL**（阻塞发布门禁 4） | 数据库连通且 40 表齐备（PG/MySQL 各查本栈）；Redis 读写；对话模型连通；向量模型连通；对象存储读/写/删；Milvus 集合维度与知识库声明维度一致；`ProductionConfigGuard` 命中出厂示例值 |
| **WARN** | Rerank 未配或超时；外部审核引擎不可用（自动降级本地词库）；BM25 索引未就绪或失败；渠道长连接离线；MCP 出口口令未设（fail-closed，功能不可用）；磁盘或 Redis 内存余量 <15%；Reaper 心跳超 2 个扫描周期；向量化队列积压打满 200；近 24 小时出现 ERROR 日志 |
| **INFO** | 版本号与 profile、栈标识（PG/MySQL）、各 `ai_` 表行数、最近一次日报生成时间 |

触发时机：每日 08:00 日报生成后自动执行、发布流程「灰度 → 健康自检 → 冒烟 → 全量」中执行、管理页手动刷新。D5 门禁要求 **0 FAIL**。

### 12.4 审计保留管理（A12）

手册已定默认：`retention.audit-days=180`（0 为不限）、`retention.auto-purge-enabled=true`、每日 03:30、Redis 锁多副本单实例、**清理范围不含会话正文**。裁决补充操作面与越权约束：

- **操作面（做到 dry-run + 手动清理，不含清理前归档导出）**：查看当前保留配置、上次清理时间与各类型删除行数；dry-run 预检按「类型 + 截止日期」返回待删计数；手动清理必须先 dry-run → 二次确认 → 填写理由；清理执行记录列表（时间/操作人/类型/截止日/行数）。
- **越权约束**：`ai:audit:retention:view` / `:edit` / `:purge` 三个独立权限；保留期下限 30 天，`audit-days=0`（不限）需额外 `ai:audit:retention:unlimited`；**只允许按时间边界删除，禁止按用户、会话或关键词选择性删除**；清理不可撤销，不引入软删。
- **实现**：按 `create_time` 索引分片删除，单事务 ≤5000 行避免长锁；先删 `ai_route_expert_call` 再删 `ai_route_run` 保证引用一致；六类明细各自独立配置与计数。

### 12.5 会话状态管理（A1）

- **状态口径**：`ai_session.status` ∈ `RUNNING`（有编排在进行）/ `AWAITING_CONFIRM`（Redis 存在未决确认，由 Redis 推导不回写）/ `IDLE` / `STOPPED`；删除走 `del_flag`。
- **终止链路**：置 Redis 停止标志 → 内核在下一个事件边界中止 → 未决确认自动置「拒绝」（不执行任何写工具）→ SSE 下发 `stopped(reason=ADMIN_TERMINATED)` → 该轮消息标 `INTERRUPTED` → `ai_route_run` 置已终止。**同轮不可恢复**，用户可继续发新消息。
- **并发**：会话级 Redis 锁 + 状态 CAS；重复终止幂等返回；对 `RUNNING` 会话直接删除返回 409，前端引导先终止。
- **权限与可见范围**：`ai:session:governance:list` / `:terminate` / `:delete` / `:export`；列表可见范围按角色数据权限（本部门 / 全部）；操作他人会话必填理由并写底座操作日志。
- **删除语义**：软删会话与消息 + 清理 Redis 会话状态与信任槽位 + 附件转 `ORPHANED`（见 §12.6）+ **不动 Milvus**；导出为 CSV/XLSX 落 OSS，浏览器侧预签名 120 秒。
- **Reaper 兜底**：停留「进行中」超过 `reaper.stale-minutes=60` 的会话置 `STOPPED`，原因码 `STALE_REAPED`。

### 12.6 附件回收（A1）

- **生命周期**：上传 → 私有桶 + `ai_message_attachment.status=PENDING`、`expire_at = 上传时间 + 72 小时`；发送 → 绑定 `message_id` 转 `BOUND`；整轮删除或会话清理 → 转 `ORPHANED`（再留 72 小时，保证导出留档窗口）；每日 **04:00** 回收任务（与 03:30 审计清理错开）删除 OSS 对象并置 `RECYCLED`。
- **暂存配额**：单用户 `PENDING` ≤20 个且 ≤50MB；超限拒绝新上传并提示清理；回收任务优先回收最旧的 `PENDING`。
- **手动回收**：工作台「暂存附件」列表可立即删除并直接回收，权限 `ai:attachment:recycle`（本人资源）。
- **安全**：全程私有读，浏览器访问一律走预签名 URL（`oss.presign-expire-seconds=120`）；`PENDING` 文件仅上传者可见；回收不产生任何公开 URL。
- **装配口径不变**：图片 ≤512KB 装配期转 Base64、文档 ≤5MB 解析为纯文本内联，均不入知识库、不做切片；解析出的纯文本随消息正文一并删除。

---

### 12.7 落地核对：契约重冻与效力修正（D11 队长裁定，路线 A）

§12.2~§12.6 裁的是设计意图，落地时以代码为事实源复核了一遍：**实现没错的改本文件，契约没错的改实现**。本节与 §6 同级效力，且**对 §12.2~§12.6 有修正效力**（冲突处以本节为准），机械对账由 `e2e/http/L00-contract-consistency.mjs` 执行。

#### A. 状态字典补回与按实体常量重登记（本次唯一的数据变更）

缺陷有两层。第一层是**取值不一致**：`ai_*_status` 字典当初照 §12.5/§12.6 的词表写成 UPPER 串，而服务层落库是 char 码或小写串——库里存 `0`、字典配 `RUNNING`，管理台状态列只能显示裸码或空白，且只有点开列表才暴露。第二层是**字典整块缺失**：后端全树导入（`2c4c7ec`）把 D1 契约 seed 里的 `sys_dict_*` 整块覆盖掉了，合入后 AI 侧一条字典都没登记。本次一并补回，取值改为以实体 `STATUS_*` 常量为准（单一来源），当前在架 **31 类 / 108 项**、双方言逐字一致。

| 字典类型 | 在架取值（与实体常量逐字一致） | 对账基准 |
|---|---|---|
| `ai_session_status` | `0` 进行中 / `1` 已结束 | `AiSession.STATUS_ACTIVE` / `STATUS_CLOSED` |
| `ai_message_status` | `0` 正常 / `1` 失败 / `2` 待确认 / `3` 已中断 | `AiMessage.STATUS_*` |
| `ai_task_run_status` | `2` 执行中 / `0` 成功 / `1` 失败 | `AiAgentTaskRun.STATUS_*` |
| `ai_route_run_status` | `pending` / `running` / `success` / `degraded` / `aborted` | `AiRouteRun.STATUS_*` |
| `ai_expert_call_status` | `success` / `invalid` / `failed` / `timeout` / `pending_confirm` / `cancelled` | `AiRouteExpertCall.STATUS_*` |

`ai_attachment_status` 不登记：`ai_message_attachment` 没有 `status` 列（补齐该列是 C 段 I-10，落地时字典与对账一并回补）。

新增门禁（L00 E 段，四个方向都断言）：同一方言内字典取值不得重复；字典项必须有登记过的词表；词表不得空着（否则页面拿裸码）；实体 `STATUS_*` 写入的每个取值必须在字典里，字典里不得有代码从不写入的取值。变异测试四项（改大小写、改拼写、删词表登记、删全部字典项）均如期变红。

#### B. §12.2~§12.6 的效力修正（按实现改口径，已生效）

| 原条目 | 原口径 | 落地口径（以此为准） |
|---|---|---|
| §12.3 档位 | 三档 FAIL/WARN/INFO，页面按分支展开 | 单项四态 `PASS`/`WARN`/`FAIL`/`UNKNOWN` × 六个分类（基础设施/模型与向量/集成/安全与运行时/智能体/存储），共 15 项；`overall` 由最差档聚合，发布门禁仍要求 0 `FAIL` |
| §12.3 检查项 | 含对象存储读写删、BM25、渠道长连接、磁盘与 Redis 余量、Reaper 心跳、向量队列积压、近 24h ERROR 日志、INFO 段 | 在架 15 项为：`db`、`db.schema`、`redis`、`model.chat.key`、`model.embedding`、`vector.milvus`、`agent.model`、`mcp.server`、`mcp.client`、`safety.engine`、`rag.rerank`、`runtime.timezone`、`structured-decision`、`storage.domain`、`crypto.key`；其余项进 C 段增量 |
| §12.5 会话状态 | `RUNNING`/`AWAITING_CONFIRM`/`IDLE`/`STOPPED` 四态落库 | `ai_session.status` 为 char：`0` 进行中 / `1` 已结束；未决确认只在 Redis 侧推导不回写；「已终止」在 `ai_route_run.status=aborted` 与该轮消息 `status=3`（已中断）上体现 |
| §12.5 权限 | `ai:session:governance:list`/`:terminate`/`:delete`/`:export` | 终止走 `POST /ai/chat/session/{sessionId}/stop`（类级 `ai:chat:send`，本人会话语义）；Redis 会话状态回收走 `ai:session:state:reap`；`ai:session:governance:*` 不引入 |
| §12.5 导出 | CSV/XLSX 落 OSS + 浏览器预签名 120 秒 | 导出为响应流直接下载（`ExcelBuilder` → `toResponse`），不落对象存储、不发预签名 URL；§12.5 的「留档窗口」口径随存储侧改动一并作废 |
| §12.5 Reaper | 停留进行中 > `stale-minutes=60` 置 `STOPPED`，原因码 `STALE_REAPED` | 实际语义是**Redis 会话状态回收**：闲置 ≥ `session-state-idle-days=30` 天清除会话状态键（每 24h 一轮、首批 200×20、启动延迟 15 分钟），不改写 `ai_session.status`，无 `STALE_REAPED` |
| §12.4 预检 | dry-run 参数 + 二次确认 + 理由 | 预检由保留配置查询接口返回各表超期行数承担（等价 dry-run，无独立 `dryRun` 参数）；权限为 `ai:audit:route:query` / `ai:audit:retention:edit` / `ai:audit:retention:cleanup`，`:view`/`:purge`/`:unlimited` 不引入 |
| §12.4 删除顺序 | 先 `ai_route_expert_call` 再 `ai_route_run` 保证引用一致 | 实现顺序为 `ai_trace`→`ai_tool_call_log`→`ai_permission_decision_log`→`ai_agent_task_run`→`ai_route_run`→`ai_route_expert_call`。40 表无外键，且父记录早于截止日、子记录晚于截止日时两种顺序都留下孤立子记录——**顺序并不产生正确性**，真正的口径是 C 段的按父记录联动删除 |
| §12.6 附件生命周期 | `status=PENDING/BOUND/ORPHANED/RECYCLED` + `expire_at = 上传 + 72h` | `ai_message_attachment` 无 `status`/`expire_at` 列；暂存件判定为 `message_id IS NULL`，TTL 由 `pending-attachment-ttl-hours=24` 决定，回收任务每 1h 一轮（非每日 04:00），手动回收入口 `POST /ai/chat/attachment/reclaim`（`ai:chat:attachment:reclaim`） |
| §12.2 版本来源 | `versions/{ts}-PRE_MERGE.md`、写 `ROLLBACK_POINT` 快照 | 来源词表为 `PRE_MANUAL`/`MANUAL`/`PRE_CONSOLIDATE`/`CONSOLIDATE`/`RESTORE`；合并前置快照即 `PRE_CONSOLIDATE`，恢复前置快照即 `RESTORE`；间隔与保留期（1440/30 分钟、90/365/180 天、自动归档开）与原裁定一致 |

#### C. 增量补齐项（契约没错、实现欠账，按 §7.3 排期，不许口头划掉）

| 编号 | 条目 | 缺口 | 验收口径 |
|---|---|---|---|
| I-1 | §12.3「40 表齐备」 | `REQUIRED_TABLES` 只列 4 张 | 已交付：健康项 `db.schema` 的待检清单改由实体 `@TableName` 提供（`AiHealthCheckServiceImpl.expectedAiTables()`，只认带显式注解的实体——`AiAgent` 无注解，按类名推导会去查一张并不存在的 `ai_agent`），与库内 `getTables` 做差集并按名字报缺表；`e2e/http/L00-contract-consistency.mjs` F 段断言「DDL 表集合 = 实体 `@TableName` 集合 = Mapper 覆盖集合」，5 项漂移变异（改 DDL 表名 / 实体漏注解 / 实体无 Mapper / 双方言多一张无实体表 / 注解指向不存在的表）全部变红 |
| I-2 | §12.3 结果缓存 60 秒 | 无缓存，每次点刷新都全跑 | 已交付：`check()` 先读 Redis 键 `agenthub:ai:health:report`（值为报告 JSON，TTL 60 秒，写法与本模块 `AiSensitiveWordServiceImpl` 的缓存惯例一致：JSON 串入库而不是靠 Redisson 编解码器保对象图）；实时体检后覆盖并重置 TTL，因此连续点刷新不会把 Redis 打穿。`GET /ai/health/check?refresh=true`（页面「重新自检」按钮）穿透缓存重算；读缓存与写缓存各自 try/catch——Redis 抖动或缓存值被写坏都只当作没缓存，不改「永不 500」。`AiHealthCheckServiceImplTest` 5 项断言 + 6 项变异（TTL 改 300 秒 / 缓存读取短路 / 忽略 refresh / 读缓存异常不兜底 / 写缓存异常不兜底 / Controller 丢参）全部变红 |
| I-3 | §12.3 WARN 项补齐 | 对象存储读写删、BM25 就绪、渠道长连接、磁盘与 Redis 余量、Reaper 心跳、向量化积压、近 24h ERROR 日志 | 每项独立 `WARN` 判定，接不进的一律记 `UNKNOWN` 而非 `PASS` |
| I-4 | §12.3 触发时机 | 日报生成后不触发体检 | 08:00 日报落库后串一次体检，结果写运行健康 |
| I-5 | §12.4 保留期下限 | BO 只有 `@Min(0) @Max(3650)`，无 30 天下限 | `audit-days` 非 0 时 ≥30；0 视为不限并需 `ai:audit:retention:unlimited` + 菜单脚本登记 |
| I-6 | §12.4 分批删除 | 单条 `delete from … where create_time < cutoff` 全量删 | 按 `create_time` 索引分片，单事务 ≤5000 行循环，逐批累计行数写清理记录 |
| I-7 | §12.4 引用一致 | 跨截止日的子记录会成孤立引用 | 子记录按父记录联动删除（`join` 父表 `create_time`），不再依赖删除顺序 |
| I-8 | §12.5 并发与保护 | 对进行中会话直接删除无 409；无会话级锁 + 状态 CAS | 删除进行中会话返回 409 并引导先终止；重复终止幂等返回 |
| I-9 | §12.5 治理面留痕 | 操作他人会话不必填理由 | 跨本人范围的操作必填理由并落底座 `sys_oper_log` |
| I-10 | §12.6 附件状态机 | 无 `status`/`expire_at` 列，无单用户配额（≤20 个 / ≤50MB） | 双方言加列（含字典 `ai_attachment_status` 回补）+ 配额校验 + 回收优先最旧 `PENDING`；此项完成后 L00 的状态字典对账一并覆盖 |
| I-11 | §12.2 版本恢复权限 | 回滚复用 `ai:memory:edit`，无 `ai:memory:version:restore`、无二次确认 | 独立权限 + 二次确认 + 操作日志，验收仍是「正文与目标版本逐字一致」 |
| I-12 | 底座示例菜单的空挂载 | 基线 `sys_menu` 里 7 个页面级菜单（`ai/chat`、`tool/gen`、`monitor/admin`、`monitor/snailjob`、`monitor/snailai`、`demo/demo`、`demo/tree`）在本项目没有对应前端页面：底座自带的 `ruoyi-ai` 与 `ruoyi-demo`/代码生成模块按 D1 备注裁掉，`agent-hub-ai` 的工作台另有自己的命名空间。**点开这些菜单是白屏**，且底座基线脚本不改，删除只能由我方脚本承担 | 新增 `script/sql/{dialect}/agenthub-base-menu-prune.sql`（按 `component` 命中删除，连带 `sys_role_menu` 授权，幂等），initdb 序号排在基线之后；`menu-component` 门禁把覆盖面对象扩到全部 `sys_menu` 行。**属产品面裁剪（去掉底座示例功能），待队长批准后再落** |
| I-13 | 忽略规则吃掉源码 | 仓库根 `.gitignore` 的未锚定 `data/` 把前端 `src/api/system/dict/data/` 整目录挡在仓外，构建期才暴露（`utils/dict.ts` 的 `getDicts` 解析失败），AI 五个状态字典的渲染同链路依赖它 | 已修：模式锚定为 `/data/`，模块补回，并落 `scripts/checks/gitignore-hygiene.mjs`（未锚定的目录级模式 + 源码被忽略清单命中即红）为必过步骤 |
| I-14 | §12.3 单项超时与总预算 | 全量自检串行跑 15 项，只有 `connection.isValid(2)` 一处显式超时；单项没有 3 秒上限、整屏没有 30 秒预算，某个卡住的检项（如元数据查询、OSS 域名解析）会把「进页面」拖成白屏 | 单项判定 3 秒超时、整体 30 秒预算；超时项记 `UNKNOWN` 并写明「判定超时」，其余项照常返回，页面不因超时降级为报错。I-2 交付时发现，按 §7.3 排期 |
| I-15 | §12.3 检项取数的可见范围 | I-2 把报告全局缓存 60 秒之后，`agent.model` 检项仍在调 `queryOptions()`，而后者按登录用户的 `visible_scope` 与部门过滤专家。结果是谁先点刷新，谁的视野就被缓存 60 秒串给下一个人：同一个坏模型在 A 眼里报警、在 B 眼里正常，且报错内容带着别人的专家名 | 已交付：新增 `IAiAgentService.queryPlatformOptions()`——只按 `status` 取入口与全部启用专家，不读 `LoginHelper`；自检的 `agent.model` 改用它，页面下拉与授权面继续用 `queryOptions()`。落点是必过单测：`AiAgentServiceImplTest` 对平台视图同时断言「没碰登录上下文」「SQL 片段不含 `visible_scope`」「查询参数值里不出现用户/部门主键」，`AiHealthCheckServiceImplTest` 断言他部门专家出现在结论里且 `queryOptions()` 不再被调用；3 项变异（自检改回 `queryOptions()` / 平台视图偷偷加 `visible_scope` / 平台视图又读 `LoginHelper`）全部变红 |

I-1/I-2/I-5/I-6/I-12 属门禁可捕获项，完成时按 AGENTS.md 的三步路径落 `scripts/checks/{scope}.mjs` → `ci.yml` 必过步骤 → §3.2 命名表定位，不允许只在本表写「必须」。I-1 的对账断言落在**既有**必过步骤 L00 的 F 段（该脚本已在 §3.2「契约」行与 `ci.yml` 登记为必过），因此不新增脚本文件；落点变化不放宽任何既有条目。 I-2 的落点是**必过单测**（`mvn test` 已在 `ci.yml` 的 `verify` 里）加 6 项变异验证：缓存复用、TTL 与穿透都是运行期行为，静态脚本只能自证「常量存在」，写在 `scripts/checks/` 里反而是一条不会报警的假门禁，因此不新增脚本文件。I-15 同理落在必过单测加 3 项变异——「检项有没有按人过滤」要看一次真实调用的取数路径，静态扫源码既抓不住改调 `queryOptions()`，也躲不开别名的包装方法。三处落点都不放宽任何既有条目。

---

## 13. 下一步

本版为执行基线（一周全量 + 队长单线全量交付 + 部署形态与云端 Agent 边界），分工变更见 §8.1。

**已产出**：

- `docs/superpowers/plans/2026-11-20-week1-collaboration-plan.md`——交付节拍与验收计划（分支与 PR 协作、契约冻结流程、D1~D5 逐日节拍与验收、联调依赖矩阵、质量门禁、发布回滚）。原按三人排班书写，责任人自 D11 重冻起统一为队长单线执行，任务范围与验收标准不变。
- `docs/superpowers/plans/2026-11-20-week1-daily-log.md`——逐日执行日志（D3 记录分工取消后的三面 PR 交付与 §12.7 契约重冻裁决）。
- 代码交付分三面开 PR：后端全树、前端全树、契约与门禁重冻（本文件 §6/§12.7 的机械执行面）。

**待产出**：不再按三条任务线拆计划文件——单线交付下由 §7.1 一周交付清单 + §12.7 C 段增量表直接驱动，逐条开 `feature/<scope>` 分支与 PR。

接口与事件类型以本文件为唯一来源；任何取值、命名与状态口径的争议，先查 §12.7 的效力修正，再看 `e2e/http/L00-contract-consistency.mjs` 的对账结果。
