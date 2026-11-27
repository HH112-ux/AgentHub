# 贡献指南

感谢你对 AgentHub 的关注。提交改动前请阅读仓库根目录的 `AGENTS.md`，并遵循以下约定。

## 开发流程

1. 从最新 `main` 创建任务分支：功能使用 `feature/<scope>`，修复使用 `fix/<scope>`。
2. 一个分支只承载一个独立任务，不混入他人改动或本地环境文件。
3. 提交信息使用 Conventional Commits：`feat:`、`fix:`、`docs:`、`test:`、`refactor:`、`chore:`。
4. 推送前先 `git fetch origin`，同步最新 `origin/main` 并解决冲突。
5. 通过 Pull Request 合入 `main`，至少一名非作者成员评审通过，并通过 CI `verify`。

## 目录与命名

- 新文件与目录名使用 ASCII 小写 kebab-case。
- 需求、设计、规格、实施计划和代码注释不得引用外部项目路径、类名或清单。
- 数据库结构或种子数据修改时，PostgreSQL 与 MySQL 脚本必须同步并保持幂等。
- 新增接口必须登记 `ai:{模块}:{动作}` 权限标识和菜单脚本。
- 新增工具、配置项或接口时同步更新 `docs/`。

## 验证要求

至少运行与本改动相关的检查：

```bash
# 静态门禁
for script in scripts/checks/*.mjs; do node "$script"; done

# 后端
cd code/back-end && ./mvnw -pl ruoyi-modules/agent-hub-ai -am test

# 前端
cd code/front-end && pnpm lint && pnpm build
```

写工具、装配或路由改动必须补单元测试，并运行 L4/L5 接口 e2e。PR 中请写明实际命令与关键输出，不要只写“已测试”。

## 安全与隐私

- 禁止提交密钥、证书、`.env`、本地绝对路径、业务数据或 PII。
- 仓库为 public，CI 日志公网可见；校验脚本只报告违规位置，不回显命中内容。
- 介绍页与公开问答不得连接业务知识库、工具、会话数据或 PII。

## Pull Request

请使用 [PR 模板](.github/pull_request_template.md)，说明改动目的、影响范围、验证结果和遗留项。涉及共享 API、数据模型、事件或部署配置时，明确标注受影响模块并通知相关成员。
