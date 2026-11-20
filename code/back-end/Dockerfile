# syntax=docker/dockerfile:1
# AgentHub 后端镜像：源码进、可执行 jar 出。设计稿 §2.1 各层位置、§2.3 安全约束、§11 前置；口径见 docs/configuration.md。
#
# 为什么自己写而不是沿用底座的「先 mvn package 再 ADD target/*.jar」：那样起栈要求宿主先装好与
# pom `<java.version>` 同版本的 JDK+Maven，换台机器就卡在构建期。构建阶段把 Maven 装进容器之后，
# `docker compose --build up` 一条命令即可，JDK 版本口径只由 pom 声明决定。
#
# 端口：底座按 `server.port` 监听，栈内口径 PG=8080 / MySQL=8081（§10.1），由 compose 的 `SERVER_PORT`
# 注入；Nginx upstream 与健康探针都用同一个值，三处不一致由 scripts/checks/deploy-static.mjs 拦。

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
# 依赖缓存挂 BuildKit cache：改一行代码不该重下整个 .m2，但首次构建仍需能访问 Maven 仓库。
# -am 必需：只打 ruoyi-admin 时同级模块（含 agent-hub-ai）不在本地仓库里，单 -pl 会解析失败。
# 测试不在镜像构建里跑：单测属 CI 与本机 `mvn test`，e2e 属起栈之后。
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -Dmaven.test.skip=true -pl ruoyi-admin -am package

FROM eclipse-temurin:21-jre AS runtime
# 探针命令 `timeout 3 bash -c '</dev/tcp/127.0.0.1/<port>'` 依赖镜像里有 bash 与 coreutils，故用 temurin 而不是 jre-alpine。
LABEL org.opencontainers.image.title="agenthub-app" \
      org.opencontainers.image.description="AgentHub 后端（Spring Boot 4.1 + AgentScope Java，含 agent-hub-ai 能力域）"

WORKDIR /app
# /app/logs：logback 的 `log.path=./logs`；/app/data：长期记忆与工作区 `workspace-root=./data/agent-workspace`。
# 两者在 compose 里各挂命名卷，先在镜像里建好目录，卷首次初始化时才带上正确的属主。
RUN mkdir -p /app/logs /app/data /app/temp
COPY --from=build /workspace/ruoyi-admin/target/ruoyi-admin.jar /app/app.jar

# SERVER_PORT 由栈注入；JAVA_OPTS 留作内存与 GC 参数的现场调口（compose 的 x-app-environment 可覆盖）。
ENV SERVER_PORT=8080 \
    LANG=C.UTF-8 \
    JAVA_OPTS=""
EXPOSE ${SERVER_PORT}

SHELL ["/bin/bash", "-c"]
# egd 用 /dev/./urandom：JVM 对 `file:/dev/urandom` 的旧写法会退回阻塞池。ZGC 与 -XX 参数沿用底座口径。
ENTRYPOINT java -Djava.security.egd=file:/dev/./urandom \
           -Dserver.port=${SERVER_PORT} \
           ${JAVA_OPTS} \
           -XX:+HeapDumpOnOutOfMemoryError -XX:+UseZGC \
           -jar /app/app.jar
