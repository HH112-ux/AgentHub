# 登录 500 仓库侧修复记录

> 目标：让「从仓库全新部署 → 登录」不再依赖服务器手工补丁。本文记录两项根因、落地分支与验证边界。

## 根因与修复

1. **RegionUtils 静态初始化硬依赖类路径上的 xdb 地址库**
   - 缺文件或文件损坏时，原实现会在静态初始化阶段抛 `ServiceException`，登录成功路径查询 IP 归属地即返回 500。
   - `fix/region-utils` 改为缺库、损坏统一降级为「未知」，不阻断应用启动与登录；查询与关闭方法在实例为空时安全返回。
2. **ip2region 地址库未随仓分发**
   - `fix/xdb-asset` 将 `ip2region_v4.xdb` 放入 `ruoyi-common-core` 的 resources，并在 `docs/open-source-licenses.md` 登记 Apache-2.0 出处。
   - 测试同时覆盖「地址库正常命中」与「实例缺失时降级」两条路径。
3. **dist 占位密钥门禁**
   - `fix/dist-rsa-gate` 增加 `scripts/checks/dist-rsa.mjs` 门禁，防止把 `.env.production` 中的占位符或示例密钥构建进 `dist`。
   - CI 在构建时注入一次性 RSA 密钥对，构建后复验产物；生产环境仍须通过部署环境注入真实密钥。

## 验证边界

- `RegionUtilsTest` 随 `./mvnw -pl ruoyi-modules/agent-hub-ai -am test` 执行。
- 命名、密钥扫描、文档链接与双方言静态门禁持续在 CI 执行。
- 新部署登录与 IP 归属地查询仍需在云端真实栈上冒烟；本地单测不替代接口 e2e。
