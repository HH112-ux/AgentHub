# AgentHub

AgentHub 是一套**可运营、可审计、可控、可评测**的企业级智能业务运行平台。项目在成熟开源中后台底座 [RuoYi-Vue-Plus](https://gitee.com/dromara/RuoYi-Vue-Plus) 之上，以 **AgentScope Java 2.0.4** 为智能体内核，采用 Hub-and-Spoke 编排：入口智能体负责意图判定与专家路由，领域专家在授权范围内调用工具、知识库和技能完成作业，重要写操作由人工确认并全程留痕。

真实系统采用 **Java 21 / Spring Boot / Vue 3**，部署在云服务器上，由 Nginx 同源提供前端静态产物与 API。仓库中的 `code/intro-site` 是独立的单文件产品介绍页，用于公开介绍与体验，不承载真实业务页面，也不接触业务数据。

## 核心能力

- **6 大能力域、34 个功能页、98 个按钮权限**：智能体、模型、知识库、工具集成、审计观测、安全治理。
- **智能体运行**：意图路由、专家协作、流式响应、停止生成、会话恢复。
- **知识与记忆**：向量 / 混合检索、Rerank、长期记忆、技能仓库、版本化恢复。
- **工具与集成**：MCP 双向集成、定时任务、多渠道接入、场景包与工具白名单。
- **治理与审计**：输入 / 输出 / 工具结果 / 知识库入库 / MCP 出口全链路过审，六类审计留痕。
- **双数据库脚本**：PostgreSQL 与 MySQL DDL、种子、菜单脚本保持一致且幂等。

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vue 3、TypeScript、Vite、Element Plus、Pinia、Vue Router |
| 后端 | Java 21、Spring Boot 4.1.1、MyBatis-Plus、Sa-Token、Redisson |
| 智能体 | AgentScope Java 2.0.4、MCP Java SDK 0.17.2 |
| 数据与存储 | PostgreSQL 14 / MySQL 8.4、Redis 7、Milvus 2.5、MinIO、etcd |
| 部署 | Docker Compose、Nginx、双栈编排与静态门禁 |
| 介绍页 | 单文件 HTML / CSS / JavaScript，零外部运行依赖 |

## 仓库结构

```text
.
├── code/
│   ├── back-end/          # Spring Boot 多模块后端
│   ├── front-end/         # Vue 3 管理台与对话工作台
│   └── intro-site/        # 公开产品介绍页（单文件）
├── deploy/
│   ├── deploy-pg/         # PostgreSQL 生产栈
│   └── deploy-mysql/      # MySQL 对照栈
├── docs/                  # 配置说明、最终设计稿、协作与验收记录
├── e2e/http/              # 接口与契约一致性检查
├── scripts/checks/        # CI 静态门禁
├── 开发总览手册.md         # 功能需求与工程总纲
├── AGENTS.md              # 仓库协作规则
├── CONTRIBUTING.md        # 贡献指南
├── SECURITY.md            # 安全策略
└── LICENSE                # MIT
```

## 快速开始

### 方式一：Docker Compose 全栈启动（推荐）

前置条件：Docker、Docker Compose、至少 8GB 可用内存。

```bash
# 先构建前端静态产物，Compose 会直接挂载 dist
cd code/front-end
pnpm install
# 本地演示需注入成对 RSA 密钥；生产环境必须改为服务端解密，不要把私钥打进浏览器产物
export VITE_APP_RSA_PUBLIC_KEY='your-base64-public-key'
export VITE_APP_RSA_PRIVATE_KEY='your-base64-private-key'
pnpm build

cd ../../deploy/deploy-pg
cp .env.example .env
# 编辑 .env，替换所有 REPLACE_WITH_* 占位符
docker compose up -d --build
```

启动后访问 `http://localhost`。Nginx 同源提供前端与 API；数据库、Redis、Milvus、MinIO、etcd 仅在 Compose 内网可达。

MySQL 对照栈位于 `deploy/deploy-mysql`，使用 `81/8443` 与 `8081` 端口，可与 PostgreSQL 栈临时并存。真实环境按当前裁定只常驻 PostgreSQL 栈。

### 方式二：前端本地开发

前置条件：Node.js 22.14+、pnpm 10.34.5。

```bash
cd code/front-end
pnpm install
pnpm dev
```

开发服务器默认使用 `/dev-api` 代理，请确保后端或全栈服务已启动。前端 RSA 密钥通过未入库的 `code/front-end/.env.local` 注入；生产环境不要将私钥放入浏览器构建，应改为服务端解密。

### 方式三：后端本地开发

前置条件：JDK 21、Maven Wrapper、PostgreSQL、Redis。

```bash
cd code/back-end
./mvnw -pl ruoyi-admin spring-boot:run
```

开发 profile 的数据库与 Redis 口令通过环境变量注入：

```bash
export AGENTHUB_DEV_DB_PASSWORD='your-local-db-password'
export AGENTHUB_DEV_REDIS_PASSWORD='your-local-redis-password'
# 仅本地联调：允许沿用出厂示例密钥；生产环境禁止使用 warn
export AGENTHUB_SECURITY_GUARD_MODE='warn'
```

本地联调还需提供有效的 `API_DECRYPT_PUBLIC_KEY`、`API_DECRYPT_PRIVATE_KEY`、`SA_TOKEN_JWT_SECRET_KEY`、`AGENTHUB_AI_CRYPTO_KEY` 与 `MILVUS_PASSWORD`。这些值应放在未入库的 `.env.local` 或本地密钥环境中；`warn` 只放行出厂示例值检测，不能把占位符变成可用密钥。

完整配置项、容器覆盖顺序和双栈端口见 [配置与部署口径](docs/configuration.md)。

## 验证

### 仓库静态门禁

```bash
for script in scripts/checks/*.mjs; do
  node "$script" || exit 1
done
```

覆盖双方言 SQL 一致性、审计保留、会话治理、附件生命周期、记忆恢复、命名规范、密钥扫描、文档链接、gitignore、菜单对齐和部署编排。

### 后端测试

```bash
cd code/back-end
./mvnw -pl ruoyi-modules/agent-hub-ai -am test
```

### 前端检查与构建

```bash
cd code/front-end
pnpm lint
pnpm build
```

### 介绍页问答 Function

```bash
node --test code/intro-site/tests/chat.test.mjs
```

### 接口契约一致性

栈运行后执行：

```bash
node e2e/http/L00-contract-consistency.mjs
```

CI 配置位于 [.github/workflows/ci.yml](.github/workflows/ci.yml)，Pull Request 模板位于 [.github/pull_request_template.md](.github/pull_request_template.md)。

## 文档入口

- [开发总览手册](开发总览手册.md)：功能需求、模块、数据模型、接口、测试与部署总纲。
- [最终设计稿](docs/superpowers/specs/2026-11-20-agenthub-decomposition-design.md)：架构、边界、契约与工程切片。
- [配置与部署口径](docs/configuration.md)：环境变量、双栈端口、初始化链、镜像与密钥边界。
- [一周协作计划](docs/superpowers/plans/2026-11-20-week1-collaboration-plan.md) 与 [每日执行日志](docs/superpowers/plans/2026-11-20-week1-daily-log.md)：交付记录与验收证据。
- [开源许可边界](docs/open-source-licenses.md)：底座与项目自有能力的许可说明。

## 安全

仓库为 public，**不得提交真实密钥、证书、`.env`、本地绝对路径或业务数据**。生产密钥通过部署环境或密钥管理系统注入；模型密钥在页面内密文保存，日志与 CI 输出不回显机密。Vite 的 `VITE_*` 变量会进入浏览器产物，因此 RSA 私钥只能用于本地联调，生产环境必须改为服务端解密。

如发现安全漏洞，请遵循 [SECURITY.md](SECURITY.md) 通过私密渠道报告，不要创建公开 Issue。

## 许可

本项目整体采用 [MIT License](LICENSE)。底层 RuoYi-Vue-Plus 的许可与版权声明保留在对应模块中，详见 [开源许可边界](docs/open-source-licenses.md)。
