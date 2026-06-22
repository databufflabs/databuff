#!/usr/bin/env bash
# Compose helpers compatible with docker compose v2 and docker-compose v1.

_DOCKER_COMPOSE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

_load_compose_deploy_env() {
  local env_file=""
  if [ -f "${_DOCKER_COMPOSE_ROOT}/env.sh" ]; then
    env_file="${_DOCKER_COMPOSE_ROOT}/env.sh"
  elif [ -f "${_DOCKER_COMPOSE_ROOT}/../env.sh" ]; then
    env_file="${_DOCKER_COMPOSE_ROOT}/../env.sh"
  else
    echo "[compose] missing env.sh (expected ${_DOCKER_COMPOSE_ROOT}/env.sh)" >&2
    exit 1
  fi
  set -a
  # shellcheck disable=SC1091
  . "$env_file"
  set +a
  if [ -f "${_DOCKER_COMPOSE_ROOT}/VERSION" ]; then
    export APM_VERSION
    APM_VERSION="$(tr -d '[:space:]' <"${_DOCKER_COMPOSE_ROOT}/VERSION")"
  fi
  if declare -F apm_refresh_image_refs >/dev/null 2>&1; then
    apm_refresh_image_refs
  fi
}
_load_compose_deploy_env

# shellcheck source=../../common/scripts/check-compose.sh
if [ -f "${_DOCKER_COMPOSE_ROOT}/scripts/check-compose.sh" ]; then
  # shellcheck disable=SC1091
  . "${_DOCKER_COMPOSE_ROOT}/scripts/check-compose.sh"
else
  # shellcheck disable=SC1091
  . "$(cd "$(dirname "${BASH_SOURCE[0]}")/../../common/scripts" && pwd)/check-compose.sh"
fi
ensure_compose_cli

export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-databuff-ai-apm}"
apm_select_compose_file "${_DOCKER_COMPOSE_ROOT}"

APM_CONTAINERS=(
  ai-apm-web
  ai-apm-ingest
  ai-apm-doris-be
  ai-apm-doris-fe
)

remove_apm_containers() {
  local name
  for name in "${APM_CONTAINERS[@]}"; do
    docker rm -f "$name" >/dev/null 2>&1 || true
  done
}

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

compose_down_legacy_project() {
  # Older packages used different project/container names; clean them up once.
  local legacy_project
  for legacy_project in databuff-apm databuff-ai-apm; do
    if docker compose version >/dev/null 2>&1; then
      COMPOSE_PROJECT_NAME="$legacy_project" docker compose down --remove-orphans >/dev/null 2>&1 || true
    elif command -v docker-compose >/dev/null 2>&1; then
      COMPOSE_PROJECT_NAME="$legacy_project" docker-compose down --remove-orphans >/dev/null 2>&1 || true
    fi
  done
  docker network rm \
    databuff-apm_apm databuff-apm_default \
    databuff-ai-apm_apm \
    databuff-ai-apm_apm databuff-ai-apm_default \
    2>/dev/null || true
}

prepare_compose_start() {
  if ! compose_cmd config >/dev/null 2>&1; then
    echo "[compose] ERROR: invalid docker-compose.yml:" >&2
    compose_cmd config >&2 || true
    exit 1
  fi
  compose_cmd down --remove-orphans >/dev/null 2>&1 || true
  compose_down_legacy_project
  remove_apm_containers
}

compose_up() {
  if compose_supports_wait; then
    compose_cmd up -d --wait "$@"
  else
    compose_cmd up -d "$@"
  fi
}

compose_down() {
  compose_cmd down --remove-orphans "$@"
  compose_down_legacy_project
  remove_apm_containers
}
