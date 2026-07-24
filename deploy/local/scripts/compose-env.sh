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
export LOCAL_JDK_IMAGE="${LOCAL_JDK_IMAGE:-databuff-local/jdk-dev:17-jammy}"

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

# Build -f args from COMPOSE_FILE (colon-separated) + auto-include override.yml
compose_f_args() {
  local f override root
  root="$(cd "$(dirname "${COMPOSE_FILE%%:*}")" && pwd)"
  for f in $(echo "$COMPOSE_FILE" | tr ":" " "); do
    printf -- "-f\0%s\0" "$f"
  done
  override="${root}/docker-compose.override.yml"
  case ":${COMPOSE_FILE}:" in
    *":${override}:"*) ;;
    *)
      if [ -f "$override" ]; then
        printf -- "-f\0%s\0" "$override"
      fi
      ;;
  esac
}

compose_cmd() {
  local args=()
  local f
  while IFS= read -r -d "" f; do
    args+=("$f")
  done < <(compose_f_args)
  if docker compose version >/dev/null 2>&1; then
    docker compose "${args[@]}" "$@"
  elif command -v docker-compose >/dev/null 2>&1; then
    docker-compose "${args[@]}" "$@"
  else
    echo "[compose] docker compose not found" >&2
    exit 1
  fi
}

compose_supports_wait() {
  compose_cmd up --help 2>/dev/null | grep -q -- '--wait'
}

compose_supports_wait_timeout() {
  compose_cmd up --help 2>/dev/null | grep -q -- '--wait-timeout'
}

compose_up() {
  compose_cmd up -d "$@"
}

# Default 180s; stuck Doris "Waiting" must fail into Web 排障. Override: COMPOSE_WAIT_TIMEOUT.
compose_up_wait() {
  local wait_timeout="${COMPOSE_WAIT_TIMEOUT:-180}"
  if compose_supports_wait; then
    echo "[compose] waiting for healthy (timeout=${wait_timeout}s): $*" >&2
    if compose_supports_wait_timeout; then
      compose_cmd up -d --wait --wait-timeout "$wait_timeout" "$@"
    elif command -v timeout >/dev/null 2>&1; then
      # timeout(1) cannot invoke bash functions; call the compose binary directly.
      if docker compose version >/dev/null 2>&1; then
        timeout "$wait_timeout" docker compose -f "$COMPOSE_FILE" up -d --wait "$@"
      else
        timeout "$wait_timeout" docker-compose -f "$COMPOSE_FILE" up -d --wait "$@"
      fi
    else
      compose_cmd up -d --wait "$@"
    fi
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
