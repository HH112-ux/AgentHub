# 试用 ECS 部署交接（PG 栈 · 裁栈版）

日期：2026-11-23。环境：阿里云 ECS 免费试用，`ecs.u1-c1m4.large`，2 vCPU / 8 GiB，Ubuntu 22.04，中国香港可用区 C。

> **状态更正（2026-11-23 晚实测）**：未经裁栈的全栈 7 容器（含 Milvus / etcd / MinIO）在同一台 2C/8G 上全部 Up 且 healthy，§0 问题 3 的「内存超预算」结论作废，§1 的 swap 与 §3 的裁栈 override 均非必需。§0 问题 1、2（MILVUS 密码/地址死键）与 §11 待办仍有效，是真实仓库缺陷。

本文只覆盖**在 2 核 8 G 上把 PG 栈跑到能登录**。`deploy/**` 一律不修改——它是 CI 双门禁守着的共享编排（`scripts/checks/deploy-static.mjs`），本机差异全部走不进仓库的 override 文件。

## 0. 先读这段：为什么不是 `docker compose up -d --build`

手册 §10.1 给的一条命令在这台机器上会失败，原因有三条，且前两条与内存无关：

| # | 问题 | 证据 | 后果 |
|---|---|---|---|
| 1 | **app 容器拿不到 `MILVUS_PASSWORD`** | `ProductionConfigGuard.java:82` 比对 `agenthub.ai.milvus.password` 的出厂值 `"Milvus"`；`application.yml:323` 写成 `${MILVUS_PASSWORD:Milvus}`；而 `docker-compose.yml:35` 只给 app 传了 `AGENTHUB_MILVUS_PASSWORD`（少一层 `.ai`，是死键） | 属性解析成 `"Milvus"` → 命中出厂值 → `prod` 下抛 `IllegalStateException` → app 崩溃循环 → `web` 的 `depends_on: app healthy` 永不满足 → 502 |
| 2 | **Milvus 地址是硬编码字面量** | `application.yml:319` 的 `uri: http://127.0.0.1:19530` 没有环境变量占位符，compose 的 `AGENTHUB_MILVUS_URI` 同样是死键 | 即使 Milvus 在跑，app 也连自己容器，RAG 不可用 |
| 3 | **全栈内存超预算** | Milvus standalone + etcd + MinIO 约 3–4 GB，Spring Boot（ZGC）约 2–3 GB，PG + Redis 约 1 GB，加上系统与构建峰值 | 8 GB 不够，OOM Killer 优先杀最胖的容器 |

问题 1 和 2 属于仓库缺陷，应另开分支修回 `main`（见 §7）。本文用 override 绕过它们，使部署可以先进行。

**裁栈不影响启动**，已核对代码：`AiVectorStore` 无 `@PostConstruct`，Milvus 句柄只在 `add/search/delete` 里 `computeIfAbsent` 惰性创建；体检项 `AiHealthCheckServiceImpl.probeMilvus()` 失败只写一行 FAIL 不抛异常；`SystemApplicationRunner` 初始化 OSS 只读库写 Redis，不碰 S3。全仓无 Actuator、无 `HealthIndicator`。

裁掉的代价：知识库向量检索、附件上传下载在**调用时**报错。登录、对话、智能体路由、HITL 确认、审计、定时任务不受影响。

## 1. 服务器初始化

```bash
curl -fsSL --max-time 20 https://get.docker.com | sh
docker --version && docker compose version && systemctl is-active docker
apt-get update -y && apt-get install -y git curl
```

加 4 G swap（缓解构建峰值，不解决 §0 问题 3）：

```bash
fallocate -l 4G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
sysctl vm.swappiness=10 && echo 'vm.swappiness=10' > /etc/sysctl.d/99-swap.conf
```

**自检 override 写法支持性**（决定 §3 用哪一版）：

```bash
docker compose version
```

- `v2.24.1` 及以上 → 用 §3 主写法（`!override` 标签）
- 更低版本 → 用 §3 末尾的备选写法

安全组入方向只放行 **22 / 80 / 443**。`compose` 把 app 的 8080 发布到了宿主机（`APP_HOST_PORT`），**安全组里不要开 8080**，否则等于绕过 Nginx 的 actuator 拦截与限流直打后端。

## 2. 拉代码

```bash
git clone https://github.com/HH112-ux/AgentHub.git /opt/AgentHub
cd /opt/AgentHub/deploy/deploy-pg
```

## 3. override 文件（不进仓库）

新建 `/opt/AgentHub/deploy/deploy-pg/docker-compose.override.yml`。Compose 会自动与同目录 `docker-compose.yml` 合并，且该文件不在仓库里，CI 的两条 deploy 门禁都不会碰到它。

```yaml
# 本机专用，不入库。作用：绕过 §0 的 1、2 两个缺陷 + 裁栈 + 内存上限。
services:
  app:
    environment:
      # 修 §0-1：把口令真正送进 app 容器，否则 ProductionConfigGuard 命中出厂值拒绝启动。
      MILVUS_PASSWORD: ${MILVUS_PASSWORD:?必须在 .env 中设置}
      # 修 §0-2 的现场版：属性路径是 agenthub.ai.milvus.uri，对应环境变量名如下（宽松绑定）。
      # 裁栈后 Milvus 不存在，这里留一个不会解析成 127.0.0.1 的值即可，不影响启动。
      AGENTHUB_AI_MILVUS_URI: http://milvus-unavailable:19530
      # 2 核 8 G 下的堆上限。注意 Dockerfile 的 ENTRYPOINT 把 ${JAVA_OPTS} 排在
      # -XX:+UseZGC 之前，所以 GC 算法改不了，只能约束堆与元空间。
      JAVA_OPTS: "-Xmx2g -XX:MaxMetaspaceSize=384m"
    # 丢掉对 milvus 的依赖，只保留 db / redis。
    depends_on: !override
      db:
        condition: service_healthy
      redis:
        condition: service_healthy
    deploy:
      resources:
        limits:
          memory: 3G

  db:
    deploy:
      resources:
        limits:
          memory: 1536M

  redis:
    deploy:
      resources:
        limits:
          memory: 512M

  web:
    deploy:
      resources:
        limits:
          memory: 128M
```

备选写法（compose < v2.24.1，不支持 `!override` 标签）：把 `depends_on` 那段删掉，改为复制一份 `docker-compose.yml` 到本机、手工删除 `app.depends_on.milvus` 与 `etcd/minio/milvus` 三个服务块，用 `-f` 显式指定该文件。**这种方式改出来的文件绝不能提交。**

## 4. `.env`

```bash
cp .env.example .env
```

逐项填写。**没改完不要起栈**——`ProductionConfigGuard` 命中任一项出厂值即拒绝启动：

| 变量 | 要求 | 生成方式 |
|---|---|---|
| `POSTGRES_DB` / `POSTGRES_USER` | 自定义 | 建议 `agenthub` / `agenthub` |
| `POSTGRES_PASSWORD` | 强口令，不得为 `Zl@2026` | `openssl rand -base64 18` |
| `REDIS_PASSWORD` | 强口令，不得为 `Zl@2026` | `openssl rand -base64 18` |
| `MILVUS_PASSWORD` | **不得为 `Milvus`**，且必须非空（override 用了 `:?`） | `openssl rand -base64 18` |
| `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` | 非默认 | 同上 |
| `AGENTHUB_SA_TOKEN_JWT_SECRET_KEY` | 不得为 `abcdefghijklmnopqrstuvwxyz` | `openssl rand -hex 32` |
| `AGENTHUB_AI_CRYPTO_KEY` | **16/24/32 位**，不得为 `agenthub-ai-2026` | `openssl rand -base64 24`（正好 32 字符） |
| `AGENTHUB_API_DECRYPT_PRIVATE_KEY` | 见 §5，PKCS#8 base64 单行 | `openssl` |
| `AGENTHUB_API_DECRYPT_PUBLIC_KEY` | 见 §5，X.509/SPKI base64 单行 | `openssl` |
| `AGENTHUB_SECURITY_GUARD_MODE` | 保持 `prod`。填 `warn` 在 prod 下会被忽略（`ProductionConfigGuard.java:94-97`） | — |

> ⚠️ 手册 §10.2 那张表里的变量名是 `BIZBUDDY_*`，属旧名。**以 compose 与 `.env.example` 的 `AGENTHUB_*` 为准**，照手册填会填成死键。

## 5. RSA 密钥：两对，不是一对

`CryptoFilter.java:45` 在**构造函数**里校验格式，格式错直接启动失败。要求：私钥 = PKCS#8、公钥 = X.509/SPKI、≥1024 位（用 2048）、base64 **单行无换行无 PEM 头**。

角色关系（已用出厂密钥做过加解密往返验证）：

- **A 对**管请求：前端加密 → 后端解密。`VITE_APP_RSA_PUBLIC_KEY` ↔ `AGENTHUB_API_DECRYPT_PRIVATE_KEY`
- **B 对**管响应：后端加密 → 前端解密。`AGENTHUB_API_DECRYPT_PUBLIC_KEY` ↔ `VITE_APP_RSA_PRIVATE_KEY`

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out pairA-priv.pem
openssl pkey  -in pairA-priv.pem -pubout -out pairA-pub.pem
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out pairB-priv.pem
openssl pkey  -in pairB-priv.pem -pubout -out pairB-pub.pem

# 取单行 base64
one() { grep -v -- '-----' "$1" | tr -d '\n'; }
```

写进 `.env`：

```
AGENTHUB_API_DECRYPT_PRIVATE_KEY=$(one pairA-priv.pem)   # 开头应为 MIIEvQIBADANBgkqhkiG9w0BAQEFAASC
AGENTHUB_API_DECRYPT_PUBLIC_KEY=$(one pairB-pub.pem)     # 开头应为 MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A
```

若私钥串以 `MIIEowIBAAKCAQEA` 开头，那是 PKCS#1，会被拒；用 `openssl pkey -in old.pem -out new.pem` 转一次。

## 6. 前端产物

`dist/` 是 gitignore 的，必须在**本机**构建后上传（2 核上跑 `pnpm build` 会慢且吃内存）。

**不要修改仓库里的 `code/front-end/.env.production`。** Vite 的 `loadEnv` 会用真实进程环境变量覆盖 `.env` 文件里的同名 `VITE_*` 值（已实测验证），所以构建时注入即可：

```bash
cd code/front-end
pnpm install --frozen-lockfile
VITE_APP_RSA_PUBLIC_KEY="$(one pairA-pub.pem)" \
VITE_APP_RSA_PRIVATE_KEY="$(one pairB-priv.pem)" \
pnpm build
```

上传（在**本机**执行，目标目录与 compose 的 `FRONTEND_DIST` 默认值对齐）：

```bash
scp -r code/front-end/dist root@<服务器IP>:/opt/AgentHub/code/front-end/
```

## 7. 证书

设计稿 §2.3.1 把 HTTPS 列为硬前置，`nginx/agenthub.conf:38` 固定读 `/etc/nginx/cert/agenthub.crt` 与 `.key`，而 `deploy/*/cert/` 已 gitignore。**证书目录为空时 `web` 容器直接启动失败。**

演示环境用自签（与 CI 里 `.github/workflows/ci.yml:83` 的做法一致），浏览器会提示不安全但链路全通：

```bash
mkdir -p cert && openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
  -keyout cert/agenthub.key -out cert/agenthub.crt -subj "/CN=agenthub"
```

要无警告就得买域名并签 Let's Encrypt（CA 只按域名签发，不给裸 IP 签），本交接不覆盖。

## 8. 起栈

**必须显式列出服务名**，否则 Compose 会按 `docker-compose.yml` 把 milvus/etcd/minio 一起拉起来：

```bash
cd /opt/AgentHub/deploy/deploy-pg
docker compose up -d --build db redis app web
docker compose ps
```

首次 `--build` 会在容器里跑 Maven 编译（`maven:3.9-eclipse-temurin-21`），2 核 + 首次拉依赖，**预计 15–40 分钟**，期间 CPU 满载属正常。

预期 `docker compose ps`：四个服务，`app` 与 `web` 最终 `healthy`，`db`/`redis` `healthy`。

冒烟检查：

```bash
curl -sk --max-time 10 https://localhost/healthz          # 期望 agenthub-nginx-ok
docker compose logs --tail=50 app | grep 配置收口          # 期望"自检通过：未发现出厂示例密钥"
```

## 9. 三个已知故障的判读

| 现象 | 最可能原因 | 一条命令定位 |
|---|---|---|
| `app` 反复重启，日志有 `[配置收口] 命中 N 项出厂示例值` | §4 某项没改，或 §5 密钥恰好等于出厂值 | `docker compose logs app \| grep -A20 配置收口` |
| `app` 启动即退，日志有 `RSA私钥格式错误` | §5 给了 PKCS#1、带换行、或低于 1024 位 | `docker compose logs app \| grep -i rsa` |
| `web` 不启动 / 502 | 证书缺失，或 `app` 未 healthy | `docker compose logs web --tail=30` |
| 容器随机重启、`dmesg` 有 `Out of memory` | §0-3 内存超预算 | `docker stats --no-stream` + `dmesg \| grep -i kill` |

## 10. 内存实测（这一步决定后续决策）

起栈成功后连续观测 15 分钟：

```bash
docker stats --no-stream
free -h
```

把两个输出记下来。判读：

- `app` + `db` + `redis` + `web` 合计 < 6 GiB 且无 swap 抖动 → 这台机器可长期跑裁栈版，全栈需求另议
- 合计 > 7 GiB 或频繁 swap → 需要进一步压缩（先降 `-Xmx` 到 1536m，再考虑关掉定时任务与日报链路）
- 想上全功能（RAG + 附件）→ 唯一现实路径是升配到 4 核 16 G，**注意试用额度有 0.833 元/小时的抵扣上限，升配后超出部分即时开始自费**

## 11. 待办（属仓库修复，不属本机部署）

本文的 §0-1 与 §0-2 是 `deploy/**` 与 `application.yml` 的真实缺陷，应另开分支修回 `main`，否则每个部署者都要重复绕一次：

1. `docker-compose.yml` 的 `x-app-environment` 补 `MILVUS_PASSWORD: ${MILVUS_PASSWORD}`
2. `config/application-agenthub.yml` 补 `agenthub.ai.milvus.uri: ${AGENTHUB_MILVUS_URI}`，并删掉 `x-app-environment` 里两个死键 `AGENTHUB_MILVUS_URI` / `AGENTHUB_MILVUS_PASSWORD`
3. `scripts/checks/deploy-static.mjs` 增一条校验：`x-app-environment` 里声明的 `AGENTHUB_*` 必须能对应到一个真实被读取的属性路径（本次两个缺陷的共同成因就是「死键」，现有门禁只查 `.env.example` 有没有登记，不查有没有人读）

按 AGENTS.md，改 `deploy/**` 与共享配置需走 PR 并由非作者评审；§11-3 属新增强制校验，须同步在设计稿 §3.2 命名表定位。
