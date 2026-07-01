#!/usr/bin/env bash
# 打 Docker 一体化离线包：部署脚本包 + demo 部署包 + APM 镜像 + Doris 镜像（按架构分包）。
#
# 依赖产物:
#   ./deploy/images/build-images.sh          # 每次发版
#   ./deploy/docker/build-docker.sh          # 每次发版
#   ./deploy/images/upload-infra-images.sh   # 仅 Doris/ZK 版本变更时（通常一次即可）
#
# Doris 镜像优先复用本地 deploy/images/dist/infra/images/（或 APM_BUILD_DIST）；
# 仅本地均无有效包时才从 ${APM_PKG_BASE}/infra/images/ 下载。
#
# Usage:
#   ./deploy/docker/build-docker-offline.sh
#
# Optional:
#   AUTO_BUILD_DEPS=1     缺少依赖时自动执行上述构建脚本
#   OFFLINE_ARCHES=amd64  仅打指定架构（逗号分隔，默认 amd64,arm64）
#   SKIP_PKG_UPLOAD=1     跳过上传

set -euo pipefail

DOCKER_ROOT="$(cd "$(dirname "$0")" && pwd)"
# 与 build-images.sh 一致：默认复用 deploy/images/dist，避免从 APM_PKG_BASE 拉大包
source "${DOCKER_ROOT}/../images/scripts/lib.sh"
export APM_BUILD_DIST="${APM_BUILD_DIST:-${APM_IMAGES_ROOT}/dist}"

RELEASE_VERSION="$(resolve_release_version)"
DORIS_VERSION="$(doris_image_version)"
DOCKER_PKG_NAME="databuff-ai-apm-${RELEASE_VERSION}.tar.gz"
DEMO_PKG_NAME="databuff-apm-demo-${RELEASE_VERSION}.tar.gz"
APM_IMAGES_DIR="$(local_images_pkg_dir "$RELEASE_VERSION")"
INFRA_IMAGES_DIR="$(local_infra_images_pkg_dir)"
OFFLINE_INSTALL_SRC="${DOCKER_ROOT}/ai-apm-offline-install.sh"
OFFLINE_DEMO_INSTALL_SRC="${DOCKER_ROOT}/ai-apm-offline-demo-install.sh"
COMPOSE_CHECK_SRC="${APM_COMMON_SRC}/scripts/check-compose.sh"

resolve_docker_pkg() {
  local name="$DOCKER_PKG_NAME"
  local candidates=(
    "${APM_BUILD_DIST}/${name}"
    "${APM_IMAGES_ROOT}/dist/${name}"
    "${TMPDIR:-/tmp}/databuff-apm-docker-dist/${name}"
  )
  local c
  for c in "${candidates[@]}"; do
    if [[ -f "$c" ]]; then
      printf '%s\n' "$c"
      return 0
    fi
  done
  return 1
}

resolve_demo_pkg() {
  local name="$DEMO_PKG_NAME"
  local candidates=(
    "${APM_BUILD_DIST}/${name}"
    "${APM_IMAGES_ROOT}/dist/${name}"
    "${TMPDIR:-/tmp}/databuff-apm-docker-dist/${name}"
  )
  local c
  for c in "${candidates[@]}"; do
    if [[ -f "$c" ]]; then
      printf '%s\n' "$c"
      return 0
    fi
  done
  return 1
}

resolve_apm_stack_tarball() {
  local arch="$1"
  local name candidates c
  name="$(image_tarball_name ai-apm-stack "$RELEASE_VERSION" "$arch")"
  candidates=(
    "${APM_IMAGES_DIR}/${name}"
    "${APM_IMAGES_ROOT}/dist/${RELEASE_VERSION}/images/${name}"
  )
  for c in "${candidates[@]}"; do
    if [[ -f "$c" ]]; then
      printf '%s\n' "$c"
      return 0
    fi
  done
  return 1
}

offline_arch_list() {
  local item
  if [[ -n "${OFFLINE_ARCHES:-}" ]]; then
    for item in ${OFFLINE_ARCHES//,/ }; do
      printf '%s\n' "$item"
    done
    return 0
  fi
  image_arch_list
}

ensure_offline_dependency() {
  local path="$1"
  local hint="$2"
  if [[ -f "$path" ]]; then
    return 0
  fi
  if [[ "${AUTO_BUILD_DEPS:-0}" == "1" ]]; then
    echo "[build-docker-offline] missing ${path}, running: ${hint}"
    eval "$hint"
    [[ -f "$path" ]] || {
      echo "[build-docker-offline] still missing after build: ${path}" >&2
      exit 1
    }
    return 0
  fi
  echo "[build-docker-offline] missing: ${path}" >&2
  echo "[build-docker-offline] run: ${hint}" >&2
  echo "[build-docker-offline] or set AUTO_BUILD_DEPS=1" >&2
  exit 1
}

ensure_offline_prerequisites() {
  local sample_arch
  sample_arch="$(offline_arch_list | head -1)"
  if ! resolve_docker_pkg >/dev/null; then
    ensure_offline_dependency \
      "${APM_BUILD_DIST}/${DOCKER_PKG_NAME}" \
      "${APM_DEPLOY_ROOT}/docker/build-docker.sh"
  fi
  if ! resolve_apm_stack_tarball "$sample_arch" >/dev/null; then
    ensure_offline_dependency \
      "${APM_IMAGES_DIR}/$(image_tarball_name ai-apm-stack "$RELEASE_VERSION" "$sample_arch")" \
      "${APM_DEPLOY_ROOT}/images/build-images.sh"
  fi
  if ! resolve_demo_pkg >/dev/null; then
    ensure_offline_dependency \
      "${APM_BUILD_DIST}/${DEMO_PKG_NAME}" \
      "${APM_DEPLOY_ROOT}/docker/build-docker.sh"
  fi
}

resolve_infra_tarball() {
  local arch="$1"
  local name local_path remote_url tmp_path candidates=()

  name="$(image_tarball_name doris-stack "$DORIS_VERSION" "$arch")"
  candidates=(
    "${INFRA_IMAGES_DIR}/${name}"
    "${APM_IMAGES_ROOT}/dist/infra/images/${name}"
  )
  for local_path in "${candidates[@]}"; do
    if [[ -f "$local_path" ]]; then
      if gzip -t "$local_path" 2>/dev/null; then
        echo "[build-docker-offline] reuse local infra pkg: ${name} (${local_path})" >&2
        printf '%s\n' "$local_path"
        return 0
      fi
      echo "[build-docker-offline] invalid local infra pkg, skip: ${local_path}" >&2
      if [[ "$local_path" == "${INFRA_IMAGES_DIR}/"* ]]; then
        rm -f "$local_path" "${local_path}.size"
      fi
    fi
  done

  local_path="${INFRA_IMAGES_DIR}/${name}"
  remote_url="$(infra_images_pkg_base_url)/${name}"
  echo "[build-docker-offline] no local infra pkg, fetching ${name} from ${remote_url} ..."
  mkdir -p "$INFRA_IMAGES_DIR"
  tmp_path="${local_path}.partial"
  rm -f "$tmp_path"
  if ! curl -fsSL "$remote_url" -o "$tmp_path"; then
    rm -f "$tmp_path"
    echo "[build-docker-offline] failed to download ${name}" >&2
    echo "[build-docker-offline] run: ./deploy/images/upload-infra-images.sh" >&2
    return 1
  fi
  if ! gzip -t "$tmp_path" 2>/dev/null; then
    rm -f "$tmp_path"
    echo "[build-docker-offline] downloaded file is not a valid gzip tarball: ${name}" >&2
    return 1
  fi
  mv -f "$tmp_path" "$local_path"
  write_image_tarball_size_sidecar "$local_path"
  printf '%s\n' "$local_path"
}

stage_offline_package() {
  local arch="$1"
  local stage_parent="$2"
  local pkg_name stage_dir archive apm_stack doris_stack demo_pkg

  pkg_name="databuff-ai-apm-offline-${RELEASE_VERSION}-${arch}"
  stage_dir="${stage_parent}/${pkg_name}"
  archive="${APM_BUILD_DIST}/${pkg_name}.tar.gz"
  docker_pkg="$(resolve_docker_pkg)" || exit 1
  demo_pkg="$(resolve_demo_pkg)" || exit 1
  apm_stack="$(resolve_apm_stack_tarball "$arch")" || exit 1
  doris_stack="$(resolve_infra_tarball "$arch")" || exit 1
  for f in "$docker_pkg" "$demo_pkg" "$apm_stack" "$doris_stack"; do
    [[ -f "$f" ]] || {
      echo "[build-docker-offline] missing file: ${f}" >&2
      exit 1
    }
  done

  mkdir -p "${stage_dir}/scripts"
  cp -f "$OFFLINE_INSTALL_SRC" "${stage_dir}/install.sh"
  cp -f "$OFFLINE_DEMO_INSTALL_SRC" "${stage_dir}/install_demo.sh"
  cp -f "$COMPOSE_CHECK_SRC" "${stage_dir}/scripts/check-compose.sh"
  cp -f "$docker_pkg" "${stage_dir}/${DOCKER_PKG_NAME}"
  cp -f "$demo_pkg" "${stage_dir}/${DEMO_PKG_NAME}"
  cp -f "$apm_stack" "${stage_dir}/"
  cp -f "$doris_stack" "${stage_dir}/"
  printf '%s\n' "$RELEASE_VERSION" >"${stage_dir}/VERSION"
  printf '%s\n' "$arch" >"${stage_dir}/ARCH"
  chmod +x "${stage_dir}/install.sh" "${stage_dir}/install_demo.sh" "${stage_dir}/scripts/check-compose.sh"

  create_tarball "$stage_dir" "$archive"
  write_image_tarball_size_sidecar "$archive"
  printf '%s\n' "$archive"
}

ensure_command python3
log_pkg_publish_targets
ensure_offline_prerequisites

STAGE_PARENT="$(mktemp -d "${TMPDIR:-/tmp}/databuff-offline-stage.XXXXXX")"
cleanup_stage() {
  rm -rf "$STAGE_PARENT"
}
trap cleanup_stage EXIT

mkdir -p "$APM_BUILD_DIST"
OFFLINE_ARCHIVES=()
while IFS= read -r arch; do
  [[ -n "$arch" ]] || continue
  echo "[build-docker-offline] staging offline bundle (${arch}) ..."
  OFFLINE_ARCHIVES+=("$(stage_offline_package "$arch" "$STAGE_PARENT")")
done < <(offline_arch_list)

upload_files=()
for archive in "${OFFLINE_ARCHIVES[@]}"; do
  upload_files+=("$archive" "${archive}.size")
done
publish_files_to_remote "$(version_offline_pkg_remote_dir "$RELEASE_VERSION")" "${upload_files[@]}"

cat <<EOF

[build-docker-offline] done
  Version : ${RELEASE_VERSION}
  Arch    : $(offline_arch_list | paste -sd, -)
  Local   : ${APM_BUILD_DIST}/databuff-ai-apm-offline-${RELEASE_VERSION}-<arch>.tar.gz
  Remote  : $(pkg_base_url)/${RELEASE_VERSION}/offline/

目标机安装:
  tar -xzf databuff-ai-apm-offline-${RELEASE_VERSION}-<arch>.tar.gz
  cd databuff-ai-apm-offline-${RELEASE_VERSION}-<arch>
  sudo ./install.sh
  sudo ./install_demo.sh    # 可选：离线安装 demo 造数

EOF
