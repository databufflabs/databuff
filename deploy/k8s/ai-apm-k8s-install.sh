#!/usr/bin/env bash
# DataBuff AI APM Kubernetes 一键安装（kubectl 直装，脚本控制启动顺序）
#
# 用法:
#   curl -fsSL https://databuff.ai/databuff/ai-apm-k8s-install.sh | bash
#
# 安装包内:
#   install.sh  卸载后全新安装
#   start.sh    按顺序启动（不卸载）
#   stop.sh     停止服务（缩容至 0）
#
# 安装前建议在各节点执行:
#   curl -fsSL https://databuff.ai/databuff/ai-apm-k8s-download-images.sh | bash
# 或部署包内 ./download-images.sh
#
# 环境变量:
#   APM_PKG_BASE        部署包下载地址 (默认 https://databuff.ai/databuff)
#   APM_INSTALL_DIR     安装目录 (默认 /opt/databuff-ai-apm-k8s)
#   APM_VERSION         指定版本号 (默认从 ${APM_PKG_BASE}/VERSION 读取最新版)
#   APM_NAMESPACE       K8s 命名空间 (默认 databuff)
#   DORIS_ENABLED       内置 Doris (默认 1)
#   ZOOKEEPER_ENABLED   内置 ZooKeeper (默认 1)
#   SKIP_DOWNLOAD       已在安装目录中解压时设为 1
#   SKIP_START          1=仅下载解压不部署
#   KUBECONFIG          kubeconfig 路径（未设置时自动尝试常见路径）
#   WAIT_TIMEOUT        各阶段等待超时 (默认 25m)
#   UNINSTALL_TIMEOUT   卸载等待超时 (默认 10m)
#   DORIS_VERSION       Doris 镜像版本 (默认读安装包 DORIS_VERSION 或 pom.xml 中 doris.version)
#
# 指定版本:
#   curl -fsSL .../ai-apm-k8s-install.sh | bash -s -- --version 0.1.2
#   APM_VERSION=0.1.2 curl -fsSL .../ai-apm-k8s-install.sh | bash

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

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
INSTALL_DIR="${APM_INSTALL_DIR:-/opt/databuff-ai-apm-k8s}"
SKIP_DOWNLOAD="${SKIP_DOWNLOAD:-0}"
SKIP_START="${SKIP_START:-0}"
K8S_PKG="databuff-ai-apm-k8s-${APM_VERSION}.tar.gz"

if [[ -d "${SCRIPT_DIR}/manifests" && -f "${SCRIPT_DIR}/scripts/lib.sh" ]]; then
  INSTALL_DIR="${APM_INSTALL_DIR:-$SCRIPT_DIR}"
  SKIP_DOWNLOAD=1
fi

ROOT="$INSTALL_DIR"

CYN='\033[36m'
GRN='\033[32m'
YLW='\033[33m'
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

cluster_reachable() {
  kubectl cluster-info >/dev/null 2>&1
}

ensure_kube_access() {
  if ! command -v kubectl >/dev/null 2>&1; then
    fail "缺少命令: kubectl"
  fi

  if [[ -z "${KUBECONFIG:-}" ]]; then
    if [[ -f /etc/rancher/k3s/k3s.yaml ]]; then
      export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
      log_sub "使用 kubeconfig: ${KUBECONFIG}"
    elif [[ -f "${HOME}/.kube/config" ]]; then
      export KUBECONFIG="${HOME}/.kube/config"
    elif [[ -f /etc/kubernetes/admin.conf ]]; then
      export KUBECONFIG=/etc/kubernetes/admin.conf
      log_sub "使用 kubeconfig: ${KUBECONFIG}"
    fi
  fi

  if cluster_reachable; then
    return 0
  fi

  local candidate
  for candidate in \
    "${KUBECONFIG:-}" \
    /etc/rancher/k3s/k3s.yaml \
    "${HOME}/.kube/config" \
    /etc/kubernetes/admin.conf; do
    [[ -n "$candidate" && -f "$candidate" ]] || continue
    export KUBECONFIG="$candidate"
    if cluster_reachable; then
      log_sub "使用 kubeconfig: ${KUBECONFIG}"
      return 0
    fi
    unset KUBECONFIG
  done

  fail "无法连接 Kubernetes API，请先配置 kubeconfig"
}

show_summary() {
  if [[ "$INSTALL_SUMMARY_PRINTED" == "1" ]]; then
    return 0
  fi
  INSTALL_SUMMARY_PRINTED=1
  print_install_summary
}

on_exit() {
  local exit_code=$?
  if [[ "$exit_code" -eq 0 && "$INSTALL_DEPLOYED" == "1" ]]; then
    show_summary
  fi
}
trap on_exit EXIT

stop_old_install() {
  if [[ -f "${INSTALL_DIR}/scripts/lib.sh" ]]; then
    ROOT="$INSTALL_DIR"
    # shellcheck source=scripts/lib.sh
    . "${ROOT}/scripts/lib.sh"
    remove_existing || true
  fi
  if [[ "$SKIP_DOWNLOAD" != "1" ]]; then
    cd /opt 2>/dev/null || cd "${TMPDIR:-/tmp}" 2>/dev/null || true
    rm -rf "$INSTALL_DIR"
  fi
}

echo ""
echo -e "${CYN}========================================================${RST}"
echo -e "${BLD} DataBuff AI APM (K8s)  一键安装 v${APM_VERSION}${RST}"
echo -e "${DIM} 全自动执行，无需输入，请稍候${RST}"
echo -e "${CYN}========================================================${RST}"
echo ""

log "${BLD}(1/5)${RST} 检查运行环境"
for cmd in curl tar kubectl; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    fail "缺少命令: $cmd"
  fi
done
ensure_kube_access
log_done "${BLD}(1/5)${RST} 检查运行环境"

log "${BLD}(2/5)${RST} 清理旧版本"
if [[ -e "$INSTALL_DIR" ]]; then
  stop_old_install
  log_done "${BLD}(2/5)${RST} 清理旧版本"
else
  log_skip "${BLD}(2/5)${RST} 清理旧版本"
fi

if [[ "$SKIP_DOWNLOAD" != "1" ]]; then
  log "${BLD}(3/5)${RST} 下载部署包"
  TMP="$(mktemp "${TMPDIR:-/tmp}/apm-k8s-install.XXXXXX.tar.gz")"
  PKG_URL="$(apm_k8s_pkg_download_url "$K8S_PKG")"
  curl -fsSL "$PKG_URL" -o "$TMP"
  log_done "${BLD}(3/5)${RST} 下载部署包"

  log "${BLD}(4/5)${RST} 解压部署文件"
  mkdir -p "$INSTALL_DIR"
  tar -xzf "$TMP" -C "$INSTALL_DIR" --strip-components=1
  rm -f "$TMP"
  chmod +x "${INSTALL_DIR}/install.sh" "${INSTALL_DIR}/start.sh" "${INSTALL_DIR}/stop.sh" 2>/dev/null || true
  log_done "${BLD}(4/5)${RST} 解压到 ${INSTALL_DIR}"
else
  log_skip "${BLD}(3/5)${RST} 下载部署包 (SKIP_DOWNLOAD=1)"
  log_skip "${BLD}(4/5)${RST} 解压部署文件 (SKIP_DOWNLOAD=1)"
fi

ROOT="$INSTALL_DIR"
if [[ ! -d "${ROOT}/manifests" || ! -f "${ROOT}/scripts/lib.sh" ]]; then
  fail "安装目录无效: ${ROOT}"
fi

# shellcheck source=scripts/lib.sh
. "${ROOT}/scripts/lib.sh"
chmod +x "${ROOT}/install.sh" "${ROOT}/start.sh" "${ROOT}/stop.sh" 2>/dev/null || true
INSTALL_DEPLOYED=1

if [[ "$SKIP_START" == "1" ]]; then
  log_skip "${BLD}(5/5)${RST} 部署服务 (SKIP_START=1)"
else
  log "${BLD}(5/5)${RST} 部署服务"
  export APM_LOG_PREFIX=install
  INSTALL_SKIP_UNINSTALL=1 deploy_stack
  log_done "${BLD}(5/5)${RST} 部署服务"
fi
