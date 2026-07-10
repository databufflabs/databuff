#!/usr/bin/env bash
# Compose helpers for deploy/local.

_LOCAL_COMPOSE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export COMPOSE_FILE="${COMPOSE_FILE:-${_LOCAL_COMPOSE_ROOT}/docker-compose.yml}"
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-databuff-ai-apm-local}"

_deploy_env="${_LOCAL_COMPOSE_ROOT}/../env.sh"
if [ ! -f "$_deploy_env" ]; then
  echo "[compose] missing ${_deploy_env}" >&2
  exit 1
fi
set -a
# shellcheck disable=SC1091
. "$_deploy_env"
set +a

export JDK_IMAGE="${JDK_IMAGE:-eclipse-temurin:17-jdk-jammy}"
export LOCAL_WEB_IMAGE="${LOCAL_WEB_IMAGE:-databuff-local/web-dev:17-jdk-jammy}"
export LOCAL_WEB_IMAGE="${LOCAL_WEB_IMAGE:-databuff-local/web-dev:17-jdk-jammy}"

DORIS_FE_SERVICE="ai-apm-doris-fe"
DORIS_BE_SERVICE="ai-apm-doris-be"
INGEST_SERVICE="ai-apm-ingest"
WEB_SERVICE="ai-apm-web"
DEMO_SERVICE="ai-apm-demo"

APM_CONTAINERS=(
  ai-apm-demo
  ai-apm-web
  ai-apm-ingest
  ai-apm-doris-be
  ai-apm-doris-fe
)

compose_cmd() {
  if docker compose version >/dev/null 2>&1; then
    docker compose -f "$COMPOSE_FILE" "$@"
  elif command -v docker-compose >/dev/null 2>&1; then
    docker-compose -f "$COMPOSE_FILE" "$@"
  else
    echo "[compose] docker compose not found" >&2
    exit 1
  fi
}

compose_supports_wait() {
  compose_cmd up --help 2>/dev/null | grep -q -- '--wait'
}

compose_up() {
  compose_cmd up -d "$@"
}

compose_up_wait() {
  if compose_supports_wait; then
    compose_cmd up -d --wait "$@"
  else
    compose_cmd up -d "$@"
  fi
}

compose_down() {
  compose_cmd down --remove-orphans "$@"
}

# JAR 目录挂载：compose up 不会重启已在运行的容器，需显式 restart 以加载新产物。
force_restart_jar_services() {
  echo "[start] force restarting ${INGEST_SERVICE} ${WEB_SERVICE} to load updated jars"
  compose_cmd restart "$INGEST_SERVICE" "$WEB_SERVICE"
}
