#!/usr/bin/env bash
# Shared helpers for Docker in-place upgrade (update.sh / ai-apm-update.sh).

apm_version_tuple() {
  local v="${1#v}"
  v="${v#V}"
  IFS='.' read -r -a _apm_ver_parts <<<"$v"
  printf '%s\n' "${#_apm_ver_parts[@]}"
}

apm_version_part() {
  local v="${1#v}" idx="$2"
  v="${v#V}"
  IFS='.' read -r -a _apm_ver_parts <<<"$v"
  if [[ "$idx" -lt "${#_apm_ver_parts[@]}" ]]; then
    printf '%s\n' "${_apm_ver_parts[$idx]}"
  else
    printf '0\n'
  fi
}

# Returns 0 when $1 >= $2 (semver-ish, numeric segments).
apm_version_gte() {
  local a="$1" b="$2" len i av bv
  len="$(apm_version_tuple "$a")"
  if [[ "$(apm_version_tuple "$b")" -gt "$len" ]]; then
    len="$(apm_version_tuple "$b")"
  fi
  for ((i = 0; i < len; i++)); do
    av="$(apm_version_part "$a" "$i")"
    bv="$(apm_version_part "$b" "$i")"
    av="${av:-0}"
    bv="${bv:-0}"
    if [[ "$av" -gt "$bv" ]]; then
      return 0
    fi
    if [[ "$av" -lt "$bv" ]]; then
      return 1
    fi
  done
  return 0
}

apm_read_version_file() {
  local file="$1"
  if [[ -f "$file" ]]; then
    tr -d '[:space:]' <"$file"
    return 0
  fi
  return 1
}

apm_install_dir_ready() {
  local dir="$1"
  [[ -d "$dir" ]] || return 1
  [[ -f "${dir}/start.sh" ]] || return 1
  [[ -f "${dir}/VERSION" ]] || return 1
  return 0
}

apm_sync_deploy_bundle() {
  local src="$1"
  local dest="$2"

  mkdir -p "${dest}/data/fe-meta" "${dest}/data/be-storage" "${dest}/scripts" "${dest}/sql"

  cp -f "${src}/docker-compose.yml" "${dest}/"
  if [[ -f "${src}/docker-compose.legacy.yml" ]]; then
    cp -f "${src}/docker-compose.legacy.yml" "${dest}/scripts/docker-compose.legacy.yml"
  elif [[ -f "${src}/scripts/docker-compose.legacy.yml" ]]; then
    cp -f "${src}/scripts/docker-compose.legacy.yml" "${dest}/scripts/docker-compose.legacy.yml"
  fi

  for script in start.sh stop.sh reset-table.sh update.sh; do
    if [[ -f "${src}/${script}" ]]; then
      cp -f "${src}/${script}" "${dest}/${script}"
      chmod +x "${dest}/${script}"
    fi
  done

  if [[ -d "${src}/scripts" ]]; then
    cp -Rf "${src}/scripts/." "${dest}/scripts/"
    chmod +x "${dest}/scripts/"*.sh 2>/dev/null || true
  fi

  if [[ -d "${src}/sql" ]]; then
    cp -Rf "${src}/sql/." "${dest}/sql/"
  fi

  if [[ -f "${src}/env.sh" ]]; then
    cp -f "${src}/env.sh" "${dest}/env.sh"
  fi
  if [[ -f "${src}/VERSION" ]]; then
    cp -f "${src}/VERSION" "${dest}/VERSION"
  fi
  if [[ -f "${src}/UPGRADE.md" ]]; then
    cp -f "${src}/UPGRADE.md" "${dest}/UPGRADE.md"
  fi
  if [[ -f "${src}/upgrade-manifest.json" ]]; then
    cp -f "${src}/upgrade-manifest.json" "${dest}/upgrade-manifest.json"
  fi
}

apm_read_upgrade_manifest_field() {
  local file="$1"
  local field="$2"
  if [[ ! -f "$file" ]]; then
    return 1
  fi
  python3 - "$file" "$field" <<'PY'
import json, sys
path, key = sys.argv[1], sys.argv[2]
with open(path, encoding="utf-8") as f:
    data = json.load(f)
val = data.get(key)
if val is None:
    sys.exit(1)
if isinstance(val, (dict, list)):
    print(json.dumps(val))
else:
    print(val)
PY
}

apm_upgrade_preflight() {
  local install_dir="$1"
  local current_ver="$2"
  local target_ver="$3"
  local manifest="${install_dir}/upgrade-manifest.json"
  local staging_manifest="${4:-}"
  local min_from upgrade_type

  if [[ -n "$staging_manifest" && -f "$staging_manifest" ]]; then
    manifest="$staging_manifest"
  fi

  if [[ "$current_ver" == "$target_ver" ]]; then
    echo "already on version ${current_ver}"
    return 0
  fi

  if ! apm_version_gte "$target_ver" "$current_ver"; then
    echo "target version ${target_ver} is older than current ${current_ver}"
    return 1
  fi

  if [[ -f "$manifest" ]]; then
    upgrade_type="$(apm_read_upgrade_manifest_field "$manifest" upgrade_type 2>/dev/null || echo A)"
    min_from="$(apm_read_upgrade_manifest_field "$manifest" min_upgrade_from 2>/dev/null || echo 0.0.0)"
    if [[ "$upgrade_type" == "C" ]]; then
      echo "version ${target_ver} requires fresh install (upgrade_type=C); use install.sh"
      return 1
    fi
    if ! apm_version_gte "$current_ver" "$min_from"; then
      echo "current ${current_ver} is below minimum upgrade base ${min_from} for ${target_ver}"
      return 1
    fi
  fi

  return 0
}

apm_backup_data_dir() {
  local install_dir="$1"
  local backup_dir="${2:-${install_dir}/backups}"
  local ts archive

  mkdir -p "$backup_dir"
  ts="$(date +%Y%m%d-%H%M%S)"
  archive="${backup_dir}/data-backup-${ts}.tar.gz"
  tar -czf "$archive" -C "$install_dir" data
  printf '%s\n' "$archive"
}

# Restore data/ from an apm_backup_data_dir archive (used by update.sh auto-retry).
apm_restore_data_dir() {
  local install_dir="$1"
  local archive="$2"

  [[ -f "$archive" ]] || return 1
  rm -rf "${install_dir}/data"
  tar -xzf "$archive" -C "$install_dir"
}

# Resolve a data-backup-*.tar.gz under install_dir/backups/ (or an explicit path).
apm_resolve_data_backup_archive() {
  local install_dir="$1"
  local hint="${2:-}"
  local backup_dir="${install_dir}/backups"
  local candidate latest

  if [[ -n "$hint" ]]; then
    if [[ -f "$hint" ]]; then
      printf '%s\n' "$(cd "$(dirname "$hint")" && pwd)/$(basename "$hint")"
      return 0
    fi
    if [[ -f "${backup_dir}/${hint}" ]]; then
      printf '%s\n' "${backup_dir}/${hint}"
      return 0
    fi
    if [[ -f "${install_dir}/${hint}" ]]; then
      printf '%s\n' "${install_dir}/${hint}"
      return 0
    fi
    return 1
  fi

  shopt -s nullglob
  local files=("${backup_dir}"/data-backup-*.tar.gz)
  shopt -u nullglob
  if [[ ${#files[@]} -eq 0 ]]; then
    return 1
  fi
  latest="${files[0]}"
  for candidate in "${files[@]}"; do
    if [[ "$candidate" -nt "$latest" ]]; then
      latest="$candidate"
    fi
  done
  printf '%s\n' "$latest"
}

# Refresh update.sh + scripts/ from target release bundle (online wrapper uses this before exec).
apm_bootstrap_update_runner() {
  local install_dir="$1"
  local version="$2"
  local tmp_pkg staging docker_pkg pkg_url

  [[ -d "$install_dir" ]] || return 1
  docker_pkg="databuff-ai-apm-${version}.tar.gz"
  pkg_url="$(apm_docker_pkg_download_url "$docker_pkg")"
  tmp_pkg="$(mktemp "${TMPDIR:-/tmp}/apm-update-bootstrap.XXXXXX.tar.gz")"
  staging="$(mktemp -d "${TMPDIR:-/tmp}/apm-update-bootstrap-stage.XXXXXX")"

  if ! curl -fsSL "$pkg_url" -o "$tmp_pkg"; then
    rm -f "$tmp_pkg"
    rm -rf "$staging"
    return 1
  fi
  tar -xzf "$tmp_pkg" -C "$staging" --strip-components=1
  rm -f "$tmp_pkg"

  mkdir -p "${install_dir}/scripts"
  if [[ -f "${staging}/update.sh" ]]; then
    cp -f "${staging}/update.sh" "${install_dir}/update.sh"
    chmod +x "${install_dir}/update.sh"
  fi
  if [[ -d "${staging}/scripts" ]]; then
    cp -Rf "${staging}/scripts/." "${install_dir}/scripts/"
    chmod +x "${install_dir}/scripts/"*.sh 2>/dev/null || true
  fi
  if [[ -d "${staging}/sql" ]]; then
    mkdir -p "${install_dir}/sql"
    cp -Rf "${staging}/sql/." "${install_dir}/sql/"
  fi
  if [[ -f "${staging}/upgrade-manifest.json" ]]; then
    cp -f "${staging}/upgrade-manifest.json" "${install_dir}/upgrade-manifest.json"
  fi

  rm -rf "$staging"
  [[ -x "${install_dir}/update.sh" ]]
}

apm_resolve_bundle_glob() {
  local pattern="$1"
  local label="$2"
  local matches=()
  shopt -s nullglob
  matches=(${pattern})
  shopt -u nullglob
  if [[ ${#matches[@]} -eq 0 ]]; then
    echo "[update] ERROR: 离线包缺少 ${label}（期望 ${pattern}）" >&2
    return 1
  fi
  if [[ ${#matches[@]} -gt 1 ]]; then
    echo "[update] ERROR: 离线包存在多个 ${label}" >&2
    return 1
  fi
  printf '%s\n' "${matches[0]}"
}

apm_docker_image_exists() {
  docker image inspect "$1" >/dev/null 2>&1
}

apm_docker_load_tarball() {
  local tarball="$1"
  local label="$2"
  echo "[update]   导入 ${label} ..."
  if [[ "$tarball" == *.gz ]]; then
    gunzip -c "$tarball" | docker load
  else
    docker load -i "$tarball"
  fi
}

# Load images from an extracted offline bundle directory (no network).
apm_load_offline_bundle_images() {
  local bundle_root="$1"
  local version="$2"
  local force="${3:-0}"
  local apm_tar doris_tar apm_stack doris_fe

  [[ -d "$bundle_root" ]] || {
    echo "[update] ERROR: 离线包目录不存在: ${bundle_root}" >&2
    return 1
  }

  apm_tar="$(apm_resolve_bundle_glob "${bundle_root}/ai-apm-stack-${version}-"'*.tar.gz' "APM 镜像包")" || return 1
  doris_tar="$(apm_resolve_bundle_glob "${bundle_root}/doris-stack-"'*.tar.gz' "Doris 镜像包")" || return 1

  if [[ -f "${bundle_root}/env.sh" ]]; then
    # shellcheck disable=SC1091
    . "${bundle_root}/env.sh"
  elif [[ -n "${APM_INSTALL_DIR:-}" && -f "${APM_INSTALL_DIR}/env.sh" ]]; then
    # shellcheck disable=SC1091
    . "${APM_INSTALL_DIR}/env.sh"
  fi

  apm_stack="${RUNTIME_IMAGE_NAMESPACE:-databuffhub}/ai-apm-ingest:${version}"
  doris_fe="${DORIS_FE_IMAGE:-apache/doris:fe-4.1.1}"

  if [[ "$force" == "1" ]] \
    || ! apm_docker_image_exists "$apm_stack" \
    || ! apm_docker_image_exists "$doris_fe"; then
    apm_docker_load_tarball "$apm_tar" "APM stack"
    apm_docker_load_tarball "$doris_tar" "Doris stack"
    return 0
  fi

  echo "[update]   离线镜像已存在，跳过 docker load（设 FORCE_LOAD_IMAGES=1 可强制重载）"
  return 0
}

# Return path to update.sh (install dir or bootstrap from offline bundle deploy pkg).
apm_resolve_update_executable() {
  local install_dir="$1"
  local bundle_root="$2"
  local version="$3"
  local deploy_pkg staging

  if [[ -x "${install_dir}/update.sh" ]]; then
    printf '%s\n' "${install_dir}/update.sh"
    return 0
  fi

  [[ -n "$bundle_root" ]] || return 1
  deploy_pkg="$(apm_resolve_bundle_glob "${bundle_root}/databuff-ai-apm-${version}.tar.gz" "部署包")" || return 1
  staging="$(mktemp -d "${TMPDIR:-/tmp}/apm-update-bootstrap.XXXXXX")"
  tar -xzf "$deploy_pkg" -C "$staging" --strip-components=1
  chmod +x "${staging}/update.sh" "${staging}/scripts/"*.sh 2>/dev/null || true
  printf '%s\n' "${staging}/update.sh"
}
