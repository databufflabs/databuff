#!/usr/bin/env bash
# DataBuff AI APM 离线安装（解压离线包后在本目录执行）
#
# 全新安装 / 重装：会删除安装目录（含 data/）。
# 保留数据升级请使用同包内 update.sh。
#   tar -xzf databuff-ai-apm-offline-0.1.3-amd64.tar.gz
#   cd databuff-ai-apm-offline-0.1.3-amd64
#   sudo ./install.sh
#
# 环境变量:
#   APM_INSTALL_DIR  安装目录 (默认 /opt/databuff-ai-apm)
#   SKIP_START         1=仅安装不启动
#   FORCE_LOAD_IMAGES  1=强制重新 docker load（即使本地已有镜像）

set -e

BUNDLE_ROOT="$(cd "$(dirname "$0")" && pwd)"
INSTALL_DIR="${APM_INSTALL_DIR:-/opt/databuff-ai-apm}"
SKIP_START="${SKIP_START:-0}"
FORCE_LOAD_IMAGES="${FORCE_LOAD_IMAGES:-0}"

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
  echo -e "  ${DIM}离线升级（保留 data/）${RST}"
  echo "    解压新版本离线包后在其目录执行: sudo ./update.sh"
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

require_bundle_file() {
  local pattern="$1"
  local label="$2"
  local matches=()
  local f

  shopt -s nullglob
  matches=(${pattern})
  shopt -u nullglob

  if [ "${#matches[@]}" -eq 0 ]; then
    fail "离线包缺少 ${label}（期望 ${pattern}）"
  fi
  if [ "${#matches[@]}" -gt 1 ]; then
    fail "离线包存在多个 ${label}，请保留与目标架构匹配的一个"
  fi
  printf '%s\n' "${matches[0]}"
}

docker_image_exists() {
  docker image inspect "$1" >/dev/null 2>&1
}

load_image_tarball() {
  local tarball="$1"
  local label="$2"

  echo "[install]   导入 ${label} ..."
  if [[ "$tarball" == *.gz ]]; then
    gunzip -c "$tarball" | docker load
  else
    docker load -i "$tarball"
  fi
}

load_bundle_images() {
  local apm_stack doris_stack apm_tar doris_tar
  local version arch

  if [ ! -f "${BUNDLE_ROOT}/VERSION" ]; then
    fail "离线包缺少 VERSION 文件"
  fi
  version="$(tr -d '[:space:]' <"${BUNDLE_ROOT}/VERSION")"
  arch="$(tr -d '[:space:]' <"${BUNDLE_ROOT}/ARCH" 2>/dev/null || true)"

  apm_tar="$(require_bundle_file "${BUNDLE_ROOT}/ai-apm-stack-${version}-"'*.tar.gz' "APM 镜像包")"
  doris_tar="$(require_bundle_file "${BUNDLE_ROOT}/doris-stack-"'*.tar.gz' "Doris 镜像包")"

  if [ -z "$arch" ]; then
    case "$apm_tar" in
      *-arm64.tar.gz) arch=arm64 ;;
      *-amd64.tar.gz) arch=amd64 ;;
      *) arch=unknown ;;
    esac
  fi

  log_sub() {
    echo -e "${CYN}[install]${RST} ${DIM}       $*${RST}"
  }

  log "${BLD}(2/4)${RST} 加载镜像 (arch=${arch})"
  log_sub "$(basename "$apm_tar")"
  log_sub "$(basename "$doris_tar")"

  if [ "$FORCE_LOAD_IMAGES" = "1" ]; then
    load_image_tarball "$apm_tar" "APM stack"
    load_image_tarball "$doris_tar" "Doris stack"
  else
    apm_stack="${RUNTIME_IMAGE_NAMESPACE:-databuffhub}/ai-apm-ingest:${version}"
    doris_fe="${DORIS_FE_IMAGE:-apache/doris:fe-4.1.1}"
    if docker_image_exists "$apm_stack" && docker_image_exists "$doris_fe"; then
      log_skip "${BLD}(2/4)${RST} 加载镜像（本地已存在，设 FORCE_LOAD_IMAGES=1 可强制重载）"
      return 0
    fi
    load_image_tarball "$apm_tar" "APM stack"
    load_image_tarball "$doris_tar" "Doris stack"
  fi
  log_done "${BLD}(2/4)${RST} 加载镜像"
}

if [ ! -f "${BUNDLE_ROOT}/VERSION" ]; then
  fail "请在离线包解压目录内执行 install.sh"
fi
APM_VERSION="$(tr -d '[:space:]' <"${BUNDLE_ROOT}/VERSION")"
DEPLOY_PKG="$(require_bundle_file "${BUNDLE_ROOT}/databuff-ai-apm-${APM_VERSION}.tar.gz" "部署包")"

echo ""
echo -e "${CYN}========================================================${RST}"
echo -e "${BLD} DataBuff AI APM  离线安装 v${APM_VERSION}${RST}"
echo -e "${DIM} 全新安装（将清理旧安装目录与 data/）${RST}"
echo -e "${CYN}========================================================${RST}"
echo ""

log "${BLD}(1/4)${RST} 检查运行环境"
if [ "$(id -u)" -ne 0 ]; then
  fail "请使用 root 运行"
fi
for cmd in tar docker; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    fail "缺少命令: $cmd"
  fi
done
if ! docker info >/dev/null 2>&1; then
  fail "Docker 不可用"
fi
if [ -f "${BUNDLE_ROOT}/scripts/check-compose.sh" ]; then
  # shellcheck source=/dev/null
  . "${BUNDLE_ROOT}/scripts/check-compose.sh"
else
  fail "离线包缺少 scripts/check-compose.sh"
fi
ensure_compose_cli
if [ -f "${BUNDLE_ROOT}/scripts/check-avx2.sh" ]; then
  # shellcheck source=/dev/null
  . "${BUNDLE_ROOT}/scripts/check-avx2.sh"
else
  fail "离线包缺少 scripts/check-avx2.sh"
fi
ensure_avx2_cpu
log_done "${BLD}(1/4)${RST} 检查运行环境"

load_bundle_images

log "${BLD}(3/4)${RST} 清理旧版本"
if [ -e "$INSTALL_DIR" ]; then
  stop_old_install
  log_done "${BLD}(3/4)${RST} 清理旧版本"
else
  log_skip "${BLD}(3/4)${RST} 清理旧版本"
fi

log "${BLD}(4/4)${RST} 安装并启动"
STAGING="$(mktemp -d "${TMPDIR:-/tmp}/apm-offline-stage.XXXXXX")"
tar -xzf "$DEPLOY_PKG" -C "$STAGING" --strip-components=1
chmod +x "${STAGING}/start.sh" "${STAGING}/stop.sh" "${STAGING}/reset-table.sh" 2>/dev/null || true
chmod +x "${STAGING}/scripts/"*.sh 2>/dev/null || true
mkdir -p "$(dirname "$INSTALL_DIR")"
mv "$STAGING" "$INSTALL_DIR"
INSTALL_DEPLOYED=1
# shellcheck source=scripts/check-compose.sh
. "${INSTALL_DIR}/scripts/check-compose.sh"
ensure_compose_cli
apm_materialize_compose_file "$INSTALL_DIR"
log_done "${BLD}(4/4)${RST} 安装到 ${INSTALL_DIR}"

if [ "$SKIP_START" = "1" ]; then
  log_skip "启动服务 (SKIP_START=1)"
else
  cd "$INSTALL_DIR"
  START_SKIP_SUMMARY=1 ./start.sh
  log_done "启动服务"
fi
