# AgentHub 配置与部署口径

> 长期文档（设计稿 §3.2「文档」行固定名）。范围只到「运行期配置怎么进容器、双栈怎么起、密钥放哪」；
> 架构与契约以[最终设计稿](superpowers/specs/2026-11-20-agenthub-decomposition-design.md)为准，交付节拍见[一周协作计划](superpowers/plans/2026-11-20-week1-collaboration-plan.md)。

## 1. 双栈端口与暴露面

| 栈 | 目录 | 对外（Nginx） | 后端 | 数据库 |
|---|---|---|---|---|
| PostgreSQL | `deploy/deploy-pg` | `80` / `443` | `8080` | 栈内 `db:5432`，不发布 |
| MySQL | `deploy/deploy-mysql` | `81` / `8443` | `8081` | 栈内 `db:3306`，不发布 |

两栈在同一台云服务器并存，靠宿主端口区分入口。Redis、etcd、MinIO、Milvus 一律只在栈内网络可达（§2.3.9 公网暴露收敛），进公网的只有 Nginx。`APP_HOST_PORT` 的后端映射是留给开发机直连的，生产按防火墙只放行内网。

`GET /ai/health/check` 需要 `ai:health:check` 鉴权，因此**不能**作为无鉴权探针：容器健康检查只做 TCP 端口就绪，Nginx 侧的 `/healthz` 是纯静态 `200 agenthub[-mysql]-nginx-ok`，只证明 TLS 与入口在跑。

## 2. 运行期配置：三段式，底座 yml 一行不改

底座 6.0.0 的 `application-prod.yml` 把数据库写成 `localhost:3306/ry-vue` + `root/root`、Redis 写成 `localhost:6379` + `ruoyi123`，`sa-token.jwt-secret-key` 与 `api-decrypt` 的 RSA 公私钥都是仓库内明文。直接用它等于带着仓库私钥上线。本项目分三段处理：

1. `deploy/{stack}/.env`（不入库）——唯一的值来源，含口令、密钥、根密钥。
2. `deploy/{stack}/docker-compose.yml` 的 `x-app-environment`——把 `.env` 注入为 `AGENTHUB_*` 环境变量；变量名一律 §3.1 的 `AGENTHUB_*` 大写蛇形，上游镜像强制的名字（`POSTGRES_*`/`MYSQL_*`/`REDIS_PASSWORD`/`MILVUS_PASSWORD`/`MINIO_ROOT_*`）是例外。
3. `deploy/{stack}/config/application-agenthub.yml`——挂载进容器 `/config`，把 `AGENTHUB_*` 映射回**底座已有的属性路径**（`spring.datasource.dynamic.datasource.master.*`、`spring.data.redis.*`、`sa-token.jwt-secret-key`、`api-decrypt.*`）。

AgentHub 自有的配置项（模型密钥根密钥、安全守卫模式、向量库与对象存储入口）不借用底座键名，由 `agent-hub-ai` 侧属性类声明后读取，因此不出现在映射文件里。

### 覆盖优先级实测

容器同时设置：

```sh
SPRING_PROFILES_ACTIVE=prod,agenthub
SPRING_CONFIG_ADDITIONAL_LOCATION=optional:file:/config/
```

用真实底座 `application.yml` + `application-prod.yml` 与 spring-boot 4.1.1 探针程序验证（探针只读 `Environment`，不起业务）：

| profiles | 数据源 URL | Redis 口令 | `sa-token` JWT | `api-decrypt` 私钥 | 底座 `hikari.maxPoolSize` |
|---|---|---|---|---|---|
| `prod,agenthub` | `jdbc:postgresql://db:5432/agenthub` | `.env` 值 | `.env` 值 | `.env` 值 | `20`（保留） |
| `prod`（对照） | `jdbc:mysql://localhost:3306/ry-vue?...` | `ruoyi123` | 底座默认 26 字符 | 仓库内 848 字符默认私钥 | `20` |

两条结论：额外挂载目录压过 classpath 的 profile 文件；且是**按键合并**而非整份替换，底座其余配置照常生效。实测中 profile 顺序不敏感（`agenthub,prod` 同样压得住），但交付口径仍固定写 `prod,agenthub`，不把「恰好可用」当契约。

另外底座 `application.yml` 的 `spring.profiles.active: @profiles.active@` 是 Maven 构建期替换值，运行期改它只能靠 `SPRING_PROFILES_ACTIVE` 环境变量覆盖——这也是映射文件必须走 profile 而不是重新打包的原因。

## 3. 数据库初始化链

两栈的 `db` 服务用容器官方 initdb 目录，按文件名字典序执行：

| 序号 | 文件 | 内容 |
|---|---|---|
| `10` | `${RY_BASE_SQL}` | 底座基线（`sys_menu`/`sys_dict_*` 等真表与自带数据），B 线导入底座后落在 `code/back-end/script/sql/...` |
| `20` | `agenthub-ai-ddl.sql` | 40 张 `ai_*` 表 |
| `30` | `agenthub-ai-seed.sql` | 内置只读工具、示例模型、33 字典类型 / 127 字典项 |
| `40` | `agenthub-ai-menu.sql` | 34 页菜单 + 98 按钮权限标识（132 个 `ai:{模块}:{动作}`） |

`RY_BASE_SQL` 留空时挂 `/dev/null` 跳过——只有 `sys_menu`/`sys_dict_*` 不存在时 `30`/`40` 会报错，此路径仅用于单测 `ai_` 表，不是可用栈。

### MySQL 必须挂 `conf.d/agenthub.cnf`

官方镜像的 initdb 对每个脚本另起一个 `mysql` 客户端进程，不带 `--default-character-set` 时按 latin1 解析连接，UTF-8 中文被当 3 个字符，底座基线在 `sys_user.nick_name varchar(30)` 处报 `ERROR 1406 Data too long`，整条链就此中断（`ai_` 表一条都建不起来）。因此 `db` 服务挂载：

```yaml
- ./conf.d/agenthub.cnf:/etc/mysql/conf.d/agenthub.cnf:ro   # [client] default-character-set = utf8mb4
```

服务端字符集仍由 `command:` 显式给（`--character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci`），映射文件只管 `[client]`，两处不重复定义。

## 4. 校验口径（已实测项）

| 项 | 方式 | 结果 |
|---|---|---|
| 40 表双方言幂等 | 底座真表 + 我方三脚本 × 两遍 × PG14/MySQL8.4，`ON_ERROR_STOP=1` | 两遍 0 错 |
| 132 权限标识 | Java 常量 ↔ PG/MySQL 真表 `perms` 三向对照 | 缺失 0 多余 0，双方言差异 0 |
| 菜单/字典口径 | `is_frame`/`is_cache` = `N`/`Y`；审计字段 = 底座种子雪花 ID | 138 / 33+127 行全部对齐 |
| 真实部署 initdb | `docker compose up -d db` 各栈 | PG 与 MySQL 均 40 表 / 132 perms / 中文无损 |
| 同源入口 | Nginx 前置一个每 1.2s 吐一帧的 SSE 假后端 | 帧间隔 2.39s（未被缓冲）、80→443 308、`actuator` 403、`index.html` 不缓存、hash 资源缺失 404、`/mcp` 到后端 |
| 部署门禁有效性 | 对 `deploy-static.mjs` 做 8 项变异（删指令、改端口、删清单变量、暴露中间件端口、删字符集前置、退化 profile、删映射键、删文件） | 8/8 如期变红且报错命中对应断言；无 docker 时渲染记为「未执行」而非静默通过 |

两栈各跑一遍上述入口用例，探针串分别为 `agenthub-nginx-ok` / `agenthub-mysql-nginx-ok`，用于区分实际应答方。

## 5. 密钥与门禁

- 模型密钥密文落库、不回显；根密钥 `AGENTHUB_AI_CRYPTO_KEY` 只在 `.env`。
- `.env`、`deploy/*/cert/`、`vendor/` 全部 gitignore；证书文件不得入仓（§2.3.1）。
- 仓库内不放任何真实口令：`scripts/checks/no-secrets-in-repo.mjs` 扫描跟踪文件。
- 生产启动必须命中「非出厂示例值」检查，命中即拒绝启动（手册 §10.2 第 1、4 项），该门禁随 A2 落地。
- 未达项只能按 §7.3 由队长当日裁定并记入[每日执行日志](superpowers/plans/2026-11-20-week1-daily-log.md)，不允许口头砍需求。

## 6. 已知未决

1. `app` 服务的镜像构建依赖 B 线在 `code/back-end` 落 `Dockerfile`；本编排只约定 `/config` 挂载点与 `8080`/`8081` 监听，不改这条就起不了 app。
2. PG 栈起不来的根因在底座侧：`ruoyi-admin/pom.xml` 的 `org.postgresql:postgresql` 是注释态，B 线导入时必须放开（见 D1 日志）。
3. 两栈各自带一套 Milvus/etcd/MinIO，同机并存的内存预算要在 §11 前置里确认；若余量不足，改为共享外部网络与卷属部署变更，需队长批准。
4. `deploy/**` 的改动由 CI 两条门禁兜住：`node scripts/checks/deploy-static.mjs`（端口口径、`.env.example` 与 compose 占位符对齐、Nginx 关键指令、MySQL 字符集前置、profile 覆盖接线，docker 在场时追加一次真实渲染）与「Nginx 入口配置语法」（现签一次性自签证书后跑 `nginx -t`，必须带 `--add-host app:127.0.0.1`，否则 upstream 解析在测试阶段就失败）。两条都随 `deploy/deploy-pg/docker-compose.yml` 是否入仓自动启停，不起容器、不连真库；行为类实测口径见上表。
5. 本地复跑校验脚本时，挂载目录要用可被容器引擎访问的路径：macOS 上 `mktemp -d` 落在 `/var/folders/...`，挂进容器后是空目录，会误报成证书缺失。CI 侧走 `RUNNER_TEMP`，无此问题。
