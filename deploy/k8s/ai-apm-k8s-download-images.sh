#!/usr/bin/env bash
# 各 K8s 节点导入离线镜像（ingest / web / demo / Doris / ZooKeeper，databuffhub/* 短名）。
#
# 用法:
#   curl -fsSL https://databuff.ai/databuff/ai-apm-k8s-download-images.sh | bash
#   export IMAGE_LOAD_CMD=ctr; curl -fsSL https://databuff.ai/databuff/ai-apm-k8s-download-images.sh | bash
#
# 部署包内:
#   ./download-images.sh
#
# 环境变量:
#   APM_PKG_BASE         部署包下载地址 (默认 https://databuff.ai/databuff)
#   APM_IMAGES_PKG_BASE  版本镜像包目录 (默认 ${APM_PKG_BASE}/${APM_VERSION}/images)
#   APM_INFRA_IMAGES_PKG_BASE  infra 镜像包目录 (默认 ${APM_PKG_BASE}/infra/images)
#   APM_VERSION          指定版本号 (默认从 ${APM_PKG_BASE}/VERSION 读取最新版)
#   IMAGE_LOAD_CMD       docker（默认）或 ctr（k3s containerd）
#   FORCE_PULL_IMAGES=1  强制重新下载（忽略本地已有镜像）
#
# 指定版本:
#   curl -fsSL .../ai-apm-k8s-download-images.sh | bash -s -- --version 0.1.5
#   APM_VERSION=0.1.5 curl -fsSL .../ai-apm-k8s-download-images.sh | bash

set -euo pipefail

cd /opt 2>/dev/null || cd "${TMPDIR:-/tmp}" 2>/dev/null || true

PKG_BASE="${APM_PKG_BASE:-__APM_PKG_BASE__}"
export APM_PKG_BASE="$PKG_BASE"
BUILTIN_APM_VERSION="__APM_VERSION__"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version=*)
      APM_VERSION="${1#--version=}"
      shift
      ;;
    --version | -v)
      if [[ $# -lt 2 ]]; then
        echo "[download-images] ERROR: --version requires a value" >&2
        exit 1
      fi
      APM_VERSION="$2"
      shift 2
      ;;
    *)
      shift
      ;;
  esac
done

if ! declare -f resolve_apm_install_version >/dev/null 2>&1; then
  _version_lib=""
  _src="${BASH_SOURCE[0]:-$0}"
  if [[ -n "$_src" && "$_src" != /dev/fd/* && "$_src" != /dev/stdin && "$_src" != - ]]; then
    _dir="$(cd "$(dirname "$_src")" && pwd)"
    for _f in "${_dir}/../common/scripts/resolve-install-version.sh"; do
      if [[ -f "$_f" ]]; then
        _version_lib="$_f"
        break
      fi
    done
  fi
  if [[ -z "$_version_lib" ]]; then
    _version_lib="$(mktemp "${TMPDIR:-/tmp}/resolve-install-version.XXXXXX.sh")"
    if ! curl -fsSL "${PKG_BASE%/}/resolve-install-version.sh" -o "$_version_lib"; then
      rm -f "$_version_lib"
      echo "[download-images] ERROR: cannot download resolve-install-version.sh from ${PKG_BASE}" >&2
      exit 1
    fi
    # shellcheck source=/dev/null
    source "$_version_lib"
    rm -f "$_version_lib"
  else
    # shellcheck source=/dev/null
    source "$_version_lib"
  fi
fi
APM_VERSION="$(resolve_apm_install_version)"
export APM_VERSION
export_apm_pkg_download_env

resolve_script_dir() {
  local src="${BASH_SOURCE[0]:-$0}"
  if [[ -n "$src" && "$src" != /dev/fd/* && "$src" != /dev/stdin && "$src" != - ]]; then
    cd "$(dirname "$src")" && pwd
    return 0
  fi
  return 1
}

if script_dir="$(resolve_script_dir 2>/dev/null)"; then
  if [[ -x "${script_dir}/download-images.sh" ]]; then
    export APM_VERSION
    exec "${script_dir}/download-images.sh" "$@"
  fi
  if [[ -f "${script_dir}/env.sh" && -f "${script_dir}/scripts/image-pkg.sh" ]]; then
    ROOT="$script_dir"
    # shellcheck disable=SC1091
    source "${ROOT}/env.sh"
    # shellcheck disable=SC1091
    source "${ROOT}/scripts/image-pkg.sh"
    export APM_VERSION ROOT
    echo "[download-images] 加载离线镜像 (k8s)"
    load_k8s_stack_images
    echo "[download-images] 全部完成"
    exit 0
  fi
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "[download-images] ERROR: missing curl" >&2
  exit 1
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/apm-k8s-dl-images.XXXXXX")"
cleanup() {
  rm -rf "$WORK"
}
trap cleanup EXIT

curl -fsSL "${PKG_BASE}/env.sh" -o "${WORK}/env.sh"
curl -fsSL "${PKG_BASE}/image-pkg.sh" -o "${WORK}/image-pkg.sh"
# shellcheck disable=SC1091
source "${WORK}/env.sh"
# shellcheck disable=SC1091
source "${WORK}/image-pkg.sh"
export APM_VERSION
export_apm_pkg_download_env
if declare -F apm_refresh_image_refs >/dev/null 2>&1; then
  apm_refresh_image_refs
fi

echo "[download-images] 加载离线镜像 (k8s)"
load_k8s_stack_images
echo "[download-images] 全部完成"
