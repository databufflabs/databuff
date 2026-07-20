#!/usr/bin/env bash
# DataBuff AI APM 一键安装（Doris + ingest + web）
#
# 全新安装 / 重装：会删除安装目录（含 data/ 观测数据）。
# 保留数据升级请使用 update.sh / ai-apm-update.sh。
#
#   curl -fsSL https://databuff.ai/databuff/ai-apm-install.sh | bash
#   curl -fsSL https://databuff.ai/databuff/ai-apm-install.sh | bash -s -- --pull-images
#
# 安装过程会按本机架构从 ${APM_PKG_BASE}/${APM_VERSION}/images 与 infra/images 下载镜像包并 docker load。
# 本地已有对应版本镜像时默认跳过下载；加 --pull-images 强制重新下载。start.sh 仅负责启动服务。
#
# 环境变量:
#   APM_PKG_BASE       部署包地址 (默认 https://databuff.ai/databuff)
#   APM_INSTALL_DIR    安装目录 (默认 /opt/databuff-ai-apm)
#   APM_VERSION        指定版本号 (默认从 ${APM_PKG_BASE}/VERSION 读取最新版)
#   SKIP_START         1=仅下载解压不启动
#   FORCE_PULL_IMAGES  1=强制重新下载镜像（同 --pull-images）
#
# 指定版本:
#   curl -fsSL .../ai-apm-install.sh | bash -s -- --version 0.1.4
#   APM_VERSION=0.1.4 curl -fsSL .../ai-apm-install.sh | bash

set -e

cd /opt 2>/dev/null || cd "${TMPDIR:-/tmp}" 2>/dev/null || true

PKG_BASE="${APM_PKG_BASE:-__APM_PKG_BASE__}"
export APM_PKG_BASE="$PKG_BASE"
BUILTIN_APM_VERSION="__APM_VERSION__"

FORCE_PULL_IMAGES="${FORCE_PULL_IMAGES:-0}"
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
    --pull-images | -f)
      FORCE_PULL_IMAGES=1
      shift
      ;;
    *)
      shift
      ;;
  esac
done
export FORCE_PULL_IMAGES

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

INSTALL_DIR="${APM_INSTALL_DIR:-/opt/databuff-ai-apm}"
SKIP_START="${SKIP_START:-0}"
DOCKER_PKG="databuff-ai-apm-${APM_VERSION}.tar.gz"

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

detect_host_ip() {
  ip="127.0.0.1"
  if command -v ip >/dev/null 2>&1; then
    ip="$(ip route get 1.1.1.1 2>/dev/null | awk '{for (i=1;i<=NF;i++) if ($i=="src") {print $(i+1); exit}}')"
  fi
  if [ -z "$ip" ] && command -v hostname >/dev/null 2>&1; then
    ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
  fi
  if [ -z "$ip" ]; then
    ip="127.0.0.1"
  fi
  echo "$ip"
}

show_summary() {
  if [ "$INSTALL_SUMMARY_PRINTED" = "1" ]; then
    return 0
  fi
  INSTALL_SUMMARY_PRINTED=1

  host_ip="$(detect_host_ip)"

  echo ""
  echo -e "${CYN}========================================================${RST}"
  echo -e "${GRN}${BLD} 安装完成${RST}"
  echo -e "${CYN}========================================================${RST}"
  echo ""
  echo -e "  ${CYN}Web UI${RST}"
  echo "    http://${host_ip}:27403"
  echo -e "  ${CYN}账号${RST}"
  echo -e "    admin / ${YLW}Databuff@123${RST}"
  echo -e "  ${CYN}Ingest${RST}"
  echo "    http://${host_ip}:4318/v1/traces"
  echo ""
  echo -e "  ${DIM}安装目录${RST}"
  echo "    ${INSTALL_DIR}"
  echo -e "  ${DIM}启动${RST}"
  echo "    cd ${INSTALL_DIR} && ./start.sh"
  echo -e "  ${DIM}停止${RST}"
  echo "    cd ${INSTALL_DIR} && ./stop.sh"
  echo -e "  ${DIM}Demo造数${RST}"
  echo "    curl -fsSL ${PKG_BASE}/ai-apm-demo-install.sh | bash"
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
  if [ -f "${INSTALL_DIR}/scripts/compose-env.sh" ]; then
    # shellcheck disable=SC1091
    . "${INSTALL_DIR}/scripts/compose-env.sh"
    (cd "$INSTALL_DIR" && compose_down) >/dev/null 2>&1 || true
  fi
  cd /opt 2>/dev/null || cd "${TMPDIR:-/tmp}" 2>/dev/null || true
  rm -rf "$INSTALL_DIR"
}

echo ""
echo -e "${CYN}========================================================${RST}"
echo -e "${BLD} DataBuff AI APM  一键安装 v${APM_VERSION}${RST}"
echo -e "${DIM} 全新安装（将清理旧安装目录与 data/）${RST}"
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
_compose_check_lib=""
_src="${BASH_SOURCE[0]:-$0}"
if [[ -n "$_src" && "$_src" != /dev/fd/* && "$_src" != /dev/stdin && "$_src" != - ]]; then
  _dir="$(cd "$(dirname "$_src")" && pwd)"
  for _f in "${_dir}/../common/scripts/check-compose.sh"; do
    if [[ -f "$_f" ]]; then
      _compose_check_lib="$_f"
      break
    fi
  done
fi
if [[ -z "$_compose_check_lib" ]]; then
  _compose_check_lib="$(mktemp "${TMPDIR:-/tmp}/check-compose.XXXXXX.sh")"
  if ! curl -fsSL "${PKG_BASE%/}/check-compose.sh" -o "$_compose_check_lib"; then
    rm -f "$_compose_check_lib"
    fail "缺少 Docker Compose，且无法下载版本检查脚本"
  fi
  # shellcheck source=/dev/null
  . "$_compose_check_lib"
  rm -f "$_compose_check_lib"
else
  # shellcheck source=/dev/null
  . "$_compose_check_lib"
fi
ensure_compose_cli
_avx2_check_lib=""
if [[ -n "$_src" && "$_src" != /dev/fd/* && "$_src" != /dev/stdin && "$_src" != - ]]; then
  for _f in "${_dir}/../common/scripts/check-avx2.sh"; do
    if [[ -f "$_f" ]]; then
      _avx2_check_lib="$_f"
      break
    fi
  done
fi
if [[ -z "$_avx2_check_lib" ]]; then
  _avx2_check_lib="$(mktemp "${TMPDIR:-/tmp}/check-avx2.XXXXXX.sh")"
  if ! curl -fsSL "${PKG_BASE%/}/check-avx2.sh" -o "$_avx2_check_lib"; then
    rm -f "$_avx2_check_lib"
    fail "无法下载 AVX2 检查脚本"
  fi
  # shellcheck source=/dev/null
  . "$_avx2_check_lib"
  rm -f "$_avx2_check_lib"
else
  # shellcheck source=/dev/null
  . "$_avx2_check_lib"
fi
ensure_avx2_cpu
log_done "${BLD}(1/5)${RST} 检查运行环境"

log "${BLD}(2/5)${RST} 下载部署包"
TMP="$(mktemp "${TMPDIR:-/tmp}/apm-install.XXXXXX.tar.gz")"
PKG_URL="$(apm_docker_pkg_download_url "$DOCKER_PKG")"
curl -fsSL "$PKG_URL" -o "$TMP"
log_done "${BLD}(2/5)${RST} 下载部署包"

log "${BLD}(3/5)${RST} 加载镜像"
STAGING="$(mktemp -d "${TMPDIR:-/tmp}/apm-install-stage.XXXXXX")"
tar -xzf "$TMP" -C "$STAGING" --strip-components=1
rm -f "$TMP"
chmod +x "${STAGING}/start.sh" "${STAGING}/stop.sh" "${STAGING}/reset-table.sh" 2>/dev/null || true
chmod +x "${STAGING}/scripts/"*.sh 2>/dev/null || true
export ROOT="$STAGING"
export FORCE_PULL_IMAGES
"${STAGING}/scripts/pull-images.sh"
log_done "${BLD}(3/5)${RST} 加载镜像"

log "${BLD}(4/5)${RST} 清理旧版本"
if [ -e "$INSTALL_DIR" ]; then
  stop_old_install
  log_done "${BLD}(4/5)${RST} 清理旧版本"
else
  log_skip "${BLD}(4/5)${RST} 清理旧版本"
fi

log "${BLD}(5/5)${RST} 安装并启动"
mkdir -p "$(dirname "$INSTALL_DIR")"
mv "$STAGING" "$INSTALL_DIR"
INSTALL_DEPLOYED=1
# shellcheck source=scripts/check-compose.sh
. "${INSTALL_DIR}/scripts/check-compose.sh"
ensure_compose_cli
apm_materialize_compose_file "$INSTALL_DIR"
log_done "${BLD}(5/5)${RST} 安装到 ${INSTALL_DIR}"

if [ "$SKIP_START" = "1" ]; then
  log_skip "启动服务 (SKIP_START=1)"
else
  cd "$INSTALL_DIR"
  START_SKIP_SUMMARY=1 ./start.sh
  log_done "启动服务"
fi
