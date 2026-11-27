# 登录 500 仓库侧修复计划（RegionUtils 容错 + dist 出厂密钥门禁 + xdb 政策）

> **背景**：登录 500 已在服务器现场修好（dist 重建 + RegionUtils 补丁），但**仓库一行没动**——谁从仓库重新部署都会复现。本文把两条修复落回仓库，并处理地址库文件政策。
>
> **For agentic workers:** 步骤用 `- [ ]` 复选框跟踪；每个任务独立分支、独立 PR、非作者评审；提交用 `<type>: <中文简述>`；不直推 `main`。

**Goal:** 让「从仓库全新部署 → 登录」不再依赖服务器手工补丁。

**根因（已验证，证据链完整）：**

1. **RegionUtils 静态初始化硬依赖类路径上的 xdb 地址库**：仓库从不携带 `ip2region_v4.xdb`，缺文件时静态块抛 `ServiceException`，登录成功路径必查 IP 归属地 → 任何新部署登录即 500。
2. **dist 出厂 RSA 密钥**：`code/front-end/.env.production`（tracked）原样携带底座演示密钥对，未注入自有密钥构建出的 dist 与后端密钥对不匹配 → 请求头 AES key 解不开 → Filter 层抛 `BadBlockException` → 登录 500（响应无 msg，前端落「系统未知错误」）。

## 约束（逐字取自 AGENTS.md / 设计稿）

- 一个分支只承载一个独立任务；合入 `main` 必须 PR + 至少一名非作者成员评审。
- 新增一条强制规则的标准路径：写零依赖校验脚本 `scripts/checks/{scope}.mjs`（本地与 CI 同一入口可跑）→ 在 `ci.yml` 登记为必过步骤 → 在设计稿 §3.2 命名表定位。
- 仓库为 public 且 CI 日志公网可见：校验脚本只报违规位置，不回显密钥或命中内容。
- 修改共享配置（`.github/workflows/ci.yml`）走 PR；设计稿 §6 契约冻结不影响 §3.2 命名表登记。

## 任务分解

### Task A: RegionUtils 缺库容错（分支 `fix/region-utils`）

- [ ] A1. 先写失败单测 `RegionUtilsTest`（测试类路径天然无 xdb，覆盖生产故障场景）
- [ ] A2. 改 `RegionUtils`：缺 IPv4 地址库只 `log.warn`、实例置空、查询降级「未知」；初始化异常同样降级不再抛
- [ ] A3. 推送分支、开 PR（本机 JDK 17 无法编译 Java 21 项目，红绿验证靠 CI `verify` job 的 `-am test`，PR 内注明）

### Task B: dist 出厂密钥 CI 门禁（分支 `fix/dist-rsa-gate`）

- [ ] B1. 写零依赖脚本 `scripts/checks/dist-rsa.mjs`：比对 `code/front-end/dist` 与 `.env.production` 的出厂密钥值，命中即红（只报文件与键名，不回显值）
- [ ] B2. `ci.yml` 登记为必过步骤 + 「CI 门禁覆盖」小结表加一行；前端构建步骤改为 CI 现签一次性密钥对注入（否则门禁永远红——CI 默认用 `.env.production` 出厂值构建）
- [ ] B3. 设计稿 §3.2「CI 门禁脚本」行补 `dist-rsa.mjs` 示例
- [ ] B4. 本机 Node 红绿验证（构造含出厂密钥的假 dist → 红；清空 → 绿），推送分支、开 PR

### Task C: xdb 文件政策（待队长选择）

- [ ] C1. 用户二选一：**先不入库、接受归属地降级「未知」**（推荐）/ **提交 11MB 二进制并登记 `docs/open-source-licenses.md`**
- [ ] C2. 按选择落地（入库则走独立分支 + PR）

## 验证与边界

- CI `verify` 恒定执行 9 条门禁 + 按制品探测追加后端单测/前端构建；Task A 单测随 `-pl ruoyi-modules/agent-hub-ai -am test` 覆盖到 `ruoyi-common-core`。
- 服务器 `/opt/agenthub` 的未提交补丁不受本次影响；本次合入后，服务器侧补丁与仓库收敛（服务器 xdb 文件保留在现场，不因 C1 移除）。
- 不动 `deploy/**`（CI 双门禁共享编排）、不动 `.env.production`（出厂值保留，由 Task B 门禁兜底）。
