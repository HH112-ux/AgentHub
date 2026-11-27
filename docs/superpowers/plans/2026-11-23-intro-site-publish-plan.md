# AgentHub 介绍页 · Sites 发布记录

> 当前代码线：`feature/intro-site-live-chat`。介绍页采用单文件 `code/intro-site/index.html`，公开问答由 Qoder Sites Function 提供；不承载真实系统页面，不保存密钥，不接触业务数据。

## 边界

- Qoder Sites 只承载产品介绍、跳转链接和公开内容问答。
- 云端 Agent 不接入业务知识库、业务工具、会话数据或 PII。
- `QODER_PAT` 仅由 Function 在服务端读取，不进入前端、仓库、日志或聊天。
- 对话历史只随当前浏览器请求携带，服务端不落库；关闭页面即丢弃。
- 真实系统必须登录，介绍页只提供跳转入口。

## 已落地

- 单文件介绍页保留深色/浅色主题切换、卡片流光、轨道流程与公开 FAQ。
- `functions/chat.mjs` 实现一次请求一个一次性云端 Session，并将浏览器携带的近期历史拼入本轮提示词。
- SSE 事件只投影 `delta`、`done`、`error` 三类公开结果；上游异常、凭据和原始响应体不透传给浏览器。
- Agent 定义不包含 `tools`，只使用 `functions/system-prompt.mjs` 中的公开产品信息。
- 模型标识固定为 `auto`，避免模型目录变化导致 Agent 定义指纹漂移。
- 云端重复资源冲突时按确定排序复用已有绑定，把永久失败降级为可继续使用；多余资源仍需人工归档。
- `dev/local-server.mjs` 提供无凭据假云端预览，用于验证静态页、SSE 分帧、增量渲染和错误文案。

## 本地验证

```bash
node --test code/intro-site/tests/chat.test.mjs
node code/intro-site/dev/local-server.mjs
```

本地预览地址为 `http://127.0.0.1:8000/`。假云端只验证接线，不验证真实模型回答。

## 发布检查

- [ ] 在 Qoder Sites 准备完整草稿，`functionDirectory` 指向 `code/intro-site/functions`
- [ ] 确认平台托管的 `QODER_PAT` 可用，不在描述符或仓库中写入密钥
- [ ] 发布后验证 `/functions/v1/app/api/chat` 返回 `text/event-stream`
- [ ] 真浏览器验证两轮问答、历史接续、业务数据拒答和错误重试
- [ ] 验证「进入系统」在新标签打开且当前页保持不动
- [ ] 将自签名证书替换为域名正式证书，或在页面上明确提示风险

## 残留项

- 对话工作台、专家管理、健康自检三张截图素材仍待补。
- Function 入口的 Deno 类型检查需在安装 Deno 的环境执行。
- 进程内锁不能替代跨实例持久锁；若站点访问范围扩大，应先引入站点数据库再做资源协调。
