#!/usr/bin/env bash
# 构建 web 基础镜像 databuffhub/ai-apm-web-base（amd64 / arm64 离线包），不推送 Docker Hub。
# 含 JDK + apt 工具链；日常 build-images.sh 叠 jar 时 FROM 该基础镜像，不再 apt-get。
#
# 推送请用：./.env/push-web-base-dockerhub.sh
#
# Usage:
#   ./deploy/images/build-base.sh
#
# Optional:
#   WEB_BASE_TAG=17-jammy
#   IMAGE_PLATFORMS=linux/amd64,linux/arm64
#   BUILDX_PROGRESS=plain
#   APM_BUILD_DIST      本机保留目录（默认 deploy/images/dist）
#
set -euo pipefail

source "$(cd "$(dirname "$0")" && pwd)/scripts/lib.sh"

DOCKERHUB_NS="${DOCKERHUB_NS:-$(runtime_image_namespace)}"
WEB_BASE_NAME="${WEB_BASE_NAME:-ai-apm-web-base}"
WEB_BASE_TAG="${WEB_BASE_TAG:-17-jammy}"
WEB_BASE_REF="${DOCKERHUB_NS}/${WEB_BASE_NAME}:${WEB_BASE_TAG}"

log()  { printf '[build-base] %s\n' "$*"; }
fail() { printf '[build-base] %s\n' "$*" >&2; exit 1; }

local_web_base_pkg_dir() {
  printf '%s\n' "${APM_BUILD_DIST:-${APM_IMAGES_ROOT}/dist}/base"
}

prepare_web_base_context() {
  local ctx
  ctx="$(mktemp -d "${TMPDIR:-/tmp}/apm-web-base.XXXXXX")"
  cp -f "${APM_DOCKER_IMAGE_SRC}/web/Dockerfile.base" "${ctx}/Dockerfile"
  printf '%s\n' "$ctx"
}

build_web_base_tarballs() {
  local ctx arch dist_dir tarball gz_tar

  ctx="$(prepare_web_base_context)"
  dist_dir="$(local_web_base_pkg_dir)"
  mkdir -p "$dist_dir"
  ensure_buildx

  for arch in $(image_arch_list); do
    log "buildx ${WEB_BASE_REF} (linux/${arch}) ..."
    tarball="${dist_dir}/$(image_tarball_name "$WEB_BASE_NAME" "$WEB_BASE_TAG" "$arch" .tar)"
    buildx_export_image_tarball "$WEB_BASE_REF" "$ctx" "linux/${arch}" "$tarball"
    verify_image_tarball_arch "$tarball" "$arch"
    gz_tar="$(compress_image_tarball "$tarball")"
    write_image_tarball_size_sidecar "$gz_tar"
    log "  -> ${gz_tar}"
  done

  rm -rf "$ctx"
}

[[ -f "${APM_DOCKER_IMAGE_SRC}/web/Dockerfile.base" ]] || \
  fail "missing ${APM_DOCKER_IMAGE_SRC}/web/Dockerfile.base"

ensure_command docker
ensure_command gzip

build_web_base_tarballs

cat <<EOF

[build-base] done
  Image    : ${WEB_BASE_REF}
  Platforms: $(image_platforms)
  LocalPkg : $(local_web_base_pkg_dir)/$(image_tarball_name "$WEB_BASE_NAME" "$WEB_BASE_TAG" '<arch>')
  Next     : ./.env/push-web-base-dockerhub.sh

EOF
