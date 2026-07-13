#!/usr/bin/env bash
# DataBuff AI APM 在线升级（保留 data/，不删除安装目录）
#
#   curl -fsSL https://databuff.ai/databuff/ai-apm-update.sh | bash
#   curl -fsSL https://databuff.ai/databuff/ai-apm-update.sh | bash -s -- --version 0.1.3
#   curl -fsSL .../ai-apm-update.sh | bash -s -- --pull-images
#
# 与 ai-apm-install.sh 区别:
#   install = 全新安装，会删除安装目录（含 data/）
#   update  = 就地升级，保留 data/
#
# 环境变量:
#   APM_PKG_BASE       部署包地址 (默认 https://databuff.ai/databuff)
#   APM_INSTALL_DIR    安装目录 (默认 /opt/databuff-ai-apm)
#   APM_VERSION        目标版本 (默认从 VERSION 读取最新)
#   FORCE_PULL_IMAGES  1=强制重新下载镜像
#   SKIP_BACKUP        1=跳过 data/ 备份
#   SKIP_START         1=仅更新文件与镜像，不启动
#   SKIP_PULL_IMAGES   1=不下载镜像（使用本地已 load 的镜像）
#   SKIP_VERIFY        1=跳过升级后校验
#   RESTORE_BACKUP       1=当前 data/ 不可信，先从 backups/ 恢复后再继续
#   BACKUP_FILE          指定备份文件（默认取最新 data-backup-*.tar.gz）

set -e

cd /opt 2>/dev/null || cd "${TMPDIR:-/tmp}" 2>/dev/null || true

PKG_BASE="${APM_PKG_BASE:-__APM_PKG_BASE__}"
export APM_PKG_BASE="$PKG_BASE"
BUILTIN_APM_VERSION="__APM_VERSION__"
INSTALL_DIR="${APM_INSTALL_DIR:-/opt/databuff-ai-apm}"

FORCE_PULL_IMAGES="${FORCE_PULL_IMAGES:-0}"
SKIP_BACKUP="${SKIP_BACKUP:-0}"
SKIP_START="${SKIP_START:-0}"
SKIP_PULL_IMAGES="${SKIP_PULL_IMAGES:-0}"
SKIP_VERIFY="${SKIP_VERIFY:-0}"
RESTORE_BACKUP="${RESTORE_BACKUP:-0}"
BACKUP_FILE="${BACKUP_FILE:-}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version=*)
      APM_VERSION="${1#--version=}"
      shift
      ;;
    --version | -v)
      [[ $# -ge 2 ]] || {
        echo "[update] ERROR: --version requires a value" >&2
        exit 1
      }
      APM_VERSION="$2"
      shift 2
      ;;
    --pull-images | -f)
      FORCE_PULL_IMAGES=1
      shift
      ;;
    --skip-backup)
      SKIP_BACKUP=1
      shift
      ;;
    --skip-start)
      SKIP_START=1
      shift
      ;;
    --restore-backup)
      RESTORE_BACKUP=1
      shift
      ;;
    --restore-backup=*)
      RESTORE_BACKUP=1
      BACKUP_FILE="${1#--restore-backup=}"
      shift
      ;;
    *)
      shift
      ;;
  esac
done

export FORCE_PULL_IMAGES SKIP_BACKUP SKIP_START SKIP_PULL_IMAGES SKIP_VERIFY
export RESTORE_BACKUP BACKUP_FILE

CYN='\033[36m'
RED='\033[31m'
YLW='\033[33m'
BLD='\033[1m'
DIM='\033[2m'
RST='\033[0m'

fail() {
  echo -e "${RED}[update] ERROR:${RST} $*" >&2
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
echo -e "${BLD} DataBuff AI APM  在线升级${RST}"
echo -e "${DIM} 保留 data/，不执行全新安装${RST}"
echo -e "${CYN}========================================================${RST}"
echo ""

if [[ "$(id -u)" -ne 0 ]]; then
  fail "请使用 root 运行"
fi

source_remote_lib resolve-install-version
source_remote_lib apm-update-lib

APM_VERSION="$(resolve_apm_install_version)"
export APM_VERSION
export_apm_pkg_download_env

if ! apm_install_dir_ready "$INSTALL_DIR"; then
  fail "未找到安装目录 ${INSTALL_DIR}，请先执行 ai-apm-install.sh 全新安装"
fi

UPDATE_SCRIPT="${INSTALL_DIR}/update.sh"
echo -e "${CYN}[update]${RST} 刷新本机 update.sh 与 scripts/ ..."
if ! apm_bootstrap_update_runner "$INSTALL_DIR" "$APM_VERSION"; then
  if [[ ! -x "$UPDATE_SCRIPT" ]]; then
    fail "无法刷新 update.sh，且安装目录缺少可执行 update.sh"
  fi
  echo -e "${CYN}[update]${RST} ${YLW}刷新失败，使用本机已有 update.sh（可能不支持新参数）${RST}"
else
  UPDATE_SCRIPT="${INSTALL_DIR}/update.sh"
fi

exec env \
  APM_PKG_BASE="$APM_PKG_BASE" \
  APM_INSTALL_DIR="$INSTALL_DIR" \
  APM_VERSION="$APM_VERSION" \
  FORCE_PULL_IMAGES="$FORCE_PULL_IMAGES" \
  SKIP_BACKUP="$SKIP_BACKUP" \
  SKIP_START="$SKIP_START" \
  SKIP_PULL_IMAGES="$SKIP_PULL_IMAGES" \
  SKIP_VERIFY="$SKIP_VERIFY" \
  RESTORE_BACKUP="$RESTORE_BACKUP" \
  BACKUP_FILE="$BACKUP_FILE" \
  "$UPDATE_SCRIPT" --version "$APM_VERSION" \
  $([[ "$FORCE_PULL_IMAGES" == "1" ]] && echo --pull-images) \
  $([[ "$SKIP_BACKUP" == "1" ]] && echo --skip-backup) \
  $([[ "$SKIP_START" == "1" ]] && echo --skip-start) \
  $([[ "$RESTORE_BACKUP" == "1" && -z "$BACKUP_FILE" ]] && echo --restore-backup) \
  $([[ "$RESTORE_BACKUP" == "1" && -n "$BACKUP_FILE" ]] && echo --restore-backup="$BACKUP_FILE")
