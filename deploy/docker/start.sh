#!/usr/bin/env bash
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

DORIS_DATA_FE="${ROOT}/data/fe-meta"
DORIS_DATA_BE="${ROOT}/data/be-storage"
DORIS_FE_SERVICE="ai-apm-doris-fe"
DORIS_BE_SERVICE="ai-apm-doris-be"
INGEST_SERVICE="ai-apm-ingest"
WEB_SERVICE="ai-apm-web"

doris_has_data() {
  if [ -d "$DORIS_DATA_FE" ] && [ -n "$(ls -A "$DORIS_DATA_FE" 2>/dev/null)" ]; then
    return 0
  fi
  if [ -d "$DORIS_DATA_BE" ] && [ -n "$(ls -A "$DORIS_DATA_BE" 2>/dev/null)" ]; then
    return 0
  fi
  return 1
}

chmod +x "${ROOT}/scripts/"*.sh 2>/dev/null || true

if [ -f "${ROOT}/env.sh" ]; then
  set -a
  # shellcheck disable=SC1091
  . "${ROOT}/env.sh"
  set +a
fi
if [ -f "${ROOT}/VERSION" ]; then
  export APM_VERSION
  APM_VERSION="$(tr -d '[:space:]' <"${ROOT}/VERSION")"
fi
if declare -F apm_refresh_image_refs >/dev/null 2>&1; then
  apm_refresh_image_refs
fi
if declare -F apm_refresh_image_pkg_bases >/dev/null 2>&1; then
  apm_refresh_image_pkg_bases
fi

# shellcheck source=scripts/compose-env.sh
. "${ROOT}/scripts/compose-env.sh"
# shellcheck source=scripts/runtime.sh
. "${ROOT}/scripts/runtime.sh"
ensure_vm_max_map_count
prepare_compose_start

if doris_has_data; then
  echo "[start] doris data exists, starting all services"
  compose_up_wait "$DORIS_FE_SERVICE" "$DORIS_BE_SERVICE" || { bootstrap_web_for_troubleshooting; exit 1; }
  compose_up "$INGEST_SERVICE" "$WEB_SERVICE"
else
  echo "[start] initializing doris"
  mkdir -p "$DORIS_DATA_FE" "$DORIS_DATA_BE"
  compose_up_wait "$DORIS_FE_SERVICE" "$DORIS_BE_SERVICE" || { bootstrap_web_for_troubleshooting; exit 1; }
  "${ROOT}/scripts/init-doris.sh"
  compose_up "$INGEST_SERVICE" "$WEB_SERVICE"
fi

if [ "${START_SKIP_READY:-0}" != "1" ]; then
  wait_for_apm_services_ready
fi
if [ "${START_SKIP_READY:-0}" != "1" ] && [ "${START_SKIP_SUMMARY:-0}" != "1" ]; then
  print_apm_ready_summary
fi
