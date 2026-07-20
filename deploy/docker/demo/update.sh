#!/usr/bin/env bash
# DataBuff AI APM Demo 就地升级（在安装目录执行）
#
#   cd /opt/databuff-ai-apm-demo && ./update.sh
#   ./update.sh --version 0.1.5 --pull-images
#
# 环境变量:
#   APM_PKG_BASE       部署包地址
#   APM_INSTALL_DIR    安装目录 (默认当前目录)
#   APM_VERSION        目标版本
#   FORCE_PULL_IMAGES  1=强制重新下载 demo 镜像
#   SKIP_PULL_IMAGES   1=不下载镜像
#   SKIP_START         1=仅更新文件与镜像，不启动
#   INGEST_HOST        ingest 地址
#   INGEST_PORT        ingest 端口 (默认 4318)

set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
INSTALL_DIR="${APM_INSTALL_DIR:-$ROOT}"
FORCE_PULL_IMAGES="${FORCE_PULL_IMAGES:-0}"
SKIP_PULL_IMAGES="${SKIP_PULL_IMAGES:-0}"
SKIP_START="${SKIP_START:-0}"
INGEST_PORT="${INGEST_PORT:-4318}"

# 先记录外部显式指定的目标版本（wrapper 传入的 APM_VERSION 或后续 --version）。
# env.sh 里写死的 export APM_VERSION=<安装版本> 不能覆盖它，否则升级会被钉回旧版。
REQUESTED_VERSION="${APM_VERSION:-}"

if [[ -f "${ROOT}/env.sh" ]]; then
  set -a
  # shellcheck disable=SC1091
  . "${ROOT}/env.sh"
  set +a
fi
[[ -n "$REQUESTED_VERSION" ]] && APM_VERSION="$REQUESTED_VERSION"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version=*)
      APM_VERSION="${1#--version=}"
      shift
      ;;
    --version | -v)
      [[ $# -ge 2 ]] || {
        echo "[demo-update] ERROR: --version requires a value" >&2
        exit 1
      }
      APM_VERSION="$2"
      shift 2
      ;;
    --pull-images | -f)
      FORCE_PULL_IMAGES=1
      shift
      ;;
    --skip-start)
      SKIP_START=1
      shift
      ;;
    *)
      shift
      ;;
  esac
done

CYN='\033[36m'
GRN='\033[32m'
YLW='\033[33m'
RED='\033[31m'
DIM='\033[2m'
BLD='\033[1m'
RST='\033[0m'

log() {
  echo -e "${CYN}[demo-update]${RST} $*"
}

log_done() {
  echo -e "${CYN}[demo-update]${RST} $1 ${GRN}... 完成${RST}"
}

log_skip() {
  echo -e "${CYN}[demo-update]${RST} $1 ${YLW}... 跳过${RST}"
}

fail() {
  echo -e "${RED}[demo-update] ERROR:${RST} $*" >&2
  exit 1
}

if [[ -f "${ROOT}/scripts/demo-deploy-lib.sh" ]]; then
  # shellcheck disable=SC1091
  . "${ROOT}/scripts/demo-deploy-lib.sh"
elif [[ -f "${ROOT}/../../common/scripts/demo-deploy-lib.sh" ]]; then
  # shellcheck disable=SC1091
  . "${ROOT}/../../common/scripts/demo-deploy-lib.sh"
else
  fail "缺少 demo-deploy-lib.sh"
fi

PKG_BASE="${APM_PKG_BASE:-${PKG_BASE:-}}"
export PKG_BASE APM_PKG_BASE="${APM_PKG_BASE:-$PKG_BASE}"

if [[ -f "${ROOT}/scripts/resolve-install-version.sh" ]]; then
  # shellcheck disable=SC1091
  . "${ROOT}/scripts/resolve-install-version.sh"
elif [[ -n "${APM_PKG_BASE:-}" ]]; then
  _version_lib="$(mktemp "${TMPDIR:-/tmp}/resolve-install-version.XXXXXX.sh")"
  if ! curl -fsSL "${APM_PKG_BASE%/}/resolve-install-version.sh" -o "$_version_lib"; then
    rm -f "$_version_lib"
    fail "无法下载 resolve-install-version.sh"
  fi
  # shellcheck disable=SC1090
  . "$_version_lib"
  rm -f "$_version_lib"
fi

if declare -F resolve_apm_install_version >/dev/null 2>&1; then
  APM_VERSION="$(resolve_apm_install_version)"
fi
[[ -n "${APM_VERSION:-}" ]] || fail "无法解析目标版本，请设置 APM_VERSION 或 --version"

if declare -F export_apm_pkg_download_env >/dev/null 2>&1; then
  export_apm_pkg_download_env
fi

demo_install_dir_ready "$INSTALL_DIR" || fail "未找到 Demo 安装目录 ${INSTALL_DIR}，请先执行 ai-apm-demo-install.sh"

CURRENT_VERSION="$(demo_read_installed_version "$INSTALL_DIR" || echo unknown)"
DEMO_PKG="databuff-apm-demo-${APM_VERSION}.tar.gz"

INGEST_HOST="${INGEST_HOST:-}"
if [[ -z "$INGEST_HOST" ]]; then
  INGEST_HOST="$(demo_detect_host_ip || true)"
fi
[[ -n "$INGEST_HOST" ]] || fail "无法获取本机 IP，请设置 INGEST_HOST"

echo ""
echo -e "${CYN}========================================================${RST}"
echo -e "${BLD} DataBuff AI APM Demo  就地升级${RST}"
echo -e "${DIM} ${CURRENT_VERSION} → ${APM_VERSION}${RST}"
echo -e "${CYN}========================================================${RST}"
echo ""

log "${BLD}(1/4)${RST} 停止当前 demo"
demo_stop_running "$INSTALL_DIR"
log_done "${BLD}(1/4)${RST} 停止当前 demo"

if [[ "$SKIP_PULL_IMAGES" != "1" && -f "${ROOT}/scripts/image-pkg.sh" ]]; then
  export APM_VERSION
  if [[ "$FORCE_PULL_IMAGES" == "1" ]]; then
    export FORCE_PULL_IMAGES=1
  fi
  log "${BLD}(2/4)${RST} 更新 demo 镜像"
  # shellcheck disable=SC1091
  . "${ROOT}/scripts/image-pkg.sh"
  load_demo_image_from_pkg
  log_done "${BLD}(2/4)${RST} 更新 demo 镜像"
else
  log_skip "${BLD}(2/4)${RST} 更新 demo 镜像"
fi

log "${BLD}(3/4)${RST} 同步部署文件"
TMP_PKG="$(mktemp "${TMPDIR:-/tmp}/apm-demo-update.XXXXXX.tar.gz")"
STAGING="$(mktemp -d "${TMPDIR:-/tmp}/apm-demo-update-stage.XXXXXX")"
PKG_URL="$(apm_docker_pkg_download_url "$DEMO_PKG")"
curl -fsSL "$PKG_URL" -o "$TMP_PKG"
tar -xzf "$TMP_PKG" -C "$STAGING" --strip-components=1
demo_sync_deploy_bundle "$STAGING" "$INSTALL_DIR"
rm -rf "$STAGING" "$TMP_PKG"
log_done "${BLD}(3/4)${RST} 同步部署文件"

if [[ "$SKIP_START" == "1" ]]; then
  log_skip "${BLD}(4/4)${RST} 启动 demo (SKIP_START=1)"
else
  log "${BLD}(4/4)${RST} 启动 demo"
  cd "$INSTALL_DIR"
  INGEST_HOST="$INGEST_HOST" INGEST_PORT="$INGEST_PORT" ./start.sh
  log_done "${BLD}(4/4)${RST} 启动 demo"
fi

echo ""
echo -e "${GRN}${BLD} Demo 升级完成${RST}  ${CURRENT_VERSION} → ${APM_VERSION}"
echo -e "${DIM} OTLP http://${INGEST_HOST}:${INGEST_PORT}/v1/traces${RST}"
echo ""
