# 第三方许可与原创范围声明

本页回答两件事：真实系统里哪些代码来自开源中后台底座、哪些是本项目的自有开发成果。

## 底座（保留许可声明）

- 后端：RuoYi-Vue-Plus 6.0.0，MIT License，Copyright (c) 2019 RuoYi-Vue-Plus。许可全文随仓分发，见 [code/back-end/LICENSE](../code/back-end/LICENSE)。
- 前端：RuoYi-Vue-Plus 配套管理台前端 plus-ui 6.0.0，同为 MIT License，Copyright (c) 2019 RuoYi-Vue-Plus。许可全文见 [code/front-end/LICENSE](../code/front-end/LICENSE)。
- 覆盖范围：`code/back-end` 下除 `ruoyi-modules/agent-hub-ai` 与 `script/sql/agenthub-ai-*` 之外的全部模块目录，`code/front-end/src` 下除 `views/ai`、`api/ai` 之外的全部页面与组件，以及它们携带的 Spring 自动配置、i18n 词条与 MyBatis 映射文件。
- 底座自带的版权与作者声明（源码注释中的 `@author`、`pom.xml` 与 `package.json` 的许可字段、启动 banner 里的框架标识）一并保留，不做删除或改写。
- Maven 与 pnpm 引入的传递依赖按各自的开源许可分发，清单由构建工具生成，不在本页逐一列举。
- 底座基线 SQL：`code/back-end/script/sql/base/{postgres,mysql}/ruoyi-{vue,job,workflow}-base.sql` 六个文件是底座 6.0.0 自带脚本的双方言整理件（建 `sys_*` 真表与底座自带数据），随 MIT 许可分发，归入底座范围；文件内保留底座的建表语句与种子数据，不按本项目命名规范改写，`scripts/checks/naming-convention.mjs` 因此只把该目录列为免检。initdb 的挂载序号（`10`/`11`/`12`）与「先底座后 `ai_`」的顺序是本项目的编排设计，见 [部署与环境变量](configuration.md) 第 3 节。
- IP 地址库数据文件：`code/back-end/ruoyi-common/ruoyi-common-core/src/main/resources/ip2region_v4.xdb`，来自 [ip2region](https://gitee.com/lionsoul/ip2region) 项目 data 目录的 v4 版地址库，Apache-2.0 License；是底座 `RegionUtils` 的运行期数据文件，随库分发。文件名为 `RegionUtils.DEFAULT_IPV4_XDB_PATH` 的类路径强制名（改名即查询失效），`scripts/checks/no-secrets-in-repo.mjs` 已将其登记为二进制免检（文件名免检、内容特征扫描本就不适用于二进制数据）。

## AgentHub 自有开发范围

- AI 能力域后端：`code/back-end/ruoyi-modules/agent-hub-ai`，含智能体内核装配、工具注册与调用、知识库检索与上下文装配、路由编排、安全合规与审计、SSE 事件契约。
- AI 能力域前端：`code/front-end/src/views/ai`、`code/front-end/src/api/ai` 及其类型定义。
- 数据契约：`code/back-end/script/sql/{postgres,mysql}/agenthub-ai-{ddl,seed,menu}.sql` 的 40 张 `ai_` 表、种子数据与菜单注册。
- 交付门禁与部署：`scripts/checks` 下的校验脚本、`deploy/` 下的编排与 Nginx 配置。
- 设计与规格文档：`docs/superpowers/specs` 与《开发总览手册.md》。

上述范围为本项目原创，功能需求以《开发总览手册.md》为基线，架构与契约以[最终设计稿](superpowers/specs/2026-11-20-agenthub-decomposition-design.md)为准。

## 变更记录

- 2026-11-20：底座整树入仓，首次登记第三方许可与原创范围边界。
- 2026-11-20：底座基线 SQL 整理入仓到 `script/sql/base/`，initdb 链不再依赖仓库外的路径变量。
- 2026-11-24：ip2region v4 地址库数据文件入仓（缺库曾致新部署登录 500），登记 Apache-2.0 出处与门禁免检口径。
