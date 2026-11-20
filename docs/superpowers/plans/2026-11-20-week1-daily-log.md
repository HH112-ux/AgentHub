# 一周全量 · 每日执行日志

> 滚动追加，不改写历史。未达项按设计稿 §7.3 由队长当日 20:00 前裁定；不允许口头砍需求。

| 日 | 当日验收点 | 达成 | 未达项 | §7.3 降级裁定 | 契约变更 |
|---|---|---|---|---|---|
| D1 | 双栈可起可登录 / 40 表双方言幂等 / 模型连通 / echo 流打通 | 契约四件套已合并 `f9f6bd9`；40 表双方言对**底座真表**连跑两遍幂等 0 错；132 个 `ai:{模块}:{动作}` 与 Java 常量、双方言集合逐字一致；33 字典类型 / 127 字典项落库；底座 6.0.0 前后端制品就位 | 「可起可登录」「模型连通」「echo 流」三条依赖 B 线导入底座与 §11 模型密钥，D1 内未跑通 | 无（未触发 §7.3 任一客观降级条件；三条未达项均为前置依赖未落地，D2 首日补齐） | ①`ai:audit:retention:dry-run` → `ai:audit:retention:preview`（连字符无法映射 Java 常量名，§12 未规定该字面量）；②菜单与字典脚本的 `is_frame`/`is_cache` 域值由 `1`/`0` 改为底座 6.0.0 的 `N`/`Y`，审计字段 `create_dept`/`create_by`/`update_by` 改用底座种子雪花 ID。**perms 取值集合不变，132 标识与事件/原因码契约均未动** |

## D1 备注 · 底座 6.0.0 实测事实（B/C 线开线前必读）

队长已从官方发行通道取得两份制品放入 `vendor/`（已 gitignore，不入库）：

| 制品 | 大小 | sha256 |
|---|---|---|
| `vendor/base-back-end-v6.0.0.tar.gz` | 4.6 MB | `f69905d162e70f99e99ab15f05ad369a5bde52267ceb5c9821526b35e9c1f889` |
| `vendor/base-front-end-v6.0.0-vue.tar.gz` | 1.4 MB | `952fc6518c899b94191bb0eda32b34bc70214bf6d197a0827a516dad28dabfa4` |

解包后 `vendor/back-end`（含 `ruoyi-admin`/`ruoyi-common`/`ruoyi-api`/`ruoyi-modules`/`script`）与 `vendor/front-end`（Vue 3 + TS + Element Plus，含 `pnpm-workspace.yaml`）。B/C 按 Task 1 Step 3–4 从这里 `cp -a` 进 `code/`。

对着底座真表跑全链路后暴露三条，必须写进 B 线导入清单：

1. **PostgreSQL 驱动默认关闭**：`ruoyi-admin/pom.xml` 里 `org.postgresql:postgresql` 是注释态。PG 栈（`:8080`）不改这一行起不来，MySQL 栈不受影响。
2. **`ruoyi-modules/ruoyi-ai` 与我们的包名直接撞车**：该模块 Java 包根就是 `org.dromara.ai`（现存 `org/dromara/ai/controller`），而设计稿把 `agent-hub-ai` 的包冻结为同一个 `org.dromara.ai`——两个模块同时进 `ruoyi-admin` 会产生 split package。它的 AI 表用 `sai_*` 前缀，与我们的 40 张 `ai_*` 不重名，但**必须整体裁掉**：`ruoyi-modules/pom.xml` 去掉 `<module>ruoyi-ai</module>`、`script/sql/ry_ai.sql` 不加载、前端 `views/ai/chat`（底座自带菜单 `aichat` → 组件 `ai/chat/index`）一并删除，否则 §3.2 冻结的 `src/views/ai/{domain}/{page}/index.vue` 命名空间会被底座页面占住。
3. **明文默认值必须在生产覆盖**：底座 `application-prod.yml` 里数据库是 `localhost:3306/ry-vue` + `root/root`，Redis `localhost:6379/ruoyi123`，`sa-token.jwt-secret-key` 与 `api-decrypt` 的 RSA 公私钥都是仓库内明文。部署侧用「挂载覆盖文件 + `${AGENTHUB_*}` 占位符」把运行时值全部收到 `.env`，见 `deploy/*/config/application-agenthub.yml`；底座自带 yml 不改，避免与后续底座升级冲突。

> 校验方式（可复跑）：临时容器加载底座基线 SQL + 我方三个 AI 脚本，各跑两遍，断言 40 表 / 132 perms / 33+127 字典 / 中文无损 / 双方言 perms 零差异 / Java 常量与真表一致。脚本为一次性工具，未入库。
