#!/usr/bin/env bash
# DataBuff AI APM Demo 离线安装（解压离线包后在本目录执行）
#
#   tar -xzf databuff-ai-apm-offline-0.1.3-amd64.tar.gz
#   cd databuff-ai-apm-offline-0.1.3-amd64
#   sudo ./install_demo.sh
#
# 需先安装主栈（./install.sh），或确保 INGEST_HOST 指向可用的 ingest 地址。
# 若已有旧 demo，会先停掉容器再替换为新版本（首次安装与升级均用本脚本）。
#
# 环境变量:
#   APM_INSTALL_DIR  安装目录 (默认 /opt/databuff-ai-apm-demo)
#   INGEST_HOST      ingest 地址 (默认本机 IP)
#   INGEST_PORT      ingest 端口 (默认 4318)
#   SKIP_START       1=仅安装不启动
#   FORCE_LOAD_IMAGES  1=强制重新 docker load demo 镜像

set -e

BUNDLE_ROOT="$(cd "$(dirname "$0")" && pwd)"
INSTALL_DIR="${APM_INSTALL_DIR:-/opt/databuff-ai-apm-demo}"
INGEST_PORT="${INGEST_PORT:-4318}"
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
  echo -e "${CYN}[install-demo]${RST} $*"
}

log_sub() {
  echo -e "${CYN}[install-demo]${RST} ${DIM}       $*${RST}"
}

log_done() {
  echo -e "${CYN}[install-demo]${RST} $1 ${GRN}... 完成${RST}"
}

log_skip() {
  echo -e "${CYN}[install-demo]${RST} $1 ${YLW}... 跳过${RST}"
}

fail() {
  echo -e "${RED}[install-demo] ERROR:${RST} $*" >&2
  exit 1
}

if [[ -f "${BUNDLE_ROOT}/scripts/demo-deploy-lib.sh" ]]; then
  # shellcheck disable=SC1091
  . "${BUNDLE_ROOT}/scripts/demo-deploy-lib.sh"
else
  fail "离线包缺少 scripts/demo-deploy-lib.sh"
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
  echo -e "${GRN}${BLD} Demo 安装完成${RST}"
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
  demo_stop_running "$INSTALL_DIR"
  rm -rf "$INSTALL_DIR"
  cd /opt 2>/dev/null || cd "${TMPDIR:-/tmp}" 2>/dev/null || true
}

load_demo_image() {
  local version="$1"

  if [ "$FORCE_LOAD_IMAGES" != "1" ] \
    && demo_docker_image_exists "${RUNTIME_IMAGE_NAMESPACE:-databuffhub}/ai-apm-demo:${version}"; then
    log_skip "${BLD}(2/4)${RST} 加载 demo 镜像（本地已存在，设 FORCE_LOAD_IMAGES=1 可强制重载）"
    return 0
  fi

  log "${BLD}(2/4)${RST} 加载 demo 镜像"
  demo_load_offline_image "$BUNDLE_ROOT" "$version" "$FORCE_LOAD_IMAGES"
  log_done "${BLD}(2/4)${RST} 加载 demo 镜像"
}

if [ ! -f "${BUNDLE_ROOT}/VERSION" ]; then
  fail "请在离线包解压目录内执行 install_demo.sh"
fi
APM_VERSION="$(tr -d '[:space:]' <"${BUNDLE_ROOT}/VERSION")"
DEMO_PKG="$(demo_resolve_bundle_glob "${BUNDLE_ROOT}/databuff-apm-demo-${APM_VERSION}.tar.gz" "Demo 部署包")" || exit 1

INGEST_HOST="${INGEST_HOST:-}"
if [ -z "$INGEST_HOST" ]; then
  INGEST_HOST="$(detect_host_ip || true)"
fi
if [ -z "$INGEST_HOST" ]; then
  fail "无法获取本机 IP，请设置 INGEST_HOST"
fi

echo ""
echo -e "${CYN}========================================================${RST}"
echo -e "${BLD} DataBuff AI APM Demo  离线安装 v${APM_VERSION}${RST}"
echo -e "${DIM} 无需联网，请稍候${RST}"
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
if ! docker compose version >/dev/null 2>&1 && ! command -v docker-compose >/dev/null 2>&1; then
  fail "缺少 docker compose"
fi
log_done "${BLD}(1/4)${RST} 检查运行环境"

if [ -e "$INSTALL_DIR" ]; then
  log "${BLD}(2/4)${RST} 停止旧 demo"
  demo_stop_running "$INSTALL_DIR"
  log_done "${BLD}(2/4)${RST} 停止旧 demo"
else
  log_skip "${BLD}(2/4)${RST} 停止旧 demo"
fi

load_demo_image "$APM_VERSION"

if [ -e "$INSTALL_DIR" ]; then
  log "${BLD}(3/4)${RST} 清理旧版本"
  rm -rf "$INSTALL_DIR"
  log_done "${BLD}(3/4)${RST} 清理旧版本"
else
  log_skip "${BLD}(3/4)${RST} 清理旧版本"
fi

log "${BLD}(4/4)${RST} 安装并启动"
mkdir -p "$INSTALL_DIR"
tar -xzf "$DEMO_PKG" -C "$INSTALL_DIR" --strip-components=1
chmod +x "${INSTALL_DIR}/start.sh" "${INSTALL_DIR}/stop.sh" 2>/dev/null || true
INSTALL_DEPLOYED=1
log_done "${BLD}(4/4)${RST} 安装到 ${INSTALL_DIR}"

if [ "$SKIP_START" = "1" ]; then
  log_skip "启动 demo (SKIP_START=1)"
else
  log "启动 demo 造数"
  log_sub "OTLP http://${INGEST_HOST}:${INGEST_PORT}/v1/traces"
  cd "$INSTALL_DIR"
  START_SKIP_READY=1 \
    INGEST_HOST="$INGEST_HOST" INGEST_PORT="$INGEST_PORT" ./start.sh
  log_done "启动 demo 造数"
fi
