#!/usr/bin/env bash
# DataBuff Docker upgrade E2E skeleton (CI / local manual runs).
#
# Flow: install (FROM_VERSION) → seed demo → update (TO_VERSION) → verify
#
# Usage:
#   FROM_VERSION=0.1.1 TO_VERSION=0.1.2 ./test-upgrade-e2e.sh
#   INSTALL_DIR=/tmp/databuff-upgrade-test FROM_VERSION=0.1.1 TO_VERSION=0.1.2 ./test-upgrade-e2e.sh
#   RUN_API_TESTS=1 FROM_VERSION=0.1.1 TO_VERSION=0.1.2 ./test-upgrade-e2e.sh
#
# Environment:
#   FROM_VERSION       Source release to install (required)
#   TO_VERSION         Target release to upgrade to (required)
#   INSTALL_DIR        Install path (default: /tmp/databuff-upgrade-e2e)
#   APM_PKG_BASE       Package download base (default from deploy/env.sh)
#   SKIP_INSTALL       1 = reuse existing INSTALL_DIR (must match FROM_VERSION)
#   SKIP_SEED          1 = skip demo install / telemetry seed
#   SKIP_UPDATE        1 = skip update step (verify only)
#   SKIP_VERIFY        1 = skip verify-upgrade.sh
#   RUN_API_TESTS      1 = run deploy/test/run-tests.sh after verify
#   TEST_BASE_URL      Base URL for API tests (default http://127.0.0.1:27403)
#
# Prerequisites: root, Docker, curl, python3; network or offline bundles for both versions.
# Full CI wiring is environment-specific — this script documents the intended sequence.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
DEPLOY_DIR="${REPO_ROOT}/deploy"

FROM_VERSION="${FROM_VERSION:-}"
TO_VERSION="${TO_VERSION:-}"
INSTALL_DIR="${INSTALL_DIR:-/tmp/databuff-upgrade-e2e}"
SKIP_INSTALL="${SKIP_INSTALL:-0}"
SKIP_SEED="${SKIP_SEED:-0}"
SKIP_UPDATE="${SKIP_UPDATE:-0}"
SKIP_VERIFY="${SKIP_VERIFY:-0}"
RUN_API_TESTS="${RUN_API_TESTS:-0}"

log() { echo "[test-upgrade-e2e] $*"; }
fail() { echo "[test-upgrade-e2e] ERROR: $*" >&2; exit 1; }

[[ -n "$FROM_VERSION" ]] || fail "FROM_VERSION is required"
[[ -n "$TO_VERSION" ]] || fail "TO_VERSION is required"

if [[ -f "${DEPLOY_DIR}/env.sh" ]]; then
  # shellcheck disable=SC1091
  . "${DEPLOY_DIR}/env.sh"
fi
export APM_PKG_BASE="${APM_PKG_BASE:-}"
export APM_INSTALL_DIR="$INSTALL_DIR"

verify_script="${INSTALL_DIR}/scripts/verify-upgrade.sh"
run_tests="${SCRIPT_DIR}/run-tests.sh"

step_install() {
  if [[ "$SKIP_INSTALL" == "1" ]]; then
    log "skip install (SKIP_INSTALL=1)"
    return 0
  fi
  log "install ${FROM_VERSION} → ${INSTALL_DIR}"
  if [[ "$(id -u)" -ne 0 ]]; then
    fail "install requires root (or pre-populate INSTALL_DIR and set SKIP_INSTALL=1)"
  fi
  APM_INSTALL_DIR="$INSTALL_DIR" APM_VERSION="$FROM_VERSION" \
    bash "${DEPLOY_DIR}/docker/ai-apm-install.sh" --version "$FROM_VERSION"
}

step_seed() {
  if [[ "$SKIP_SEED" == "1" ]]; then
    log "skip seed (SKIP_SEED=1)"
    return 0
  fi
  log "seed demo telemetry (demo container → ingest on ${INSTALL_DIR})"
  local demo_dir="${INSTALL_DIR}-demo"
  if [[ -f "${DEPLOY_DIR}/docker/ai-apm-demo-install.sh" ]]; then
    APM_INSTALL_DIR="$demo_dir" INGEST_HOST=127.0.0.1 INGEST_PORT=4318 \
      APM_VERSION="$FROM_VERSION" \
      bash "${DEPLOY_DIR}/docker/ai-apm-demo-install.sh" --version "$FROM_VERSION" || {
        log "demo install failed — continue without seed (set SKIP_SEED=1 to silence)"
      }
  else
    log "no demo install script; skip seed"
  fi
}

step_update() {
  if [[ "$SKIP_UPDATE" == "1" ]]; then
    log "skip update (SKIP_UPDATE=1)"
    return 0
  fi
  log "update ${FROM_VERSION} → ${TO_VERSION}"
  [[ -d "$INSTALL_DIR" ]] || fail "install dir missing: ${INSTALL_DIR}"
  (
    cd "$INSTALL_DIR"
    ./update.sh --version "$TO_VERSION"
  )
}

step_verify() {
  if [[ "$SKIP_VERIFY" == "1" ]]; then
    log "skip verify (SKIP_VERIFY=1)"
    return 0
  fi
  if [[ ! -x "$verify_script" ]]; then
    fail "verify script not found or not executable: ${verify_script}"
  fi
  log "run ${verify_script} --expected-version=${TO_VERSION}"
  (
    cd "$INSTALL_DIR"
    ./scripts/verify-upgrade.sh --expected-version="$TO_VERSION"
  )
}

step_api_tests() {
  if [[ "$RUN_API_TESTS" != "1" ]]; then
    return 0
  fi
  [[ -x "$run_tests" ]] || fail "run-tests.sh not executable: ${run_tests}"
  log "run API regression tests"
  TEST_BASE_URL="${TEST_BASE_URL:-http://127.0.0.1:27403}" \
    TEST_WARMUP_SECONDS="${TEST_WARMUP_SECONDS:-60}" \
    "$run_tests"
}

main() {
  log "FROM=${FROM_VERSION} TO=${TO_VERSION} INSTALL_DIR=${INSTALL_DIR}"
  step_install
  step_seed
  step_update
  step_verify
  step_api_tests
  log "done"
}

main "$@"
