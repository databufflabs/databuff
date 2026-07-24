#!/usr/bin/env bash
# Post-upgrade verification for Docker in-place upgrades.
# Usage:
#   ./scripts/verify-upgrade.sh --expected-version=0.1.5
#   EXPECTED_VERSION=0.1.5 ./scripts/verify-upgrade.sh
#   ./scripts/verify-upgrade.sh --skip-health --skip-schema

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
INSTALL_DIR="${APM_INSTALL_DIR:-${INSTALL_DIR:-$ROOT}}"

SKIP_HEALTH=0
SKIP_SCHEMA=0
EXPECTED_VERSION="${EXPECTED_VERSION:-}"
FAILED=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --expected-version=*)
      EXPECTED_VERSION="${1#--expected-version=}"
      shift
      ;;
    --expected-version)
      [[ $# -ge 2 ]] || {
        echo "[verify] FAIL: --expected-version requires a value" >&2
        exit 1
      }
      EXPECTED_VERSION="$2"
      shift 2
      ;;
    --skip-health)
      SKIP_HEALTH=1
      shift
      ;;
    --skip-schema)
      SKIP_SCHEMA=1
      shift
      ;;
    -h | --help)
      echo "Usage: $0 [--expected-version=VER] [--skip-health] [--skip-schema]"
      exit 0
      ;;
    *)
      echo "[verify] FAIL: unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

verify_ok() {
  echo "[verify] OK: $*"
}

verify_fail() {
  echo "[verify] FAIL: $*" >&2
  FAILED=1
}

verify_info() {
  echo "[verify] $*"
}

source_libs() {
  local f
  for f in \
    "${INSTALL_DIR}/scripts/apm-update-lib.sh" \
    "${INSTALL_DIR}/../common/scripts/apm-update-lib.sh" \
    "${SCRIPT_DIR}/apm-update-lib.sh" \
    "${SCRIPT_DIR}/../../common/scripts/apm-update-lib.sh"; do
    if [[ -f "$f" ]]; then
      # shellcheck disable=SC1090,SC1091
      . "$f"
      return 0
    fi
  done
  verify_fail "missing apm-update-lib.sh"
  return 1
}

source_runtime() {
  local f
  for f in \
    "${INSTALL_DIR}/scripts/runtime.sh" \
    "${SCRIPT_DIR}/runtime.sh"; do
    if [[ -f "$f" ]]; then
      # shellcheck disable=SC1090,SC1091
      . "$f"
      return 0
    fi
  done
  verify_fail "missing runtime.sh"
  return 1
}

if [[ ! -d "$INSTALL_DIR" ]]; then
  echo "[verify] FAIL: install directory not found: ${INSTALL_DIR}" >&2
  exit 1
fi

cd "$INSTALL_DIR"

# shellcheck source=compose-env.sh
. "${INSTALL_DIR}/scripts/compose-env.sh"
source_libs || exit 1
source_runtime || exit 1

DORIS_SERVICE="${DORIS_SERVICE:-ai-apm-doris-fe}"

doris_mysql() {
  compose_cmd exec -T "$DORIS_SERVICE" mysql -h127.0.0.1 -P9030 -uroot "$@"
}

KEY_SERVICES=(
  ai-apm-doris-fe
  ai-apm-doris-be
  ai-apm-ingest
  ai-apm-web
)

verify_install_layout() {
  if ! apm_install_dir_ready "$INSTALL_DIR"; then
    verify_fail "install directory is missing start.sh or VERSION (${INSTALL_DIR})"
    return 1
  fi
  verify_ok "install directory layout (${INSTALL_DIR})"
}

verify_app_version() {
  local actual=""
  if ! actual="$(apm_read_version_file "${INSTALL_DIR}/VERSION")"; then
    verify_fail "VERSION file missing or unreadable (${INSTALL_DIR}/VERSION)"
    return 1
  fi

  if [[ -z "$EXPECTED_VERSION" ]]; then
    verify_info "VERSION=${actual} (no expected version supplied, skip compare)"
    return 0
  fi

  if [[ "$actual" == "$EXPECTED_VERSION" ]]; then
    verify_ok "app version ${actual}"
    return 0
  fi

  verify_fail "app version mismatch: expected ${EXPECTED_VERSION}, got ${actual}"
  return 1
}

verify_compose_services() {
  local svc state
  for svc in "${KEY_SERVICES[@]}"; do
    state="$(docker inspect -f '{{.State.Status}}' "$svc" 2>/dev/null || echo missing)"
    if [[ "$state" == "running" ]]; then
      verify_ok "service ${svc} running"
    else
      verify_fail "service ${svc} not running (state: ${state})"
    fi
  done
}

verify_schema_version() {
  local actual="" expected="" manifest="${INSTALL_DIR}/upgrade-manifest.json"

  if ! doris_mysql -e "SELECT 1" >/dev/null 2>&1; then
    verify_fail "cannot connect to Doris FE (${DORIS_SERVICE})"
    return 1
  fi

  if ! doris_mysql -N -e "SHOW DATABASES LIKE 'databuff'" 2>/dev/null | grep -qx databuff; then
    verify_fail "database databuff not found"
    return 1
  fi

  actual="$(doris_mysql -N -e "SELECT version FROM databuff.schema_version WHERE id = 1 LIMIT 1" 2>/dev/null | head -n1 | tr -d '[:space:]')"
  if [[ -z "$actual" ]]; then
    verify_fail "schema_version row missing in databuff.schema_version"
    return 1
  fi

  if [[ ! -f "$manifest" ]]; then
    verify_info "schema version ${actual} (no upgrade-manifest.json, skip compare)"
    return 0
  fi

  if ! expected="$(apm_read_upgrade_manifest_field "$manifest" schema_version 2>/dev/null)"; then
    verify_info "schema version ${actual} (upgrade-manifest.json has no schema_version, skip compare)"
    return 0
  fi

  if [[ "$actual" == "$expected" ]]; then
    verify_ok "schema version ${actual}"
    return 0
  fi

  verify_fail "schema version mismatch: expected ${expected}, got ${actual}"
  return 1
}

verify_health_endpoints() {
  # Prefer Docker container health (container-internal ports). Host publish
  # ports may be remapped via INGEST_HTTP_PORT / WEB_HTTP_PORT.
  if declare -F check_container_ready >/dev/null 2>&1; then
    if check_container_ready "ai-apm-ingest"; then
      verify_ok "ingest container healthy"
    else
      verify_fail "ingest container not healthy (status=$(container_health_status ai-apm-ingest))"
    fi

    if check_container_ready "ai-apm-web"; then
      verify_ok "web container healthy"
    else
      verify_fail "web container not healthy (status=$(container_health_status ai-apm-web))"
    fi
    return 0
  fi

  ingest_port="${INGEST_HTTP_PORT:-4318}"
  web_port="${WEB_HTTP_PORT:-27403}"
  if check_http_ready "http://127.0.0.1:${ingest_port}/health"; then
    verify_ok "ingest /health (${ingest_port})"
  else
    verify_fail "ingest /health not ready (http://127.0.0.1:${ingest_port}/health)"
  fi

  if check_http_ready "http://127.0.0.1:${web_port}/health"; then
    verify_ok "web /health (${web_port})"
  else
    verify_fail "web /health not ready (http://127.0.0.1:${web_port}/health)"
  fi
}

verify_info "starting upgrade verification in ${INSTALL_DIR}"

verify_install_layout
verify_app_version
verify_compose_services

if [[ "$SKIP_SCHEMA" != "1" ]]; then
  verify_schema_version
else
  verify_info "skip schema checks (--skip-schema)"
fi

if [[ "$SKIP_HEALTH" != "1" ]]; then
  verify_health_endpoints
else
  verify_info "skip health checks (--skip-health)"
fi

if [[ "$FAILED" -ne 0 ]]; then
  verify_fail "one or more checks failed"
  exit 1
fi

verify_ok "all checks passed"
exit 0
