# AgentHub 配置与部署口径

> 长期文档（设计稿 §3.2「文档」行固定名）。范围只到「运行期配置怎么进容器、双栈怎么起、密钥放哪」；
> 架构与契约以[最终设计稿](superpowers/specs/2026-11-20-agenthub-decomposition-design.md)为准，交付节拍见[一周协作计划](superpowers/plans/2026-11-20-week1-collaboration-plan.md)。

## 1. 双栈端口与暴露面

| 栈 | 目录 | 对外（Nginx） | 后端 | 数据库 |
|---|---|---|---|---|
| PostgreSQL | `deploy/deploy-pg` | `80` / `443` | `8080` | 栈内 `db:5432`，不发布 |
| MySQL | `deploy/deploy-mysql` | `81` / `8443` | `8081` | 栈内 `db:3306`，不发布 |

两栈在同一台云服务器并存，靠宿主端口区分入口。Redis、etcd、MinIO、Milvus 一律只在栈内网络可达（§2.3.9 公网暴露收敛），进公网的只有 Nginx。`APP_HOST_PORT` 的后端映射是留给开发机直连的，生产按防火墙只放行内网。

`GET /ai/health/check` 需要 `ai:health:query` 鉴权，因此**不能**作为无鉴权探针：容器健康检查只做 TCP 端口就绪，Nginx 侧的 `/healthz` 是纯静态 `200 agenthub[-mysql]-nginx-ok`，只证明 TLS 与入口在跑。

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
| `10` | `script/sql/base/{dialect}/ruoyi-vue-base.sql` | 底座系统基线：`sys_menu`/`sys_dict_*`/`sys_user` 等真表与自带数据 |
| `11` | `script/sql/base/{dialect}/ruoyi-job-base.sql` | 任务调度表（`schedule_job` 等） |
| `12` | `script/sql/base/{dialect}/ruoyi-workflow-base.sql` | 工作流表 |
| `20` | `script/sql/{dialect}/agenthub-ai-ddl.sql` | 40 张 `ai_*` 表 |
| `30` | `script/sql/{dialect}/agenthub-ai-seed.sql` | 内置只读工具、示例模型、31 字典类型 / 108 字典项 |
| `40` | `script/sql/{dialect}/agenthub-ai-menu.sql` | 139 行菜单（7 目录 + 34 页面 + 98 按钮）= 131 个 `ai:{模块}:{动作}` 权限标识 |

底座基线随仓库存放在 `script/sql/base/`，compose 里按序号写死挂载（`deploy/deploy-{pg,mysql}/docker-compose.yml`），不再走外部路径变量：基线一旦缺失，`30`/`40` 因缺 `sys_menu`/`sys_dict_*` 直接报错，栈就起不来——把「缺文件」做成运行期变量等于把联调阻塞留到起栈那一刻。要换基线版本请直接改这三个挂载项（走 PR）。

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
| 40 表清单三方对齐 | `e2e/http/L00-contract-consistency.mjs` F 段：实体 `@TableName` ↔ 双方言 DDL ↔ Mapper 覆盖 | 40 张逐一命中，5 项漂移变异全红。运行期健康项 `db.schema` 用同一份实体清单与 `getTables` 求差集，缺表直接报名字（不再写死「四张核心表」） |
| 131 权限标识 | 注解与常量类 ↔ 双方言菜单脚本对账（`e2e/http/L00-contract-consistency.mjs`） | 注解 ⊆ 菜单 = 常量，双方言差异 0 |
| 菜单/字典口径 | `is_frame`/`is_cache` = `N`/`Y`；审计字段 = 底座种子雪花 ID | 139 菜单行 / 31+108 字典行 |
| 状态字典 ↔ 实体常量 | **机械对账已按队长 2026-11-21 指示撤除**（原 `e2e/http/L00-contract-consistency.mjs` E 段：5 个 `ai_*_status` 取值与实体 `STATUS_*` 双向比对，另断言取值不重复、词表不空、字典项不孤立） | 撤除前后各跑过一次同一条漂移：把 mysql 种子 `ai_route_run_status` 的 `pending` 改成 `Pending`，带 E 段 rc=1 报两条，删掉后 rc=0 通过——这类漂移今后不会报警，改字典要人工对照设计稿 §12.7 A 段的表（31 类 / 108 项种子与双方言一致性本身不变）。台账见 §12.7 I-16 |
| 体检报告 60 秒缓存 | `AiHealthCheckServiceImplTest` 5 项断言（缓存复用、TTL=60s、`refresh=true` 穿透、缓存值损坏回退实时、写缓存失败降级）+ 6 项变异：TTL 改 300 秒、缓存读取短路、忽略 refresh、读/写缓存异常不再兜底、Controller 丢掉 refresh 参数 | 6/6 如期变红且报错命中对应断言；缓存键 `agenthub:ai:health:report`，每次实时体检覆盖并重置 TTL，页面「重新自检」不受 60 秒限制 |
| 体检检项取数视野 | `AiAgentServiceImplTest` 平台全量视图三重断言（未碰登录上下文、SQL 片段不含 `visible_scope`、查询参数值里不出现用户与部门主键）+ `AiHealthCheckServiceImplTest` 他部门专家出现在结论里且 `queryOptions()` 不再被调用 | 3/3 变异（自检改回 `queryOptions()`、平台视图偷偷加 `visible_scope`、平台视图又读 `LoginHelper`）如期变红。报告按 §12.3 全局缓存 60 秒，检项取数因此必须与登录用户无关，否则某个人的视野会串给下一个人 |
| 体检超时与整屏预算（§12.7 I-14） | `AiHealthItemTimeoutInvokerTest` 4 项（检项跑在工作线程、到点抛 `TimeoutException` 且调用方不陪跑、超时后 `cancel(true)` 中断工作线程、检项异常原样解包）+ `AiHealthCheckServiceImplTest` 新增 4 项（单项超时记 `UNKNOWN` 并写明「判定超时」、单项预算取 3 秒上限与整屏剩余的较小值、30 秒预算耗尽后剩余项不再执行、每行按真实耗时回填 `elapsedMs`）+ 8 项变异：单项上限放宽到 300 秒、取消取小、预算耗尽仍执行、超时落到 FAIL 分支、整屏预算改 60 秒、耗时不回写、超时不中断工作线程、异常不解包 | 8/8 如期变红且报错命中对应断言；硬超时要把检项搬到工作线程，前提是该线程没有登录上下文——`AiHealthItemInvoker` 的类注释写明这条约束由 I-15 的平台全量视图守住，本模块 ai_ Mapper 也不带 `@DataPermission` |
| 真实部署 initdb | `docker compose up -d db` 各栈 | PG 与 MySQL 均 40 表 / 131 perms / 中文无损 |
| 同源入口 | Nginx 前置一个每 1.2s 吐一帧的 SSE 假后端 | 帧间隔 2.39s（未被缓冲）、80→443 308、`actuator` 403、`index.html` 不缓存、hash 资源缺失 404、`/mcp` 到后端 |
| 部署门禁有效性 | 对 `deploy-static.mjs` 做 17 项变异：编排与 Nginx 8 项（删指令、改端口、删清单变量、暴露中间件端口、删字符集前置、退化 profile、删映射键、删文件）+ 镜像接线 9 项（compose 去 `SERVER_PORT`、`ENTRYPOINT` 不传端口、运行期镜像改 `-jre-alpine`、构建镜像 JDK 降 17、构建命令去 `-am`、`ruoyi-admin` 不依赖 `agent-hub-ai`、`/app/data` 退化 bind、删 `.dockerignore` 排除项、删 `Dockerfile`） | 17/17 如期变红且报错命中对应断言（判定按「rc=1 且报错含该断言措辞」，脚本自身崩溃不计通过）；无 docker 时渲染记为「未执行」而非静默通过 |

两栈各跑一遍上述入口用例，探针串分别为 `agenthub-nginx-ok` / `agenthub-mysql-nginx-ok`，用于区分实际应答方。

## 5. 密钥与门禁

- 模型密钥密文落库、不回显；根密钥 `AGENTHUB_AI_CRYPTO_KEY` 只在 `.env`。
- `.env`、`deploy/*/cert/`、`vendor/` 全部 gitignore；证书文件不得入仓（§2.3.1）。
- 仓库内不放任何真实口令：`scripts/checks/no-secrets-in-repo.mjs` 扫描跟踪文件。
- 生产启动必须命中「非出厂示例值」检查，命中即拒绝启动（手册 §10.2 第 1、4 项），该门禁随 A2 落地。
- 未达项只能按 §7.3 由队长当日裁定并记入[每日执行日志](superpowers/plans/2026-11-20-week1-daily-log.md)，不允许口头砍需求。

## 6. 已知未决

1. ~~`app` 服务的镜像构建依赖 B 线在 `code/back-end` 落 `Dockerfile`~~ 已交付：`code/back-end/Dockerfile` 两阶段构建，口径见 §7。仍待现场确认的是受限网络下的基础镜像通道（§7 末条）。
2. 底座 `ruoyi-admin/pom.xml` 的 PostgreSQL 驱动在 D1 日志里是注释态；现仓库内已放开，且与 MySQL 驱动同时在位、按 url 选择（`scripts/checks/deploy-static.mjs` 的镜像接线段会校验 ruoyi-admin 依赖 `agent-hub-ai`，但驱动在位与否仍以起栈实测为准）。
3. 两栈各自带一套 Milvus/etcd/MinIO，同机并存的内存预算要在 §11 前置里确认；若余量不足，改为共享外部网络与卷属部署变更，需队长批准。
4. `deploy/**` 的改动由 CI 两条门禁兜住：`node scripts/checks/deploy-static.mjs`（端口口径、`.env.example` 与 compose 占位符对齐、Nginx 关键指令、MySQL 字符集前置、profile 覆盖接线、镜像接线（§7），docker 在场时追加一次真实渲染）与「Nginx 入口配置语法」（现签一次性自签证书后跑 `nginx -t`，必须带 `--add-host app:127.0.0.1`，否则 upstream 解析在测试阶段就失败）。两条都随 `deploy/deploy-pg/docker-compose.yml` 是否入仓自动启停，不起容器、不连真库；行为类实测口径见上表。
5. 本地复跑校验脚本时，挂载目录要用可被容器引擎访问的路径：macOS 上 `mktemp -d` 落在 `/var/folders/...`，挂进容器后是空目录，会误报成证书缺失。CI 侧走 `RUNNER_TEMP`，无此问题。

## 7. 应用镜像构建与容器内端口

`docker compose --build` 一条命令产出 app 镜像，构建与运行分两阶段（`code/back-end/Dockerfile`）：

| 项 | 口径 | 为什么这样定 |
|---|---|---|
| 构建阶段 | `maven:3.9-eclipse-temurin-21` + `RUN --mount=type=cache,target=/root/.m2`，命令 `mvn -B -ntp -Dmaven.test.skip=true -pl ruoyi-admin -am package` | 底座自带 `ruoyi-admin/Dockerfile` 走「宿主先 `mvn package` 再 ADD jar」，换台机器要先装齐同版本 JDK；把 Maven 装进容器后版本口径只由 pom 的 `<java.version>` 决定。`-am` 必需：镜像本地仓库里没有同级模块，只 `-pl ruoyi-admin` 会解析失败。测试不在镜像里跑（属 CI 与起栈后的 e2e） |
| 运行阶段 | `eclipse-temurin:21-jre`，`WORKDIR /app` | compose 的容器探针是 `timeout 3 bash -c '</dev/tcp/127.0.0.1/<port>'`，`-jre-alpine` 里没有 bash 与 coreutils，探活会永远不绿 |
| 容器内端口 | `ENV SERVER_PORT` 由 compose 按栈注入（PG `8080` / MySQL `8081`），ENTRYPOINT 传 `-Dserver.port=${SERVER_PORT}` | 底座 yml 写死 8080；两栈同机并存时，MySQL 栈的 Nginx upstream 与健康探针都按 §1 的口径指 8081，容器不监听 8081 就是「nginx 502 + 容器不 healthy」的连环故障。三处（compose 环境 / nginx upstream / Dockerfile）由 `deploy-static.mjs` 的镜像接线段对齐 |
| 落盘目录 | `/app/logs`（logback `log.path=./logs`）与 `/app/data`（`workspace-root=./data/agent-workspace`）各挂命名卷 | 长期记忆的 `MEMORY.md`、抽取流水与版本快照是文件而不是表；不挂卷的话一次 `--force-recreate` 就把用户记忆清零，而且现场无法复现 |
| 上下文 | `code/back-end/.dockerignore` 排除 `**/target/` 与 IDE/系统文件 | 不排除时 64 MB 上下文里有 50 MB 是本地构建产物，每次构建重传一遍，`COPY` 进去的还是过期 jar |
| 探针与就绪 | 不用 `GET /ai/health/check`（带 `ai:health:query` 鉴权），改用 TCP 就绪 + Nginx `/healthz` | 见 §2 与 D2 日志：带鉴权的接口作容器探针只会把「未登录」当成启动失败 |

现场校验（本地与 CI 同一入口）：`node scripts/checks/deploy-static.mjs`。

受限网络下的已知偏差：Docker Hub 不可达时，`maven:3.9-eclipse-temurin-21`、`eclipse-temurin:21-jre`、`milvusdb/milvus:v2.5.5`、`quay.io/coreos/etcd:v3.5.14`、`minio/minio:RELEASE.2024-08-17T01-24-54Z` 都要经镜像站拉取后 `docker tag` 回正式名，编排文件本身不改；Maven 依赖走 `repo1.maven.org` 通常可直连，与本条无关。

镜像暂以 root 运行（与底座编排习惯一致，卷属主也最简单）；改非 root 属一周后硬化项，届时须同时验证两个命名卷的属主。
