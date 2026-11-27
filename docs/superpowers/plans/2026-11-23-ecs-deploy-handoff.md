# AgentHub ECS 部署交接（当前口径）

> 本文只记录部署顺序与验证点；配置项、容器覆盖顺序和双栈端口以 [配置与部署口径](../../configuration.md) 为准。仓库主线已修复 Milvus 环境变量绑定，不需要再使用本地 override 绕过。

## 1. 前置检查

- Docker Engine 与 Docker Compose v2.24.1+
- 至少 8 GiB 可用内存；全栈包含 PostgreSQL/MySQL、Redis、Milvus、MinIO、etcd
- 安全组仅开放 22、80、443；不要公开 app 的 8080 端口
- 正式环境使用域名证书；自签证书只用于链路验证

## 2. 拉取代码并准备环境变量

```bash
git clone https://github.com/HH112-ux/AgentHub.git /opt/AgentHub
cd /opt/AgentHub/deploy/deploy-pg
cp .env.example .env
```

逐项替换 `.env` 中的 `REPLACE_WITH_*` 占位符。数据库、Redis、Milvus、MinIO、JWT、AI 加密密钥与 MCP token 都必须使用部署环境自有值；`AGENTHUB_SECURITY_GUARD_MODE` 保持 `enforce`。

RSA 密钥需要两对：

- 请求方向：`VITE_APP_RSA_PUBLIC_KEY` ↔ `AGENTHUB_API_DECRYPT_PRIVATE_KEY`
- 响应方向：`AGENTHUB_API_DECRYPT_PUBLIC_KEY` ↔ `VITE_APP_RSA_PRIVATE_KEY`

私钥使用 PKCS#8、公钥使用 X.509/SPKI，base64 单行且不带 PEM 头。生成方式：

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out pairA-priv.pem
openssl pkey -in pairA-priv.pem -pubout -out pairA-pub.pem
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out pairB-priv.pem
openssl pkey -in pairB-priv.pem -pubout -out pairB-pub.pem
one() { grep -v -- '-----' "$1" | tr -d '\n'; }
```

## 3. 构建前端静态产物

前端 `dist` 不进入 Git，必须在部署机或受控构建机生成。构建时注入与后端配对的 RSA 密钥：

```bash
cd /opt/AgentHub/code/front-end
pnpm install --frozen-lockfile
VITE_APP_RSA_PUBLIC_KEY="$(one /path/to/pairA-pub.pem)" \
VITE_APP_RSA_PRIVATE_KEY="$(one /path/to/pairB-priv.pem)" \
pnpm build
```

不要把真实私钥写入 `.env.production` 或提交到仓库。CI 已增加 dist 占位密钥复验门禁，构建产物中命中占位符或示例密钥会直接失败。

## 4. 证书与起栈

```bash
cd /opt/AgentHub/deploy/deploy-pg
mkdir -p cert
# 正式环境替换为域名证书；以下仅用于本地链路验证
openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
  -keyout cert/agenthub.key -out cert/agenthub.crt -subj "/CN=agenthub"
docker compose up -d --build
docker compose ps
```

小内存机器可通过 `.env` 中的 `AGENTHUB_APP_JAVA_OPTS` 限制 JVM 堆与元空间，不要修改共享编排。

## 5. 冒烟验证

```bash
curl -k --max-time 10 https://localhost/healthz
docker compose logs --tail=100 app
```

验收点：

- Nginx `/healthz` 返回 `agenthub-nginx-ok`
- app 配置收口通过，未命中占位符或示例密钥
- PostgreSQL/MySQL、Redis、Milvus、MinIO、etcd 均 healthy
- 登录、对话、SSE、写工具确认、审计查询主链路可运行
- 知识库检索与附件上传下载在真实栈上各跑一次

真实接口 e2e 与浏览器巡检仍按设计稿 §9 H1 执行；本地静态门禁与单测不能替代线上冒烟。
