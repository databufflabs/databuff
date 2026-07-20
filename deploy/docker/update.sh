#!/usr/bin/env bash
# DataBuff AI APM 就地升级（保留 data/ 观测数据）
#
# 在安装目录执行:
#   cd /opt/databuff-ai-apm && ./update.sh
#   ./update.sh --version 0.1.5
#   ./update.sh --pull-images
#   ./update.sh --restore-backup
#   ./update.sh --restore-backup=data-backup-20260713-110220
#
# 环境变量:
#   APM_PKG_BASE              部署包下载地址
#   APM_INSTALL_DIR           安装目录 (默认 /opt/databuff-ai-apm)
#   UPDATE_BUNDLE_ROOT        离线包解压目录（内网升级时设置，无需 curl）
#   FORCE_PULL_IMAGES         1=强制重新下载镜像
#   FORCE_LOAD_IMAGES         1=离线包强制 docker load
#   SKIP_BACKUP               1=跳过 data/ 备份（禁用失败自动恢复）
#   SKIP_START                1=仅更新文件与镜像，不启动
#   SKIP_VERIFY               1=跳过升级后校验 (verify-upgrade.sh)
#   UPDATE_MAX_ATTEMPTS       启动/迁移失败时自动恢复备份并重试的次数 (默认 3)
#   RESTORE_BACKUP         1=当前 data/ 不可信，先从 backups/ 恢复后再继续
#   BACKUP_FILE            指定备份目录名（默认取 backups/ 下最新 data-backup-*；亦兼容旧版 *.tar.gz）

set -e

# 自保护：拷贝自身到 temp 并 re-exec，使 apm_sync_deploy_bundle 覆盖磁盘上的
# update.sh 时，bash 仍从 temp 副本读取，避免运行中覆盖导致解析错位。
if [[ "${APM_UPDATE_REEXECED:-0}" != "1" ]]; then
  _orig_dir="$(cd "$(dirname "$0")" && pwd)"
  _runner="$(mktemp "${TMPDIR:-/tmp}/apm-update-runner.XXXXXX.sh")"
  cp -f "${_orig_dir}/update.sh" "$_runner"
  chmod +x "$_runner"
  APM_UPDATE_REEXECED=1 APM_UPDATE_ORIG_DIR="$_orig_dir" exec "$_runner" "$@"
fi

# re-exec 后 $0 是 temp 副本，从保存的原始目录恢复 ROOT
ROOT="${APM_UPDATE_ORIG_DIR}"
INSTALL_DIR="${APM_INSTALL_DIR:-$ROOT}"
trap 'rm -f "$0"' EXIT

PKG_BASE="${APM_PKG_BASE:-}"
if [[ -f "${ROOT}/env.sh" ]]; then
  set -a
  # shellcheck disable=SC1091
  . "${ROOT}/env.sh"
  set +a
fi
PKG_BASE="${APM_PKG_BASE:-${PKG_BASE}}"
BUILTIN_APM_VERSION=""
if [[ -f "${ROOT}/VERSION" ]]; then
  BUILTIN_APM_VERSION="$(tr -d '[:space:]' <"${ROOT}/VERSION")"
fi

FORCE_PULL_IMAGES="${FORCE_PULL_IMAGES:-0}"
FORCE_LOAD_IMAGES="${FORCE_LOAD_IMAGES:-0}"
SKIP_BACKUP="${SKIP_BACKUP:-0}"
SKIP_START="${SKIP_START:-0}"
SKIP_VERIFY="${SKIP_VERIFY:-0}"
UPDATE_MAX_ATTEMPTS="${UPDATE_MAX_ATTEMPTS:-3}"
RESTORE_BACKUP="${RESTORE_BACKUP:-0}"
BACKUP_FILE="${BACKUP_FILE:-}"
UPDATE_BUNDLE_ROOT="${UPDATE_BUNDLE_ROOT:-}"
backup_archive=""
restore_source_archive=""

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
export FORCE_PULL_IMAGES FORCE_LOAD_IMAGES RESTORE_BACKUP

CYN='\033[36m'
GRN='\033[32m'
YLW='\033[33m'
RED='\033[31m'
DIM='\033[2m'
BLD='\033[1m'
RST='\033[0m'

log() { echo -e "${CYN}[update]${RST} $*"; }
log_done() { echo -e "${CYN}[update]${RST} $1 ${GRN}... 完成${RST}"; }
log_skip() { echo -e "${CYN}[update]${RST} $1 ${YLW}... 跳过${RST}"; }
fail() { echo -e "${RED}[update] ERROR:${RST} $*" >&2; exit 1; }

upgrade_finalize() {
  local attempt="$1"
  log "${BLD}(6/6)${RST} 启动服务并迁移 Schema ${DIM}(第 ${attempt}/${UPDATE_MAX_ATTEMPTS} 次)${RST}"
  cd "$INSTALL_DIR"
  source_compose_env
  # shellcheck disable=SC1091
  . "${INSTALL_DIR}/scripts/runtime.sh"
  ensure_vm_max_map_count
  prepare_compose_start
  compose_up_wait ai-apm-doris-fe ai-apm-doris-be
  if ! "${INSTALL_DIR}/scripts/migrate-schema.sh"; then
    log "${YLW}Schema 迁移失败（ingest 未启动，避免写入异常表结构）${RST}"
    return 1
  fi
  compose_up ai-apm-ingest ai-apm-web
  wait_for_apm_services_ready
  if [[ "$SKIP_VERIFY" == "1" ]]; then
    log_skip "升级后校验 (SKIP_VERIFY=1)"
  else
    log "升级后校验"
    "${INSTALL_DIR}/scripts/verify-upgrade.sh" --expected-version="$TARGET_VERSION"
    log_done "升级后校验"
  fi
}

source_compose_env() {
  # compose-env.sh reloads install-dir env.sh/VERSION and would clobber target APM_VERSION
  # shellcheck disable=SC1091
  . "${INSTALL_DIR}/scripts/compose-env.sh"
  export APM_VERSION="$TARGET_VERSION"
  if declare -F apm_refresh_image_refs >/dev/null 2>&1; then
    apm_refresh_image_refs
  fi
  if declare -F apm_refresh_image_pkg_bases >/dev/null 2>&1; then
    apm_refresh_image_pkg_bases
  fi
}

source_update_libs() {
  local f
  for f in \
    "${ROOT}/scripts/apm-update-lib.sh" \
    "${ROOT}/../common/scripts/apm-update-lib.sh"; do
    if [[ -f "$f" ]]; then
      # shellcheck disable=SC1090,SC1091
      . "$f"
      return 0
    fi
  done
  fail "缺少 apm-update-lib.sh"
}

source_version_lib() {
  local f
  for f in \
    "${ROOT}/scripts/resolve-install-version.sh" \
    "${ROOT}/../common/scripts/resolve-install-version.sh"; do
    if [[ -f "$f" ]]; then
      # shellcheck disable=SC1090,SC1091
      . "$f"
      return 0
    fi
  done
  if [[ -n "$PKG_BASE" ]] && command -v curl >/dev/null 2>&1; then
    f="$(mktemp "${TMPDIR:-/tmp}/resolve-install-version.XXXXXX.sh")"
    if curl -fsSL "${PKG_BASE%/}/resolve-install-version.sh" -o "$f"; then
      # shellcheck disable=SC1090,SC1091
      . "$f"
      rm -f "$f"
      return 0
    fi
    rm -f "$f"
  fi
  fail "缺少 resolve-install-version.sh"
}

source_update_libs
source_version_lib

if ! apm_install_dir_ready "$INSTALL_DIR"; then
  fail "未找到有效安装目录 ${INSTALL_DIR}，请先执行 install.sh 全新安装"
fi

CURRENT_VERSION="$(apm_read_version_file "${INSTALL_DIR}/VERSION" || echo "")"
[[ -n "$CURRENT_VERSION" ]] || fail "无法读取当前版本 (${INSTALL_DIR}/VERSION)"

export APM_VERSION
APM_VERSION="$(resolve_apm_install_version)"
export_apm_pkg_download_env
TARGET_VERSION="$APM_VERSION"
DOCKER_PKG="databuff-ai-apm-${TARGET_VERSION}.tar.gz"

if [[ "$CURRENT_VERSION" == "$TARGET_VERSION" && "$RESTORE_BACKUP" != "1" ]]; then
  log "已在版本 ${CURRENT_VERSION}，无需升级"
  exit 0
fi
if [[ "$RESTORE_BACKUP" != "1" ]] && ! apm_version_gte "$TARGET_VERSION" "$CURRENT_VERSION"; then
  fail "目标版本 ${TARGET_VERSION} 低于当前版本 ${CURRENT_VERSION}，不支持降级"
fi

echo ""
echo -e "${CYN}========================================================${RST}"
if [[ "$RESTORE_BACKUP" == "1" && "$CURRENT_VERSION" == "$TARGET_VERSION" ]]; then
  echo -e "${BLD} DataBuff AI APM  修复升级 ${TARGET_VERSION}${RST}"
  echo -e "${DIM} 从 backups/ 恢复 data/ 后重新迁移${RST}"
elif [[ "$RESTORE_BACKUP" == "1" ]]; then
  echo -e "${BLD} DataBuff AI APM  升级 ${CURRENT_VERSION} → ${TARGET_VERSION}${RST}"
  echo -e "${DIM} 当前 data/ 不可信，将先从 backups/ 恢复${RST}"
else
  echo -e "${BLD} DataBuff AI APM  升级 ${CURRENT_VERSION} → ${TARGET_VERSION}${RST}"
fi
echo -e "${CYN}========================================================${RST}"
echo ""

log "${BLD}(1/6)${RST} 检查环境"
if [[ "$(id -u)" -ne 0 ]] && [[ ! -w "$INSTALL_DIR" ]]; then
  fail "请使用 root 运行（或确保对安装目录 ${INSTALL_DIR} 可写）"
fi
for cmd in tar docker python3; do
  command -v "$cmd" >/dev/null 2>&1 || fail "缺少命令: $cmd"
done
docker info >/dev/null 2>&1 || fail "Docker 不可用"
# shellcheck source=scripts/check-compose.sh
. "${INSTALL_DIR}/scripts/check-compose.sh"
ensure_compose_cli
log_done "${BLD}(1/6)${RST} 检查环境"

log "${BLD}(2/6)${RST} 停止服务"
source_compose_env
(cd "$INSTALL_DIR" && compose_down) >/dev/null 2>&1 || true
log_done "${BLD}(2/6)${RST} 停止服务"

if [[ "$RESTORE_BACKUP" == "1" ]]; then
  log "当前 data/ 不可信，从备份恢复"
  restore_source_archive="$(apm_resolve_data_backup_archive "$INSTALL_DIR" "$BACKUP_FILE")" \
    || fail "未找到可用的 data/ 备份（请确认 backups/data-backup-* 存在，或用 --restore-backup=目录名 指定）"
  apm_restore_data_dir "$INSTALL_DIR" "$restore_source_archive" \
    || fail "恢复 data/ 失败: ${restore_source_archive}"
  backup_archive="$restore_source_archive"
  log_done "已恢复 data/ ← ${restore_source_archive}"
fi

log "${BLD}(3/6)${RST} 备份 data/"
if [[ "$RESTORE_BACKUP" == "1" ]]; then
  log_skip "${BLD}(3/6)${RST} 备份 data/（已从可信备份恢复，跳过）"
elif [[ "$SKIP_BACKUP" == "1" ]]; then
  log_skip "${BLD}(3/6)${RST} 备份 data/ (SKIP_BACKUP=1)"
else
  backup_archive="$(apm_backup_data_dir "$INSTALL_DIR")" \
    || fail "备份 data/ 失败（请确认 ${INSTALL_DIR}/data 存在）"
  log_done "${BLD}(3/6)${RST} 备份 → ${backup_archive}"
fi

log "${BLD}(4/6)${RST} 更新部署文件"
export APM_VERSION="$TARGET_VERSION"
export_apm_pkg_download_env
STAGING=""
if [[ -n "$UPDATE_BUNDLE_ROOT" && -f "${UPDATE_BUNDLE_ROOT}/${DOCKER_PKG}" ]]; then
  STAGING="$(mktemp -d "${TMPDIR:-/tmp}/apm-update-stage.XXXXXX")"
  tar -xzf "${UPDATE_BUNDLE_ROOT}/${DOCKER_PKG}" -C "$STAGING" --strip-components=1
elif [[ -f "${ROOT}/${DOCKER_PKG}" ]]; then
  STAGING="$(mktemp -d "${TMPDIR:-/tmp}/apm-update-stage.XXXXXX")"
  tar -xzf "${ROOT}/${DOCKER_PKG}" -C "$STAGING" --strip-components=1
else
  command -v curl >/dev/null 2>&1 || fail "需要 curl 下载部署包，或设置 UPDATE_BUNDLE_ROOT 为离线包解压目录"
  TMP_PKG="$(mktemp "${TMPDIR:-/tmp}/apm-update.XXXXXX.tar.gz")"
  PKG_URL="$(apm_docker_pkg_download_url "$DOCKER_PKG")"
  curl -fsSL "$PKG_URL" -o "$TMP_PKG"
  STAGING="$(mktemp -d "${TMPDIR:-/tmp}/apm-update-stage.XXXXXX")"
  tar -xzf "$TMP_PKG" -C "$STAGING" --strip-components=1
  rm -f "$TMP_PKG"
fi
chmod +x "${STAGING}/start.sh" "${STAGING}/stop.sh" "${STAGING}/reset-table.sh" "${STAGING}/update.sh" 2>/dev/null || true
chmod +x "${STAGING}/scripts/"*.sh 2>/dev/null || true
preflight_msg=""
if ! preflight_msg="$(apm_upgrade_preflight "$INSTALL_DIR" "$CURRENT_VERSION" "$TARGET_VERSION" "${STAGING}/upgrade-manifest.json" 2>&1)"; then
  rm -rf "$STAGING"
  fail "升级预检失败: ${preflight_msg}"
fi
apm_sync_deploy_bundle "$STAGING" "$INSTALL_DIR"
rm -rf "$STAGING"
# shellcheck source=scripts/check-compose.sh
. "${INSTALL_DIR}/scripts/check-compose.sh"
ensure_compose_cli
apm_materialize_compose_file "$INSTALL_DIR"
log_done "${BLD}(4/6)${RST} 更新部署文件"

log "${BLD}(5/6)${RST} 加载镜像"
export ROOT="$INSTALL_DIR"
offline_has_images=0
if [[ -n "${UPDATE_BUNDLE_ROOT:-}" ]]; then
  shopt -s nullglob
  offline_image_tars=("${UPDATE_BUNDLE_ROOT}/ai-apm-stack-${TARGET_VERSION}-"*.tar.gz)
  shopt -u nullglob
  if [[ ${#offline_image_tars[@]} -gt 0 ]]; then
    offline_has_images=1
  fi
fi
if [[ "$offline_has_images" == "1" ]]; then
  force_load="${FORCE_LOAD_IMAGES:-${FORCE_PULL_IMAGES:-0}}"
  apm_load_offline_bundle_images "$UPDATE_BUNDLE_ROOT" "$TARGET_VERSION" "$force_load"
elif [[ "${SKIP_PULL_IMAGES:-0}" != "1" ]]; then
  command -v curl >/dev/null 2>&1 || fail "需要 curl 下载镜像，或设置 UPDATE_BUNDLE_ROOT 为含镜像 tar 的离线包目录"
  "${INSTALL_DIR}/scripts/pull-images.sh"
else
  log_skip "${BLD}(5/6)${RST} 加载镜像 (SKIP_PULL_IMAGES=1，使用本地已有镜像)"
fi
log_done "${BLD}(5/6)${RST} 加载镜像"

if [[ "$SKIP_START" == "1" ]]; then
  log_skip "${BLD}(6/6)${RST} 启动与迁移 (SKIP_START=1)"
else
  attempt=1
  while [[ "$attempt" -le "$UPDATE_MAX_ATTEMPTS" ]]; do
    if [[ "$attempt" -gt 1 ]]; then
      log "${YLW}第 ${attempt}/${UPDATE_MAX_ATTEMPTS} 次尝试：恢复升级前 data/ 备份后重试${RST}"
      if [[ -z "$backup_archive" || ! -e "$backup_archive" ]]; then
        fail "无法自动重试：缺少 data/ 备份（请勿设置 SKIP_BACKUP=1）"
      fi
      source_compose_env
      (cd "$INSTALL_DIR" && compose_down) >/dev/null 2>&1 || true
      apm_restore_data_dir "$INSTALL_DIR" "$backup_archive" \
        || fail "恢复 data/ 备份失败: ${backup_archive}"
      log_done "已恢复 data/ ← ${backup_archive}"
    fi

    if upgrade_finalize "$attempt"; then
      log_done "${BLD}(6/6)${RST} 启动与迁移"
      break
    fi

    if [[ "$attempt" -ge "$UPDATE_MAX_ATTEMPTS" ]]; then
      if [[ -n "$backup_archive" && -e "$backup_archive" ]]; then
        source_compose_env
        (cd "$INSTALL_DIR" && compose_down) >/dev/null 2>&1 || true
        apm_restore_data_dir "$INSTALL_DIR" "$backup_archive" || true
      fi
      log "${YLW}升级失败（已尝试 ${UPDATE_MAX_ATTEMPTS} 次），启动 Web 排障模式${RST}"
      cd "$INSTALL_DIR"
      source_compose_env
      # shellcheck disable=SC1091
      . "${INSTALL_DIR}/scripts/runtime.sh"
      compose_up_wait ai-apm-doris-fe ai-apm-doris-be || true
      bootstrap_web_for_troubleshooting "升级失败"
      if [[ -n "$backup_archive" && -e "$backup_archive" ]]; then
        fail "升级失败（已尝试 ${UPDATE_MAX_ATTEMPTS} 次）。data/ 已恢复为升级前备份: ${backup_archive}"
      fi
      fail "升级失败（已尝试 ${UPDATE_MAX_ATTEMPTS} 次）"
    fi

    log "${YLW}(6/6) 启动或迁移失败，将恢复备份并重试${RST}"
    attempt=$((attempt + 1))
  done
fi

echo ""
echo -e "${CYN}========================================================${RST}"
echo -e "${GRN}${BLD} 升级完成 ${CURRENT_VERSION} → ${TARGET_VERSION}${RST}"
echo -e "${CYN}========================================================${RST}"
echo -e "  ${DIM}安装目录${RST}  ${INSTALL_DIR}"
echo -e "  ${DIM}数据目录${RST}  ${INSTALL_DIR}/data/ （已保留）"
echo ""
