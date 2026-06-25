#!/usr/bin/env bash
# Shared helpers for deploy/local (JAR bind-mount dev stack).

_LOCAL_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export LOCAL_ROOT="$(cd "${_LOCAL_LIB_DIR}/.." && pwd)"
export LOCAL_RUN="${LOCAL_ROOT}/run"
export LOCAL_DOCKER_ROOT="${LOCAL_ROOT}/../docker"

# shellcheck disable=SC1091
source "${LOCAL_ROOT}/../images/scripts/lib.sh"

ensure_command() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "[local] required command not found: ${cmd}" >&2
    exit 1
  fi
}

ensure_vm_max_map_count() {
  local required=2000000
  local current
  current="$(sysctl -n vm.max_map_count 2>/dev/null || echo 0)"
  if [ "$current" -lt "$required" ]; then
    echo "[local] raising vm.max_map_count ${current} -> ${required}"
    sysctl -w "vm.max_map_count=${required}" >/dev/null 2>&1 || true
  fi
}

doris_has_data() {
  local fe="${LOCAL_ROOT}/data/fe-meta"
  local be="${LOCAL_ROOT}/data/be-storage"
  if [ -d "$fe" ] && [ -n "$(ls -A "$fe" 2>/dev/null)" ]; then
    return 0
  fi
  if [ -d "$be" ] && [ -n "$(ls -A "$be" 2>/dev/null)" ]; then
    return 0
  fi
  return 1
}

sync_ingest_run_dir() {
  local dest="${LOCAL_RUN}/ingest"
  local jar
  jar="$(ingest_jar_path)"
  mkdir -p "$dest"
  rm -f "${dest}"/*.jar
  cp -f "${APM_DOCKER_IMAGE_SRC}/ingest/start.sh" "${APM_DOCKER_IMAGE_SRC}/ingest/application.yml" "$dest/"
  cp -f "$jar" "$dest/"
  chmod +x "${dest}/start.sh"
}

sync_web_run_dir() {
  local dest="${LOCAL_RUN}/web"
  local jar
  jar="$(web_jar_path)"
  mkdir -p "$dest/data/skills" "$dest/data/ai-workspaces"
  rm -f "${dest}"/*.jar
  cp -f "${APM_DOCKER_IMAGE_SRC}/web/start.sh" "${APM_DOCKER_IMAGE_SRC}/web/application.yml" "$dest/"
  cp -f "$jar" "$dest/"
  chmod +x "${dest}/start.sh"
}

sync_demo_run_dir() {
  local dest="${LOCAL_RUN}/demo"
  local jar
  jar="$(demo_jar_path)"
  mkdir -p "$dest"
  rm -f "${dest}"/*.jar
  cp -f "${APM_DOCKER_IMAGE_SRC}/demo/start.sh" "$dest/"
  cp -f "$jar" "${dest}/demo-seeder.jar"
  chmod +x "${dest}/start.sh"
}

build_ingest_web() {
  ensure_command mvn
  mvn_package_modules
}

build_demo() {
  ensure_command mvn
  mvn_package_demo
}

verify_ingest_web_jars() {
  local f
  for f in "$(ingest_jar_path)" "$(web_jar_path)"; do
    if [ ! -f "$f" ]; then
      echo "[local] missing artifact: $f" >&2
      exit 1
    fi
  done
}

verify_demo_jar() {
  local jar
  jar="$(demo_jar_path)"
  if [ ! -f "$jar" ]; then
    echo "[local] missing artifact: $jar" >&2
    exit 1
  fi
}

sync_all_run_dirs() {
  sync_ingest_run_dir
  sync_web_run_dir
  sync_demo_run_dir
}

load_local_env() {
  if [ -f "${LOCAL_ROOT}/../env.sh" ]; then
    set -a
    # shellcheck disable=SC1091
    . "${LOCAL_ROOT}/../env.sh"
    set +a
  fi
}

ensure_openjdk_image() {
  local pull_ref registry_host

  pull_ref="$(openjdk_pull_image)"
  if docker image inspect "${OPENJDK_IMAGE}" >/dev/null 2>&1; then
    echo "[local] using local ${OPENJDK_IMAGE}"
    return 0
  fi
  if [[ "$pull_ref" != "${OPENJDK_IMAGE}" ]] && docker image inspect "$pull_ref" >/dev/null 2>&1; then
    echo "[local] using local ${pull_ref}, tagging as ${OPENJDK_IMAGE}"
    docker tag "$pull_ref" "${OPENJDK_IMAGE}"
    return 0
  fi

  echo "[local] pull ${pull_ref}"
  if ! docker pull "$pull_ref"; then
    if [[ -n "${OPENJDK_REGISTRY:-}" ]]; then
      registry_host="${OPENJDK_REGISTRY%%/*}"
      echo "[local] pull failed; try: docker login ${registry_host}" >&2
    else
      echo "[local] pull failed; check network or docker login" >&2
    fi
    exit 1
  fi
  if [[ "$pull_ref" != "${OPENJDK_IMAGE}" ]]; then
    docker tag "$pull_ref" "${OPENJDK_IMAGE}"
  fi
}
