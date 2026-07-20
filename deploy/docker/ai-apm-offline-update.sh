#!/usr/bin/env bash
# DataBuff AI APM 离线升级（解压目标版本离线包后在本目录执行）
#
#   tar -xzf databuff-ai-apm-offline-0.1.5-amd64.tar.gz
#   cd databuff-ai-apm-offline-0.1.5-amd64
#   sudo ./update.sh
#
# 与 install.sh 区别:
#   install.sh = 全新安装，删除旧安装目录（含 data/）
#   update.sh  = 就地升级，保留 /opt/databuff-ai-apm/data/
#
# 也可在安装目录指定离线包路径（全程无需联网）:
#   sudo UPDATE_BUNDLE_ROOT=/path/to/databuff-ai-apm-offline-0.1.5-amd64 \
#        SKIP_PULL_IMAGES=1 /opt/databuff-ai-apm/update.sh --version 0.1.5
#
# 环境变量:
#   APM_INSTALL_DIR    安装目录 (默认 /opt/databuff-ai-apm)
#   FORCE_LOAD_IMAGES  1=强制重新 docker load
#   SKIP_BACKUP        1=跳过 data/ 备份
#   SKIP_START         1=仅更新不启动

set -e

BUNDLE_ROOT="$(cd "$(dirname "$0")" && pwd)"
INSTALL_DIR="${APM_INSTALL_DIR:-/opt/databuff-ai-apm}"
FORCE_LOAD_IMAGES="${FORCE_LOAD_IMAGES:-0}"
SKIP_BACKUP="${SKIP_BACKUP:-0}"
SKIP_START="${SKIP_START:-0}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --pull-images | -f)
      FORCE_LOAD_IMAGES=1
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
    *)
      shift
      ;;
  esac
done

CYN='\033[36m'
GRN='\033[32m'
RED='\033[31m'
BLD='\033[1m'
DIM='\033[2m'
RST='\033[0m'

fail() {
  echo -e "${RED}[update] ERROR:${RST} $*" >&2
  exit 1
}

if [[ ! -f "${BUNDLE_ROOT}/VERSION" ]]; then
  fail "请在离线包解压目录内执行 update.sh"
fi

APM_VERSION="$(tr -d '[:space:]' <"${BUNDLE_ROOT}/VERSION")"

if [[ -f "${BUNDLE_ROOT}/scripts/apm-update-lib.sh" ]]; then
  # shellcheck disable=SC1091
  . "${BUNDLE_ROOT}/scripts/apm-update-lib.sh"
else
  fail "离线包缺少 scripts/apm-update-lib.sh"
fi

echo ""
echo -e "${CYN}========================================================${RST}"
echo -e "${BLD} DataBuff AI APM  离线升级 v${APM_VERSION}${RST}"
echo -e "${DIM} 保留 data/，全程无需联网${RST}"
echo -e "${CYN}========================================================${RST}"
echo ""

if [[ "$(id -u)" -ne 0 ]]; then
  fail "请使用 root 运行"
fi
for cmd in tar docker python3; do
  command -v "$cmd" >/dev/null 2>&1 || fail "缺少命令: $cmd"
done
docker info >/dev/null 2>&1 || fail "Docker 不可用"

apm_install_dir_ready "$INSTALL_DIR" || fail "未找到安装目录 ${INSTALL_DIR}，请先执行 install.sh 全新安装"

echo -e "${CYN}[update]${RST} ${BLD}(1/2)${RST} 加载离线镜像"
apm_load_offline_bundle_images "$BUNDLE_ROOT" "$APM_VERSION" "$FORCE_LOAD_IMAGES"
echo -e "${CYN}[update]${RST} ${BLD}(1/2)${RST} 加载离线镜像 ${GRN}... 完成${RST}"

echo -e "${CYN}[update]${RST} ${BLD}(2/2)${RST} 执行就地升级"
UPDATE_SCRIPT="$(apm_resolve_update_executable "$INSTALL_DIR" "$BUNDLE_ROOT" "$APM_VERSION")" \
  || fail "无法定位 update.sh"

export UPDATE_BUNDLE_ROOT="$BUNDLE_ROOT"
export APM_INSTALL_DIR="$INSTALL_DIR"
export APM_VERSION
export SKIP_PULL_IMAGES=1
export FORCE_LOAD_IMAGES="$FORCE_LOAD_IMAGES"
export SKIP_BACKUP SKIP_START

exec "$UPDATE_SCRIPT" --version "$APM_VERSION" \
  $([[ "$FORCE_LOAD_IMAGES" == "1" ]] && echo --pull-images) \
  $([[ "$SKIP_BACKUP" == "1" ]] && echo --skip-backup) \
  $([[ "$SKIP_START" == "1" ]] && echo --skip-start)
