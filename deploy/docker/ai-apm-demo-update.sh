#!/usr/bin/env bash
# DataBuff AI APM Demo 在线升级（停掉旧 demo，换成目标版本）
#
#   curl -fsSL https://databuff.ai/databuff/ai-apm-demo-update.sh | bash
#   curl -fsSL .../ai-apm-demo-update.sh | bash -s -- --version 0.1.4
#   curl -fsSL .../ai-apm-demo-update.sh | bash -s -- --pull-images
#
# 与 ai-apm-demo-install.sh 区别:
#   install = 全新安装（无旧目录也可执行）
#   update  = 就地升级（要求已有 Demo 安装目录）
#
# 环境变量:
#   APM_PKG_BASE       部署包地址
#   APM_INSTALL_DIR    安装目录 (默认 /opt/databuff-ai-apm-demo)
#   APM_VERSION        目标版本 (默认从 VERSION 读取最新)
#   FORCE_PULL_IMAGES  1=强制重新下载镜像
#   SKIP_START         1=仅更新文件与镜像，不启动
#   SKIP_PULL_IMAGES   1=不下载镜像
#   INGEST_HOST        ingest 地址
#   INGEST_PORT        ingest 端口 (默认 4318)

set -e

cd /opt 2>/dev/null || cd "${TMPDIR:-/tmp}" 2>/dev/null || true

PKG_BASE="${APM_PKG_BASE:-__APM_PKG_BASE__}"
export APM_PKG_BASE="$PKG_BASE"
BUILTIN_APM_VERSION="__APM_VERSION__"
INSTALL_DIR="${APM_INSTALL_DIR:-/opt/databuff-ai-apm-demo}"
FORCE_PULL_IMAGES="${FORCE_PULL_IMAGES:-0}"
SKIP_START="${SKIP_START:-0}"
SKIP_PULL_IMAGES="${SKIP_PULL_IMAGES:-0}"
INGEST_PORT="${INGEST_PORT:-4318}"

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
RED='\033[31m'
YLW='\033[33m'
BLD='\033[1m'
DIM='\033[2m'
RST='\033[0m'

fail() {
  echo -e "${RED}[demo-update] ERROR:${RST} $*" >&2
  exit 1
}

source_remote_lib() {
  local name="$1"
  local dest
  dest="$(mktemp "${TMPDIR:-/tmp}/${name}.XXXXXX.sh")"
  if ! curl -fsSL "${PKG_BASE%/}/${name}.sh" -o "$dest"; then
    rm -f "$dest"
    fail "无法下载 ${PKG_BASE}/${name}.sh"
  fi
  # shellcheck disable=SC1090,SC1091
  . "$dest"
  rm -f "$dest"
}

echo ""
echo -e "${CYN}========================================================${RST}"
echo -e "${BLD} DataBuff AI APM Demo  在线升级${RST}"
echo -e "${DIM} 停掉旧 demo，替换为目标版本${RST}"
echo -e "${CYN}========================================================${RST}"
echo ""

if [[ "$(id -u)" -ne 0 ]]; then
  fail "请使用 root 运行"
fi

source_remote_lib resolve-install-version
source_remote_lib demo-deploy-lib

APM_VERSION="$(resolve_apm_install_version)"
export APM_VERSION
export_apm_pkg_download_env

demo_install_dir_ready "$INSTALL_DIR" || fail "未找到 Demo 安装目录 ${INSTALL_DIR}，请先执行 ai-apm-demo-install.sh"

INGEST_HOST="${INGEST_HOST:-}"
if [[ -z "$INGEST_HOST" ]]; then
  INGEST_HOST="$(demo_detect_host_ip || true)"
fi
[[ -n "$INGEST_HOST" ]] || fail "无法获取本机 IP，请设置 INGEST_HOST"

# 总是先从目标版本 demo 包刷新本机 update.sh / scripts/ / env.sh 里的 APM_VERSION，
# 避免执行旧版内层 update.sh（它会 source 旧 env.sh 把 APM_VERSION 钉回老版本 → 404）。
UPDATE_SCRIPT="${INSTALL_DIR}/update.sh"
echo -e "${CYN}[demo-update]${RST} 刷新本机 update.sh 与 scripts/ ..."
TMP_PKG="$(mktemp "${TMPDIR:-/tmp}/apm-demo-update-bootstrap.XXXXXX.tar.gz")"
STAGING="$(mktemp -d "${TMPDIR:-/tmp}/apm-demo-update-bootstrap-stage.XXXXXX")"
DEMO_PKG="databuff-apm-demo-${APM_VERSION}.tar.gz"
PKG_URL="$(apm_docker_pkg_download_url "$DEMO_PKG")"
if curl -fsSL "$PKG_URL" -o "$TMP_PKG"; then
  tar -xzf "$TMP_PKG" -C "$STAGING" --strip-components=1
  rm -f "$TMP_PKG"
  mkdir -p "${INSTALL_DIR}/scripts"
  [[ -f "${STAGING}/update.sh" ]] && { cp -f "${STAGING}/update.sh" "${INSTALL_DIR}/update.sh"; chmod +x "${INSTALL_DIR}/update.sh"; }
  [[ -d "${STAGING}/scripts" ]] && { cp -Rf "${STAGING}/scripts/." "${INSTALL_DIR}/scripts/"; chmod +x "${INSTALL_DIR}/scripts/"*.sh 2>/dev/null || true; }
  # 旧 env.sh 写死 export APM_VERSION=<旧版>，会被内层 update.sh source 后覆盖 --version。
  # 把这行改成目标版本，其余用户自定义项保留。
  if [[ -f "${INSTALL_DIR}/env.sh" ]]; then
    if grep -q '^export APM_VERSION=' "${INSTALL_DIR}/env.sh"; then
      sed -i "s/^export APM_VERSION=.*/export APM_VERSION=${APM_VERSION}/" "${INSTALL_DIR}/env.sh"
    else
      printf '\nexport APM_VERSION=%s\n' "$APM_VERSION" >> "${INSTALL_DIR}/env.sh"
    fi
  fi
  rm -rf "$STAGING"
  UPDATE_SCRIPT="${INSTALL_DIR}/update.sh"
else
  rm -f "$TMP_PKG"
  rm -rf "$STAGING"
  if [[ ! -x "$UPDATE_SCRIPT" ]]; then
    fail "无法刷新 update.sh，且安装目录缺少可执行 update.sh"
  fi
  echo -e "${CYN}[demo-update]${RST} ${YLW}刷新失败，使用本机已有 update.sh（可能不支持新参数）${RST}"
fi

exec env \
  APM_PKG_BASE="$APM_PKG_BASE" \
  APM_INSTALL_DIR="$INSTALL_DIR" \
  APM_VERSION="$APM_VERSION" \
  FORCE_PULL_IMAGES="$FORCE_PULL_IMAGES" \
  SKIP_START="$SKIP_START" \
  SKIP_PULL_IMAGES="$SKIP_PULL_IMAGES" \
  INGEST_HOST="$INGEST_HOST" \
  INGEST_PORT="$INGEST_PORT" \
  "$UPDATE_SCRIPT" --version "$APM_VERSION" \
  $([[ "$FORCE_PULL_IMAGES" == "1" ]] && echo --pull-images) \
  $([[ "$SKIP_START" == "1" ]] && echo --skip-start)
