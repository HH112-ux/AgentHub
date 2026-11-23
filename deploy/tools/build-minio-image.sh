#!/usr/bin/env bash
# 自建对象存储镜像：上游已把开源版 MinIO 归档（dl.min.io 返回 410 Gone，Docker Hub 的
# minio/minio 与 minio/mc 仓库不再对匿名拉取开放），deploy/*/docker-compose.yml 钉住的
# 镜像名因此只能由官方 GitHub Release 的 linux-amd64 二进制重建，镜像名保持真实版本号不变。
# 只从 github.com/minio 官方仓库取件并逐个校验 sha256，不使用第三方镜像站：
# 对象存储里放的是用户附件与向量库数据段，制品来源必须可核验。
#
# 用法：bash deploy/tools/build-minio-image.sh
# 依赖：curl、sha256sum、docker。覆写口径用环境变量：MINIO_RELEASE / MC_RELEASE / IMAGE_TAG / BASE_IMAGE。
set -euo pipefail

MINIO_RELEASE="${MINIO_RELEASE:-RELEASE.2024-08-17T01-24-54Z}"
MC_RELEASE="${MC_RELEASE:-RELEASE.2024-08-17T11-33-50Z}"
IMAGE_TAG="${IMAGE_TAG:-minio/minio:${MINIO_RELEASE}}"
BASE_IMAGE="${BASE_IMAGE:-debian:12-slim}"

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

fetch() { # fetch <repo> <tag> <asset>
  curl -fsSL --retry 3 -o "${work}/$3" "https://github.com/$1/releases/download/$2/$3"
  curl -fsSL --retry 3 -o "${work}/$3.sha256sum" "https://github.com/$1/releases/download/$2/$3.sha256sum"
}

verify() { # verify <asset>；官方 .sha256sum 记录的是产物内嵌名（minio.RELEASE.<tag>），按它改名后校验再改回
  local asset="$1" embedded
  embedded="$(awk '{print $2}' "${work}/${asset}.sha256sum")"
  mv "${work}/${asset}" "${work}/${embedded}"
  ( cd "${work}" && sha256sum -c "${asset}.sha256sum" )
  mv "${work}/${embedded}" "${work}/${asset}"
}

fetch minio/minio "${MINIO_RELEASE}" "minio.linux-amd64.${MINIO_RELEASE}"
fetch minio/mc "${MC_RELEASE}" "mc.linux-amd64.${MC_RELEASE}"
verify "minio.linux-amd64.${MINIO_RELEASE}"
verify "mc.linux-amd64.${MC_RELEASE}"
mv "${work}/minio.linux-amd64.${MINIO_RELEASE}" "${work}/minio"
mv "${work}/mc.linux-amd64.${MC_RELEASE}" "${work}/mc"

# 入口脚本注册 mc 的 local 别名：compose 的健康检查用 `mc ready local`，而该别名不会由 mc 自建。
# 服务端此时尚未监听，故后台重试而不是当场失败。
cat > "${work}/docker-entrypoint.sh" <<'ENTRY'
#!/bin/sh
set -e
if [ -n "${MINIO_ROOT_USER:-}" ] && [ -n "${MINIO_ROOT_PASSWORD:-}" ]; then
  (
    i=0
    while [ "${i}" -lt 60 ]; do
      mc alias set local "http://127.0.0.1:9000" "${MINIO_ROOT_USER}" "${MINIO_ROOT_PASSWORD}" >/dev/null 2>&1 && break
      i=$((i + 1))
      sleep 2
    done
  ) &
fi
exec minio "$@"
ENTRY

cat > "${work}/Dockerfile" <<DOCKERFILE
FROM ${BASE_IMAGE}
COPY minio /usr/bin/minio
COPY mc /usr/bin/mc
COPY docker-entrypoint.sh /usr/bin/docker-entrypoint.sh
RUN chmod 0755 /usr/bin/minio /usr/bin/mc /usr/bin/docker-entrypoint.sh && mkdir -p /data
EXPOSE 9000 9001
VOLUME ["/data"]
ENTRYPOINT ["/usr/bin/docker-entrypoint.sh"]
CMD ["server", "/data"]
DOCKERFILE

docker build --quiet -t "${IMAGE_TAG}" "${work}"
docker image inspect "${IMAGE_TAG}" --format 'built {{.Id}} {{.Size}}'
