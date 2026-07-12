#!/usr/bin/env bash
# Doris install failure → Web troubleshooting bootstrap → recovery E2E (KR8).
#
# Simulates Doris FE/BE healthcheck failure via docker-compose.override.yml (BE mem_limit),
# asserts Web bootstrap mode, then removes override and verifies full stack recovery.
#
# Usage (113, offline 140 bundle — P0):
#   export BUNDLE_ROOT=/root/databuff-e2e-offline-bundles/140/databuff-ai-apm-offline-<VER>-amd64
#   export APM_INSTALL_DIR=/opt/databuff-ai-apm-failover
#   ./deploy/test/doris-failover-e2e.sh
#
# Usage (online 140 — P1):
#   export INSTALL_MODE=online APM_VERSION=<TO_VERSION>
#   export APM_PKG_BASE=http://192.168.50.140/databuff
#   export APM_INSTALL_DIR=/opt/databuff-ai-apm-failover
#   ./deploy/test/doris-failover-e2e.sh
#
# Pause after failure assertions for manual 运维专家 step:
#   STOP_AFTER=ops_prompt ./deploy/test/doris-failover-e2e.sh
#
# Recovery only (after manual ops + override still present):
#   SKIP_INSTALL=1 SKIP_INJECT=1 SKIP_FAILURE_START=1 ./deploy/test/doris-failover-e2e.sh
#
# Environment:
#   APM_INSTALL_DIR       default /opt/databuff-ai-apm-failover
#   REINSTALL_DIR         default ${APM_INSTALL_DIR}-reinstall
#   APM_VERSION           required for online mode (or read from BUNDLE_ROOT/VERSION)
#   APM_PKG_BASE          default http://192.168.50.140/databuff
#   BUNDLE_ROOT           offline bundle extract dir (offline mode)
#   INSTALL_MODE          offline|online (default offline when BUNDLE_ROOT set, else online)
#   BE_MEM_LIMIT          BE override mem_limit (default 256m)
#   SIMULATE_OFFLINE      1 = iptables DROP egress before failure start (default 0)
#   STOP_AFTER            install|inject|failure|ops_prompt|recovery|reinstall|all
#   SKIP_INSTALL          1
#   SKIP_INJECT           1
#   SKIP_FAILURE_START    1
#   SKIP_RECOVERY         1
#   SKIP_REINSTALL        1
#   SKIP_OPS_PROMPT       1 = do not print 运维专家 instructions
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
DEPLOY_DIR="${REPO_ROOT}/deploy"

APM_INSTALL_DIR="${APM_INSTALL_DIR:-/opt/databuff-ai-apm-failover}"
REINSTALL_DIR="${REINSTALL_DIR:-${APM_INSTALL_DIR}-reinstall}"
APM_PKG_BASE="${APM_PKG_BASE:-http://192.168.50.140/databuff}"
BUNDLE_ROOT="${BUNDLE_ROOT:-}"
INSTALL_MODE="${INSTALL_MODE:-}"
BE_MEM_LIMIT="${BE_MEM_LIMIT:-256m}"
SIMULATE_OFFLINE="${SIMULATE_OFFLINE:-0}"
STOP_AFTER="${STOP_AFTER:-all}"

SKIP_INSTALL="${SKIP_INSTALL:-0}"
SKIP_INJECT="${SKIP_INJECT:-0}"
SKIP_FAILURE_START="${SKIP_FAILURE_START:-0}"
SKIP_RECOVERY="${SKIP_RECOVERY:-0}"
SKIP_REINSTALL="${SKIP_REINSTALL:-0}"
SKIP_OPS_PROMPT="${SKIP_OPS_PROMPT:-0}"

OVERRIDE_FILE="${APM_INSTALL_DIR}/docker-compose.override.yml"
IPTABLES_BACKUP="${IPTABLES_BACKUP:-/tmp/doris-failover-iptables-backup.txt}"
START_FAIL_RC=0

log() { echo "[doris-failover-e2e] $*"; }
fail() { echo "[doris-failover-e2e] ERROR: $*" >&2; exit 1; }

maybe_stop() {
  local phase="$1"
  if [[ "$STOP_AFTER" == "$phase" ]]; then
    log "STOP_AFTER=${phase}; exiting"
    exit 0
  fi
}

require_root() {
  if [[ "$(id -u)" -ne 0 ]]; then
    fail "run as root on the test host (113)"
  fi
}

resolve_install_mode() {
  if [[ -n "$INSTALL_MODE" ]]; then
    return 0
  fi
  if [[ -n "$BUNDLE_ROOT" ]]; then
    INSTALL_MODE="offline"
  else
    INSTALL_MODE="online"
  fi
}

resolve_version() {
  if [[ -n "${APM_VERSION:-}" ]]; then
    return 0
  fi
  if [[ -n "$BUNDLE_ROOT" && -f "${BUNDLE_ROOT}/VERSION" ]]; then
    APM_VERSION="$(tr -d '[:space:]' <"${BUNDLE_ROOT}/VERSION")"
    return 0
  fi
  if command -v curl >/dev/null 2>&1; then
    APM_VERSION="$(curl -fsSL "${APM_PKG_BASE}/VERSION" | tr -d '[:space:]')" || true
  fi
  [[ -n "${APM_VERSION:-}" ]] || fail "set APM_VERSION or BUNDLE_ROOT with VERSION file"
}

stop_stack_quiet() {
  if [[ -f "${APM_INSTALL_DIR}/stop.sh" ]]; then
    (cd "$APM_INSTALL_DIR" && ./stop.sh) >/dev/null 2>&1 || true
  fi
  docker rm -f ai-apm-web ai-apm-ingest ai-apm-doris-fe ai-apm-doris-be >/dev/null 2>&1 || true
}

step_install() {
  if [[ "$SKIP_INSTALL" == "1" ]]; then
    log "skip install (SKIP_INSTALL=1)"
    return 0
  fi
  require_root
  resolve_install_mode
  resolve_version
  log "install mode=${INSTALL_MODE} version=${APM_VERSION} dir=${APM_INSTALL_DIR}"

  stop_stack_quiet
  rm -rf "$APM_INSTALL_DIR"

  if [[ "$INSTALL_MODE" == "offline" ]]; then
    [[ -n "$BUNDLE_ROOT" && -d "$BUNDLE_ROOT" ]] || fail "BUNDLE_ROOT must point to extracted offline bundle"
    [[ -f "${BUNDLE_ROOT}/install.sh" ]] || fail "missing ${BUNDLE_ROOT}/install.sh"
    (
      cd "$BUNDLE_ROOT"
      export APM_INSTALL_DIR SKIP_START=1 FORCE_LOAD_IMAGES=1
      ./install.sh
    )
  else
    export APM_PKG_BASE APM_INSTALL_DIR SKIP_START=1 FORCE_PULL_IMAGES="${FORCE_PULL_IMAGES:-1}"
    bash "${DEPLOY_DIR}/docker/ai-apm-install.sh" --version "$APM_VERSION"
  fi

  [[ -f "${APM_INSTALL_DIR}/start.sh" ]] || fail "install did not materialize ${APM_INSTALL_DIR}/start.sh"
  log "install OK (SKIP_START=1)"
  maybe_stop install
}

step_inject_failure() {
  if [[ "$SKIP_INJECT" == "1" ]]; then
    log "skip inject (SKIP_INJECT=1)"
    return 0
  fi
  log "inject BE mem_limit=${BE_MEM_LIMIT} → ${OVERRIDE_FILE}"
  cat >"$OVERRIDE_FILE" <<EOF
services:
  ai-apm-doris-be:
    mem_limit: ${BE_MEM_LIMIT}
EOF
  maybe_stop inject
}

apply_offline_firewall() {
  if [[ "$SIMULATE_OFFLINE" != "1" ]]; then
    return 0
  fi
  if ! command -v iptables >/dev/null 2>&1; then
    log "SIMULATE_OFFLINE=1 but iptables missing; continuing without egress drop"
    return 0
  fi
  log "simulate offline: iptables OUTPUT DROP (backup ${IPTABLES_BACKUP})"
  iptables-save >"$IPTABLES_BACKUP"
  iptables -A OUTPUT -o lo -j ACCEPT
  iptables -A OUTPUT -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
  iptables -A OUTPUT -j DROP
}

restore_offline_firewall() {
  if [[ "$SIMULATE_OFFLINE" != "1" ]]; then
    return 0
  fi
  if [[ -f "$IPTABLES_BACKUP" ]] && command -v iptables-restore >/dev/null 2>&1; then
    iptables-restore <"$IPTABLES_BACKUP" || true
    log "restored iptables from ${IPTABLES_BACKUP}"
  fi
}

assert_web_health() {
  curl -sf --max-time 10 "http://127.0.0.1:27403/health" >/dev/null \
    || fail "Web /health not ready (expected troubleshooting bootstrap)"
}

assert_ingest_down() {
  if curl -sf --max-time 3 "http://127.0.0.1:4318/health" >/dev/null 2>&1; then
    fail "ingest /health should not be up in troubleshooting mode"
  fi
  log "ingest not ready (expected)"
}

assert_web_container_running() {
  docker ps --format '{{.Names}}' | grep -qx 'ai-apm-web' \
    || fail "ai-apm-web container not running"
}

step_failure_start() {
  if [[ "$SKIP_FAILURE_START" == "1" ]]; then
    log "skip failure start (SKIP_FAILURE_START=1)"
    return 0
  fi
  [[ -d "$APM_INSTALL_DIR" ]] || fail "missing install dir ${APM_INSTALL_DIR}"

  apply_offline_firewall
  log "start with failure injection (expect non-zero exit)"
  set +e
  (
    cd "$APM_INSTALL_DIR"
    SKIP_PULL_IMAGES=1 START_SKIP_SUMMARY=0 ./start.sh
  )
  START_FAIL_RC=$?
  set -e
  restore_offline_firewall

  if [[ "$START_FAIL_RC" -eq 0 ]]; then
    fail "start.sh exited 0 — failure injection did not trigger (try lower BE_MEM_LIMIT?)"
  fi
  log "start.sh exit_code=${START_FAIL_RC} (expected non-zero)"

  assert_web_container_running
  assert_web_health
  assert_ingest_down
  log "failure assertions PASS (Web bootstrap, ingest down)"
  maybe_stop failure
}

print_ops_expert_prompt() {
  if [[ "$SKIP_OPS_PROMPT" == "1" ]]; then
    return 0
  fi
  local host_ip
  host_ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
  [[ -n "$host_ip" ]] || host_ip="127.0.0.1"

  cat <<EOF

======================================================================
  人工步骤：运维专家排障（task-doris-failover-offline-140）
======================================================================
1. 打开 Web UI: http://${host_ip}:27403  (admin / Databuff@123)
2. 配置 → 大模型：填写 API Key，测试连通性并保存
3. AI 平台 → 选择「运维专家」(expertId=ops)
4. 发送提示词（可复制）：

   我在 ${host_ip} 安装了 DataBuff（目录 ${APM_INSTALL_DIR}），
   install/start 失败但 Web 能打开。请 SSH 到 root@${host_ip}
   （密码见测试环境）排查 Doris FE/BE 为何未就绪，并给出修复步骤。
   安装目录：${APM_INSTALL_DIR}

5. 记录专家回复中的根因（预期：BE 内存不足 / OOM / unhealthy / mem_limit）
6. 完成后执行恢复阶段：
   SKIP_INSTALL=1 SKIP_INJECT=1 SKIP_FAILURE_START=1 \\
     APM_INSTALL_DIR=${APM_INSTALL_DIR} BUNDLE_ROOT=${BUNDLE_ROOT:-} \\
     ${SCRIPT_DIR}/doris-failover-e2e.sh

======================================================================

EOF
  maybe_stop ops_prompt
}

doris_mysql() {
  (
    cd "$APM_INSTALL_DIR"
    # shellcheck disable=SC1091
    . ./scripts/compose-env.sh
    compose_cmd exec -T ai-apm-doris-fe mysql -h127.0.0.1 -P9030 -uroot "$@"
  )
}

assert_full_stack() {
  local label="$1"
  curl -sf --max-time 10 "http://127.0.0.1:4318/health" >/dev/null \
    || fail "${label}: ingest /health failed"
  curl -sf --max-time 10 "http://127.0.0.1:27403/health" >/dev/null \
    || fail "${label}: web /health failed"

  for c in ai-apm-doris-fe ai-apm-doris-be ai-apm-ingest ai-apm-web; do
    docker ps --format '{{.Names}} {{.Status}}' | grep -q "^${c} " \
      || fail "${label}: container ${c} not running"
  done

  doris_mysql -e "SELECT 1" >/dev/null \
    || fail "${label}: Doris SELECT 1 failed"
  log "${label}: full stack OK (4 containers + Doris)"
}

step_recovery() {
  if [[ "$SKIP_RECOVERY" == "1" ]]; then
    log "skip recovery (SKIP_RECOVERY=1)"
    return 0
  fi
  log "recovery: remove override and restart"
  rm -f "$OVERRIDE_FILE"
  stop_stack_quiet
  (
    cd "$APM_INSTALL_DIR"
    SKIP_PULL_IMAGES=1 ./start.sh
  )
  assert_full_stack "recovery"
  maybe_stop recovery
}

step_reinstall_smoke() {
  if [[ "$SKIP_REINSTALL" == "1" ]]; then
    log "skip reinstall smoke (SKIP_REINSTALL=1)"
    return 0
  fi
  resolve_install_mode
  resolve_version
  log "reinstall smoke → ${REINSTALL_DIR} (no override)"

  stop_stack_quiet
  rm -rf "$REINSTALL_DIR"

  if [[ "$INSTALL_MODE" == "offline" ]]; then
    [[ -n "$BUNDLE_ROOT" && -d "$BUNDLE_ROOT" ]] || fail "BUNDLE_ROOT required for offline reinstall"
    (
      cd "$BUNDLE_ROOT"
      export APM_INSTALL_DIR="$REINSTALL_DIR" FORCE_LOAD_IMAGES=0
      ./install.sh
    )
  else
    export APM_PKG_BASE APM_INSTALL_DIR="$REINSTALL_DIR" FORCE_PULL_IMAGES="${FORCE_PULL_IMAGES:-0}"
    bash "${DEPLOY_DIR}/docker/ai-apm-install.sh" --version "$APM_VERSION"
  fi

  assert_full_stack "reinstall"
  maybe_stop reinstall
}

main() {
  log "STOP_AFTER=${STOP_AFTER} INSTALL_DIR=${APM_INSTALL_DIR}"
  step_install
  step_inject_failure
  step_failure_start
  print_ops_expert_prompt
  step_recovery
  step_reinstall_smoke
  log "KR8 doris-failover E2E complete"
}

main "$@"
