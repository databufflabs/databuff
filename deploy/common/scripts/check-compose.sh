#!/usr/bin/env bash
# Docker Compose availability check and compose file selection.

APM_COMPOSE_MIN_V1="${APM_COMPOSE_MIN_V1:-1.18.0}"
APM_COMPOSE_MODERN_MIN_V1="${APM_COMPOSE_MODERN_MIN_V1:-1.29.0}"

_apm_compose_v1_version() {
  docker-compose --version 2>/dev/null | sed -E 's/.*version ([0-9]+\.[0-9]+\.[0-9]+).*/\1/' | head -n1
}

_apm_version_ge() {
  local ver="$1"
  local min="$2"
  [ -n "$ver" ] && printf '%s\n%s\n' "$min" "$ver" | sort -V -C 2>/dev/null
}

ensure_compose_cli() {
  if docker compose version >/dev/null 2>&1; then
    return 0
  fi
  if ! command -v docker-compose >/dev/null 2>&1; then
    echo "[compose] ERROR: Docker Compose not found." >&2
    echo "[compose] Install the Compose v2 plugin (recommended):" >&2
    echo "[compose]   yum install -y docker-compose-plugin" >&2
    echo "[compose]   apt-get install -y docker-compose-plugin" >&2
    exit 1
  fi
  local ver
  ver="$(_apm_compose_v1_version)"
  if ! _apm_version_ge "$ver" "$APM_COMPOSE_MIN_V1"; then
    echo "[compose] ERROR: docker-compose ${ver:-unknown} is too old (need >= ${APM_COMPOSE_MIN_V1})." >&2
    echo "[compose] Upgrade to Docker Compose v2 plugin:" >&2
    echo "[compose]   yum install -y docker-compose-plugin" >&2
    echo "[compose]   apt-get install -y docker-compose-plugin" >&2
    exit 1
  fi
}

apm_select_compose_file() {
  local root="$1"
  local default_file="${root}/docker-compose.yml"
  local legacy_file="${root}/docker-compose.legacy.yml"

  if [ -n "${COMPOSE_FILE:-}" ]; then
    return 0
  fi

  if docker compose version >/dev/null 2>&1; then
    export COMPOSE_FILE="$default_file"
    return 0
  fi

  local ver
  ver="$(_apm_compose_v1_version)"
  if _apm_version_ge "$ver" "$APM_COMPOSE_MODERN_MIN_V1"; then
    export COMPOSE_FILE="$default_file"
    return 0
  fi

  if [ -f "$legacy_file" ]; then
    echo "[compose] docker-compose ${ver}: using legacy compose file" >&2
    export COMPOSE_FILE="$legacy_file"
    return 0
  fi

  echo "[compose] WARNING: docker-compose ${ver} may not support docker-compose.yml;" >&2
  echo "[compose] missing ${legacy_file}; falling back to default compose file" >&2
  export COMPOSE_FILE="$default_file"
}
