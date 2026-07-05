#!/usr/bin/env bash
# Shared helpers for Demo 造数安装 / 升级（在线与离线）。

demo_install_dir_ready() {
  local dir="$1"
  [[ -d "$dir" ]] || return 1
  [[ -f "${dir}/start.sh" ]] || return 1
  [[ -f "${dir}/stop.sh" ]] || return 1
  return 0
}

demo_read_installed_version() {
  local dir="$1"
  if [[ -f "${dir}/VERSION" ]]; then
    tr -d '[:space:]' <"${dir}/VERSION"
    return 0
  fi
  return 1
}

demo_detect_host_ip() {
  local ip=""
  if command -v ip >/dev/null 2>&1; then
    ip="$(ip route get 1.1.1.1 2>/dev/null | awk '{for (i=1;i<=NF;i++) if ($i=="src") {print $(i+1); exit}}')"
  fi
  if [[ -z "$ip" ]] && command -v hostname >/dev/null 2>&1; then
    ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
  fi
  if [[ -z "$ip" ]]; then
    return 1
  fi
  printf '%s\n' "$ip"
}

demo_compose_down() {
  local install_dir="$1"
  local project

  [[ -f "${install_dir}/docker-compose.yml" ]] || return 0

  for project in databuff-ai-apm-demo databuff-apm-demo; do
    if docker compose version >/dev/null 2>&1; then
      (cd "$install_dir" && COMPOSE_PROJECT_NAME="$project" docker compose down --remove-orphans) >/dev/null 2>&1 || true
    elif command -v docker-compose >/dev/null 2>&1; then
      (cd "$install_dir" && COMPOSE_PROJECT_NAME="$project" docker-compose down --remove-orphans) >/dev/null 2>&1 || true
    fi
  done
}

demo_stop_running() {
  local install_dir="$1"

  if [[ -x "${install_dir}/stop.sh" ]]; then
    (cd "$install_dir" && ./stop.sh) >/dev/null 2>&1 || true
    return 0
  fi

  demo_compose_down "$install_dir"
}

demo_remove_install_dir() {
  local install_dir="$1"
  demo_stop_running "$install_dir"
  rm -rf "$install_dir"
}

demo_sync_deploy_bundle() {
  local src="$1"
  local dest="$2"

  mkdir -p "$dest/scripts"
  for item in docker-compose.yml start.sh stop.sh env.sh VERSION .env; do
    if [[ -f "${src}/${item}" ]]; then
      cp -f "${src}/${item}" "${dest}/${item}"
    fi
  done
  if [[ -f "${src}/update.sh" ]]; then
    cp -f "${src}/update.sh" "${dest}/update.sh"
    chmod +x "${dest}/update.sh"
  fi
  if [[ -d "${src}/scripts" ]]; then
    cp -Rf "${src}/scripts/." "${dest}/scripts/"
    chmod +x "${dest}/scripts/"*.sh 2>/dev/null || true
  fi
  chmod +x "${dest}/start.sh" "${dest}/stop.sh" 2>/dev/null || true
}

demo_resolve_bundle_glob() {
  local pattern="$1"
  local label="$2"
  local matches=()

  shopt -s nullglob
  matches=(${pattern})
  shopt -u nullglob

  if [[ ${#matches[@]} -eq 0 ]]; then
    echo "[demo] ERROR: 缺少 ${label}（期望 ${pattern}）" >&2
    return 1
  fi
  if [[ ${#matches[@]} -gt 1 ]]; then
    echo "[demo] ERROR: 存在多个 ${label}" >&2
    return 1
  fi
  printf '%s\n' "${matches[0]}"
}

demo_docker_image_exists() {
  docker image inspect "$1" >/dev/null 2>&1
}

demo_load_image_tarball() {
  local tarball="$1"
  local label="$2"

  echo "[demo]   导入 ${label} ..."
  if [[ "$tarball" == *.gz ]]; then
    gunzip -c "$tarball" | docker load
  else
    docker load -i "$tarball"
  fi
}

demo_load_offline_image() {
  local bundle_root="$1"
  local version="$2"
  local force="${3:-0}"
  local apm_tar demo_ref

  demo_ref="${RUNTIME_IMAGE_NAMESPACE:-databuffhub}/ai-apm-demo:${version}"
  apm_tar="$(demo_resolve_bundle_glob "${bundle_root}/ai-apm-stack-${version}-"'*.tar.gz' "APM 镜像包")" || return 1

  if [[ "$force" != "1" ]] && demo_docker_image_exists "$demo_ref"; then
    echo "[demo]   demo 镜像已存在，跳过 docker load（设 FORCE_LOAD_IMAGES=1 可强制重载）"
    return 0
  fi

  demo_load_image_tarball "$apm_tar" "APM stack (含 demo)"
}
