#!/usr/bin/env bash
# Doris schema incremental migrations (used by Docker update.sh / migrate-schema.sh).
# Caller must define doris_mysql() before sourcing this file.

schema_migrations_dir() {
  local root="${1:-}"
  if [[ -f "${root}/sql/migrations/V001__baseline.sql" ]]; then
    printf '%s\n' "${root}/sql/migrations"
    return 0
  fi
  if [[ -f "${root}/../common/sql/migrations/V001__baseline.sql" ]]; then
    printf '%s\n' "${root}/../common/sql/migrations"
    return 0
  fi
  if [[ -n "${DATABUFF_DEPLOY_COMMON:-}" && -f "${DATABUFF_DEPLOY_COMMON}/sql/migrations/V001__baseline.sql" ]]; then
    printf '%s\n' "${DATABUFF_DEPLOY_COMMON}/sql/migrations"
    return 0
  fi
  return 1
}

schema_migration_version_from_file() {
  local base
  base="$(basename "$1")"
  if [[ "$base" =~ ^V([0-9]+)__ ]]; then
    printf '%s\n' "$((10#${BASH_REMATCH[1]}))"
    return 0
  fi
  return 1
}

schema_latest_migration_version() {
  local dir="$1"
  local f max=0 ver
  shopt -s nullglob
  for f in "${dir}"/V*.sql; do
    ver="$(schema_migration_version_from_file "$f" || echo 0)"
    if [[ "$ver" -gt "$max" ]]; then
      max="$ver"
    fi
  done
  shopt -u nullglob
  printf '%s\n' "$max"
}

schema_database_exists() {
  doris_mysql -N -e "SHOW DATABASES LIKE 'databuff'" 2>/dev/null | grep -qx databuff
}

schema_version_table_exists() {
  doris_mysql -N -e "SHOW TABLES FROM databuff LIKE 'schema_version'" 2>/dev/null | grep -qx schema_version
}

schema_current_version() {
  if ! schema_database_exists; then
    printf '0\n'
    return 0
  fi
  if ! schema_version_table_exists; then
    printf '0\n'
    return 0
  fi
  doris_mysql -N -e "SELECT version FROM databuff.schema_version WHERE id = 1 LIMIT 1" 2>/dev/null | head -n1 | tr -d '[:space:]'
}

schema_upsert_version_row() {
  local version="$1"
  if ! schema_version_table_exists; then
    return 1
  fi
  doris_mysql -e "DELETE FROM databuff.schema_version WHERE id = 1" >/dev/null 2>&1 || true
  doris_mysql -e "INSERT INTO databuff.schema_version (id, version, applied_at) VALUES (1, ${version}, NOW())"
}

schema_stamp_version() {
  local version="$1"
  schema_upsert_version_row "$version"
}

# Apply pending migrations under $dir; returns 0 when up to date or successfully migrated.
schema_apply_migrations() {
  local root="${1:-.}"
  local dir log_prefix="${2:-[schema-migrate]}"
  local current target f file_ver applied=0

  dir="$(schema_migrations_dir "$root" || true)"
  if [[ -z "$dir" ]]; then
    echo "${log_prefix} no migrations directory, skip" >&2
    return 0
  fi

  if ! schema_database_exists; then
    echo "${log_prefix} database databuff not found, skip (fresh install uses databuff.sql)" >&2
    return 0
  fi

  target="$(schema_latest_migration_version "$dir")"
  current="$(schema_current_version)"
  current="${current:-0}"

  if [[ "$current" -ge "$target" ]]; then
    echo "${log_prefix} schema up to date (version=${current})" >&2
    return 0
  fi

  echo "${log_prefix} migrating schema ${current} -> ${target}" >&2
  shopt -s nullglob
  for f in "${dir}"/V*.sql; do
    file_ver="$(schema_migration_version_from_file "$f" || continue)"
    if [[ "$file_ver" -le "$current" ]]; then
      continue
    fi
    echo "${log_prefix} applying $(basename "$f") ..." >&2
    apply_doris_sql_file "$f" || return 1
    schema_upsert_version_row "$file_ver" || {
      echo "${log_prefix} failed to record schema version ${file_ver}" >&2
      return 1
    }
    applied=1
    current="$file_ver"
  done
  shopt -u nullglob

  if [[ "$applied" -eq 1 ]]; then
    echo "${log_prefix} schema migration complete (version=${current})" >&2
  fi
  return 0
}
