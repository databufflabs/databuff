#!/usr/bin/env bash
# DataBuff AI APM Demo 造数一键安装
#
#   curl -fsSL https://databuff.ai/databuff/ai-apm-demo-install.sh | bash
#
# 环境变量:
#   APM_PKG_BASE     部署包地址
#   APM_INSTALL_DIR  安装目录 (默认 /opt/databuff-ai-apm-demo)
#   APM_VERSION      指定版本号 (默认从 ${APM_PKG_BASE}/VERSION 读取最新版)
#   INGEST_HOST      ingest 地址 (默认本机 IP)
#   INGEST_PORT      ingest 端口 (默认 4318)
#   SKIP_START       1=仅下载解压不启动
#
# 指定版本:
#   curl -fsSL .../ai-apm-demo-install.sh | bash -s -- --version 0.1.2
#   APM_VERSION=0.1.2 curl -fsSL .../ai-apm-demo-install.sh | bash

set -e

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
        echo "[install] ERROR: --version requires a value" >&2
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
      echo "[install] ERROR: cannot download resolve-install-version.sh from ${PKG_BASE}" >&2
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

INSTALL_DIR="${APM_INSTALL_DIR:-/opt/databuff-ai-apm-demo}"
INGEST_PORT="${INGEST_PORT:-4318}"
SKIP_START="${SKIP_START:-0}"
DEMO_PKG="databuff-apm-demo-${APM_VERSION}.tar.gz"

CYN='\033[36m'
GRN='\033[32m'
YLW='\033[33m'
BLU='\033[34m'
RED='\033[31m'
DIM='\033[2m'
BLD='\033[1m'
RST='\033[0m'

INSTALL_DEPLOYED=0
INSTALL_SUMMARY_PRINTED=0

log() {
  echo -e "${CYN}[install]${RST} $*"
}

log_sub() {
  echo -e "${CYN}[install]${RST} ${DIM}       $*${RST}"
}

log_done() {
  echo -e "${CYN}[install]${RST} $1 ${GRN}... 完成${RST}"
}

log_skip() {
  echo -e "${CYN}[install]${RST} $1 ${YLW}... 跳过${RST}"
}

fail() {
  echo -e "${RED}[install] ERROR:${RST} $*" >&2
  exit 1
}

_demo_lib=""
_src="${BASH_SOURCE[0]:-$0}"
if [[ -n "$_src" && "$_src" != /dev/fd/* && "$_src" != /dev/stdin && "$_src" != - ]]; then
  _dir="$(cd "$(dirname "$_src")" && pwd)"
  for _f in "${_dir}/../common/scripts/demo-deploy-lib.sh"; do
    if [[ -f "$_f" ]]; then
      _demo_lib="$_f"
      break
    fi
  done
fi
if [[ -z "$_demo_lib" ]]; then
  _demo_lib="$(mktemp "${TMPDIR:-/tmp}/demo-deploy-lib.XXXXXX.sh")"
  if ! curl -fsSL "${PKG_BASE%/}/demo-deploy-lib.sh" -o "$_demo_lib"; then
    rm -f "$_demo_lib"
    echo "[install] ERROR: cannot download demo-deploy-lib.sh from ${PKG_BASE}" >&2
    exit 1
  fi
fi
# shellcheck disable=SC1090,SC1091
source "$_demo_lib"
if [[ "$_demo_lib" == "${TMPDIR:-/tmp}/demo-deploy-lib."* ]]; then
  rm -f "$_demo_lib"
fi

detect_host_ip() {
  demo_detect_host_ip
}

show_summary() {
  if [ "$INSTALL_SUMMARY_PRINTED" = "1" ]; then
    return 0
  fi
  INSTALL_SUMMARY_PRINTED=1

  echo ""
  echo -e "${CYN}========================================================${RST}"
  echo -e "${GRN}${BLD} 安装完成${RST}"
  echo -e "${CYN}========================================================${RST}"
  echo ""
  echo -e "  ${CYN}OTLP${RST}"
  echo "    http://${INGEST_HOST}:${INGEST_PORT}/v1/traces"
  echo ""
  echo -e "  ${DIM}安装目录${RST}"
  echo "    ${INSTALL_DIR}"
  echo -e "  ${DIM}启动${RST}"
  echo "    cd ${INSTALL_DIR} && ./start.sh"
  echo -e "  ${DIM}停止${RST}"
  echo "    cd ${INSTALL_DIR} && ./stop.sh"
  echo -e "  ${DIM}升级${RST}"
  echo "    cd ${INSTALL_DIR} && ./update.sh"
  echo "    curl -fsSL ${PKG_BASE}/ai-apm-demo-update.sh | bash"
  echo ""
  echo -e "${CYN}========================================================${RST}"
  echo ""
}

on_exit() {
  exit_code=$?
  if [ "$exit_code" -eq 0 ] && [ "$INSTALL_DEPLOYED" = "1" ]; then
    show_summary
  fi
}
trap on_exit EXIT

stop_old_install() {
  if [ ! -e "$INSTALL_DIR" ]; then
    return 0
  fi
  demo_remove_install_dir "$INSTALL_DIR"
  cd /opt 2>/dev/null || cd "${TMPDIR:-/tmp}" 2>/dev/null || true
}

INGEST_HOST="${INGEST_HOST:-}"
if [ -z "$INGEST_HOST" ]; then
  INGEST_HOST="$(detect_host_ip || true)"
fi
if [ -z "$INGEST_HOST" ]; then
  fail "无法获取本机 IP，请设置 INGEST_HOST"
fi

echo ""
echo -e "${CYN}========================================================${RST}"
echo -e "${BLD} DataBuff AI APM Demo  一键安装 v${APM_VERSION}${RST}"
echo -e "${DIM} 全自动执行，无需输入，请稍候${RST}"
echo -e "${CYN}========================================================${RST}"
echo ""

log "${BLD}(1/5)${RST} 检查运行环境"
if [ "$(id -u)" -ne 0 ]; then
  fail "请使用 root 运行"
fi
for cmd in curl tar docker; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    fail "缺少命令: $cmd"
  fi
done
if ! docker info >/dev/null 2>&1; then
  fail "Docker 不可用"
fi
log_done "${BLD}(1/5)${RST} 检查运行环境"

log "${BLD}(2/5)${RST} 清理旧版本"
if [ -e "$INSTALL_DIR" ]; then
  stop_old_install
  log_done "${BLD}(2/5)${RST} 清理旧版本"
else
  log_skip "${BLD}(2/5)${RST} 清理旧版本"
fi

log "${BLD}(3/5)${RST} 下载部署包"
TMP="$(mktemp "${TMPDIR:-/tmp}/apm-demo-install.XXXXXX.tar.gz")"
PKG_URL="$(apm_docker_pkg_download_url "$DEMO_PKG")"
curl -fsSL "$PKG_URL" -o "$TMP"
log_done "${BLD}(3/5)${RST} 下载部署包"

log "${BLD}(4/5)${RST} 解压部署文件"
mkdir -p "$INSTALL_DIR"
tar -xzf "$TMP" -C "$INSTALL_DIR" --strip-components=1
rm -f "$TMP"
chmod +x "${INSTALL_DIR}/start.sh" "${INSTALL_DIR}/stop.sh" "${INSTALL_DIR}/update.sh" 2>/dev/null || true
INSTALL_DEPLOYED=1
log_done "${BLD}(4/5)${RST} 解压到 ${INSTALL_DIR}"

if [ "$SKIP_START" = "1" ]; then
  log_skip "${BLD}(5/5)${RST} 启动服务 (SKIP_START=1)"
else
  log "${BLD}(5/5)${RST} 启动 demo 造数"
  log_sub "OTLP http://${INGEST_HOST}:${INGEST_PORT}/v1/traces"
  cd "$INSTALL_DIR"
  INGEST_HOST="$INGEST_HOST" INGEST_PORT="$INGEST_PORT" ./start.sh
  log_done "${BLD}(5/5)${RST} 启动 demo 造数"
fi
