#!/usr/bin/env bash
# 打 Docker 部署包（主栈 + demo）并 SCP 上传到 databuff-site（APM_PKG_REMOTE_DIR）。
# 镜像构建与 push 请使用 build-images.sh。
#
# Usage:
#   ./deploy/docker/build-docker.sh
#
# Optional:
#   SKIP_PKG_UPLOAD=1   跳过部署包上传
#
# 下载地址见 deploy/env.sh，build 时写入 install 脚本。

set -euo pipefail

DOCKER_ROOT="$(cd "$(dirname "$0")" && pwd)"
export APM_BUILD_DIST="${APM_BUILD_DIST:-${TMPDIR:-/tmp}/databuff-apm-docker-dist}"
source "${DOCKER_ROOT}/../images/scripts/lib.sh"
log_pkg_publish_targets

RELEASE_VERSION="$(resolve_release_version)"
PKG_NAME="databuff-ai-apm-${RELEASE_VERSION}"
DEMO_PKG_NAME="databuff-apm-demo-${RELEASE_VERSION}"
STAGE_PARENT="$(mktemp -d "${TMPDIR:-/tmp}/${PKG_NAME}.stage.XXXXXX")"
STAGE_DIR="${STAGE_PARENT}/${PKG_NAME}"
DEMO_STAGE="${STAGE_PARENT}/${DEMO_PKG_NAME}"
ARCHIVE="${APM_BUILD_DIST}/${PKG_NAME}.tar.gz"
DEMO_ARCHIVE="${APM_BUILD_DIST}/${DEMO_PKG_NAME}.tar.gz"
INSTALL_SCRIPT="${APM_DOCKER_SRC}/ai-apm-install.sh"
UPDATE_SCRIPT="${APM_DOCKER_SRC}/ai-apm-update.sh"
DEMO_INSTALL_SCRIPT="${APM_DOCKER_SRC}/ai-apm-demo-install.sh"
DEMO_UPDATE_SCRIPT="${APM_DOCKER_SRC}/ai-apm-demo-update.sh"
DIST_INSTALL="${APM_BUILD_DIST}/ai-apm-install.sh"
DIST_UPDATE="${APM_BUILD_DIST}/ai-apm-update.sh"
DIST_DEMO_INSTALL="${APM_BUILD_DIST}/ai-apm-demo-install.sh"
DIST_DEMO_UPDATE="${APM_BUILD_DIST}/ai-apm-demo-update.sh"
DEMO_PKG_SRC="${APM_DOCKER_DEMO_SRC}"

cleanup_stage() {
  rm -rf "$STAGE_PARENT"
}
trap cleanup_stage EXIT

mkdir -p "$STAGE_DIR" "$DEMO_STAGE" "$APM_BUILD_DIST"

ensure_command python3

stage_main_package() {
  echo "[build-docker] staging ${STAGE_DIR} ..."
  mkdir -p \
    "${STAGE_DIR}/sql/migrations" \
    "${STAGE_DIR}/data/fe-meta" \
    "${STAGE_DIR}/data/be-storage" \
    "${STAGE_DIR}/scripts"

  cp -f "${APM_DOCKER_SRC}/docker-compose.yml" "${STAGE_DIR}/"
  cp -f "${APM_DOCKER_SRC}/docker-compose.legacy.yml" "${STAGE_DIR}/scripts/docker-compose.legacy.yml"
  cp -f "${APM_DOCKER_SRC}/start.sh" "${STAGE_DIR}/"
  cp -f "${APM_DOCKER_SRC}/stop.sh" "${STAGE_DIR}/"
  cp -f "${APM_DOCKER_SRC}/reset-table.sh" "${STAGE_DIR}/"
  cp -f "${APM_DOCKER_SRC}/update.sh" "${STAGE_DIR}/"
  cp -R "${APM_DOCKER_SRC}/scripts/." "${STAGE_DIR}/scripts/"
  cp -f "${APM_COMMON_SRC}/scripts/doris-be-wait.sh" "${STAGE_DIR}/scripts/doris-be-wait.sh"
  cp -f "${APM_COMMON_SRC}/scripts/check-compose.sh" "${STAGE_DIR}/scripts/check-compose.sh"
  cp -f "${APM_COMMON_SRC}/scripts/check-avx2.sh" "${STAGE_DIR}/scripts/check-avx2.sh"
  cp -f "${APM_COMMON_SRC}/scripts/schema-migrate.sh" "${STAGE_DIR}/scripts/schema-migrate.sh"
  cp -f "${APM_COMMON_SRC}/scripts/apm-update-lib.sh" "${STAGE_DIR}/scripts/apm-update-lib.sh"
  cp -f "${APM_COMMON_SRC}/scripts/resolve-install-version.sh" "${STAGE_DIR}/scripts/resolve-install-version.sh"
  stage_runtime_env_sh "${STAGE_DIR}/env.sh" "$RELEASE_VERSION"
  cp -f "${APM_DOCKER_SRC}/scripts/image-pkg.sh" "${STAGE_DIR}/scripts/image-pkg.sh"
  cp -R "${APM_COMMON_SRC}/sql/." "${STAGE_DIR}/sql/"
  cp -f "${APM_COMMON_SRC}/upgrade-manifest.json" "${STAGE_DIR}/upgrade-manifest.json"
  cp -f "${APM_DOCKER_SRC}/UPGRADE.md" "${STAGE_DIR}/UPGRADE.md"

  printf '%s\n' "$RELEASE_VERSION" >"${STAGE_DIR}/VERSION"
  inject_doris_host_ports "${STAGE_DIR}/docker-compose.yml"

  chmod +x \
    "${STAGE_DIR}/start.sh" \
    "${STAGE_DIR}/stop.sh" \
    "${STAGE_DIR}/reset-table.sh" \
    "${STAGE_DIR}/update.sh" \
    "${STAGE_DIR}/scripts/"*.sh
}

stage_demo_package() {
  echo "[build-docker] staging demo package ${DEMO_STAGE} ..."
  mkdir -p "${DEMO_STAGE}/scripts"
  cp -f "${DEMO_PKG_SRC}/docker-compose.yml" "${DEMO_STAGE}/"
  cp -f "${DEMO_PKG_SRC}/start.sh" "${DEMO_STAGE}/"
  cp -f "${DEMO_PKG_SRC}/stop.sh" "${DEMO_STAGE}/"
  cp -f "${DEMO_PKG_SRC}/update.sh" "${DEMO_STAGE}/"
  cp -f "${APM_DOCKER_SRC}/scripts/image-pkg.sh" "${DEMO_STAGE}/scripts/image-pkg.sh"
  cp -f "${APM_COMMON_SRC}/scripts/demo-deploy-lib.sh" "${DEMO_STAGE}/scripts/demo-deploy-lib.sh"
  cp -f "${APM_COMMON_SRC}/scripts/resolve-install-version.sh" "${DEMO_STAGE}/scripts/resolve-install-version.sh"
  stage_runtime_env_sh "${DEMO_STAGE}/env.sh" "$RELEASE_VERSION"
  printf '%s\n' "$RELEASE_VERSION" >"${DEMO_STAGE}/VERSION"
  write_demo_compose_env "${DEMO_STAGE}/.env" "$RELEASE_VERSION"
  chmod +x "${DEMO_STAGE}/start.sh" "${DEMO_STAGE}/stop.sh" "${DEMO_STAGE}/update.sh"
}

stage_main_package
stage_demo_package
create_tarball "$STAGE_DIR" "$ARCHIVE"
create_tarball "$DEMO_STAGE" "$DEMO_ARCHIVE"
copy_install_script "$INSTALL_SCRIPT" "$DIST_INSTALL"
copy_install_script "$UPDATE_SCRIPT" "$DIST_UPDATE"
copy_install_script "$DEMO_INSTALL_SCRIPT" "$DIST_DEMO_INSTALL"
copy_install_script "$DEMO_UPDATE_SCRIPT" "$DIST_DEMO_UPDATE"
publish_version_docker_pkg "$RELEASE_VERSION" \
  "$ARCHIVE" \
  "$DEMO_ARCHIVE"
publish_root_pkg \
  "$DIST_INSTALL" \
  "$DIST_UPDATE" \
  "$DIST_DEMO_INSTALL" \
  "$DIST_DEMO_UPDATE"
publish_version_manifest "$RELEASE_VERSION"

cat <<EOF

[build-docker] done
全新安装:
curl -fsSL $(pkg_base_url)/ai-apm-install.sh | bash
就地升级（保留 data/）:
curl -fsSL $(pkg_base_url)/ai-apm-update.sh | bash
Demo 安装:
curl -fsSL $(pkg_base_url)/ai-apm-demo-install.sh | bash
Demo 升级:
curl -fsSL $(pkg_base_url)/ai-apm-demo-update.sh | bash
强制重新下载镜像:
curl -fsSL $(pkg_base_url)/ai-apm-update.sh | bash -s -- --pull-images

EOF
