#!/usr/bin/env bash
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-databuff-ai-apm-demo}"

compose_cmd() {
  if docker compose version >/dev/null 2>&1; then
    docker compose "$@"
  elif command -v docker-compose >/dev/null 2>&1; then
    docker-compose "$@"
  else
    echo "[start] docker compose not found" >&2
    exit 1
  fi
}

detect_local_ip() {
  ip=""
  if command -v ip >/dev/null 2>&1; then
    ip="$(ip route get 1.1.1.1 2>/dev/null | awk '{for (i=1;i<=NF;i++) if ($i=="src") {print $(i+1); exit}}')"
  fi
  if [ -z "$ip" ] && command -v hostname >/dev/null 2>&1; then
    ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
  fi
  if [ -z "$ip" ]; then
    echo "[start] cannot detect local IP, set INGEST_HOST" >&2
    exit 1
  fi
  echo "$ip"
}

if [ -f "${ROOT}/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "${ROOT}/.env"
  set +a
elif [ -f "${ROOT}/env.sh" ]; then
  set -a
  # shellcheck disable=SC1091
  . "${ROOT}/env.sh"
  set +a
elif [ -f "${ROOT}/../../env.sh" ]; then
  set -a
  # shellcheck disable=SC1091
  . "${ROOT}/../../env.sh"
  set +a
fi

if [ -f "${ROOT}/VERSION" ]; then
  APM_VERSION="$(tr -d '[:space:]' <"${ROOT}/VERSION")"
  export APM_VERSION
fi

INGEST_HOST="${INGEST_HOST:-$(detect_local_ip)}"
INGEST_PORT="${INGEST_PORT:-4318}"
export OTEL_EXPORTER_OTLP_ENDPOINT="${OTEL_EXPORTER_OTLP_ENDPOINT:-http://${INGEST_HOST}:${INGEST_PORT}}"
export SKYWALKING_GRPC_TARGET="${SKYWALKING_GRPC_TARGET:-${INGEST_HOST}:11800}"
export SEED_PROTOCOL="${SEED_PROTOCOL:-otlp}"
export SEED_INTERVAL_SECONDS="${SEED_INTERVAL_SECONDS:-30}"

if declare -F apm_refresh_image_refs >/dev/null 2>&1; then
  apm_refresh_image_refs
fi
if declare -F apm_refresh_image_pkg_bases >/dev/null 2>&1; then
  apm_refresh_image_pkg_bases
fi

if [ -z "${APM_DEMO_IMAGE:-}" ]; then
  echo "[start] missing APM_DEMO_IMAGE" >&2
  exit 1
fi

if ! docker image inspect "$APM_DEMO_IMAGE" >/dev/null 2>&1; then
  echo "[start] missing image ${APM_DEMO_IMAGE}; run install/update or ./scripts/pull-images.sh first" >&2
  exit 1
fi

echo "[start] OTLP endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT} (interval ${SEED_INTERVAL_SECONDS}s)"
echo "[start] SkyWalking gRPC: ${SKYWALKING_GRPC_TARGET} (protocol ${SEED_PROTOCOL})"
compose_cmd up -d

if [ "${START_SKIP_READY:-0}" != "1" ]; then
  echo ""
  echo "[start] demo seeder running"
  echo "  Target : ${OTEL_EXPORTER_OTLP_ENDPOINT}/v1/traces"
  echo "  Logs   : docker logs -f ai-apm-demo"
  echo "  Stop   : ./stop.sh"
  echo ""
fi
