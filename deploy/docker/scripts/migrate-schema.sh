#!/usr/bin/env bash
# Run Doris schema migrations after FE/BE are up (Docker update / manual).
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# shellcheck source=compose-env.sh
. "${ROOT}/scripts/compose-env.sh"

DORIS_SERVICE="${DORIS_SERVICE:-ai-apm-doris-fe}"
MAX="${DORIS_INIT_MAX:-120}"

doris_mysql() {
  compose_cmd exec -T "$DORIS_SERVICE" mysql -h127.0.0.1 -P9030 -uroot "$@"
}

source_libs() {
  local p
  for p in \
    "${ROOT}/scripts/doris-be-wait.sh" \
    "${ROOT}/../common/scripts/doris-be-wait.sh"; do
    if [[ -f "$p" ]]; then
      # shellcheck disable=SC1090,SC1091
      . "$p"
      break
    fi
  done
  for p in \
    "${ROOT}/scripts/schema-migrate.sh" \
    "${ROOT}/../common/scripts/schema-migrate.sh"; do
    if [[ -f "$p" ]]; then
      # shellcheck disable=SC1090,SC1091
      . "$p"
      return 0
    fi
  done
  echo "[migrate-schema] missing schema-migrate.sh" >&2
  exit 1
}

source_libs

if ! schema_database_exists; then
  echo "[migrate-schema] database databuff not found, skip"
  exit 0
fi

echo "[migrate-schema] waiting FE in ${DORIS_SERVICE}"
i=1
while [[ "$i" -le "$MAX" ]]; do
  if doris_mysql -e "SELECT 1" >/dev/null 2>&1; then
    break
  fi
  if [[ "$i" -eq "$MAX" ]]; then
    echo "[migrate-schema] timeout waiting for Doris FE" >&2
    exit 1
  fi
  sleep 2
  i=$((i + 1))
done

if [[ "${SKIP_BE_WAIT:-0}" != "1" ]]; then
  wait_for_be_alive "[migrate-schema]" "$MAX" 3
  wait_for_be_avail_stable "[migrate-schema]" 120
fi

schema_apply_migrations "$ROOT" "[migrate-schema]"
