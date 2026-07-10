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
  export JDK_IMAGE="${JDK_IMAGE:-eclipse-temurin:17-jdk-jammy}"
  export LOCAL_WEB_IMAGE="${LOCAL_WEB_IMAGE:-databuff-local/web-dev:17-jdk-jammy}"
}

local_image_arch() {
  docker image inspect "$1" --format '{{.Architecture}}' 2>/dev/null || true
}

ensure_jdk_image() {
  local pull_ref registry_host host_arch image_arch

  pull_ref="$(jdk_pull_image)"
  host_arch="$(detect_image_arch)"

  if docker image inspect "${JDK_IMAGE}" >/dev/null 2>&1; then
    image_arch="$(local_image_arch "${JDK_IMAGE}")"
    if [ "$image_arch" = "$host_arch" ]; then
      echo "[local] using local JDK image ${JDK_IMAGE} (linux/${host_arch})"
      return 0
    fi
    echo "[local] ${JDK_IMAGE} is linux/${image_arch}, need linux/${host_arch}; re-pulling"
  elif [[ "$pull_ref" != "${JDK_IMAGE}" ]] && docker image inspect "$pull_ref" >/dev/null 2>&1; then
    image_arch="$(local_image_arch "$pull_ref")"
    if [ "$image_arch" = "$host_arch" ]; then
      echo "[local] using local ${pull_ref}, tagging as ${JDK_IMAGE}"
      docker tag "$pull_ref" "${JDK_IMAGE}"
      return 0
    fi
  fi

  echo "[local] pull JDK base image ${pull_ref} (linux/${host_arch})"
  if ! docker pull --platform "linux/${host_arch}" "$pull_ref"; then
    if [[ -n "${JDK_REGISTRY:-}" ]]; then
      registry_host="${JDK_REGISTRY%%/*}"
      echo "[local] pull failed; try: docker login ${registry_host}" >&2
    else
      echo "[local] pull failed; check network or docker login" >&2
    fi
    exit 1
  fi
  if [[ "$pull_ref" != "${JDK_IMAGE}" ]]; then
    docker tag "$pull_ref" "${JDK_IMAGE}"
  fi
}

ensure_local_web_image() {
  if docker image inspect "${LOCAL_WEB_IMAGE}" >/dev/null 2>&1; then
    echo "[local] using local web dev image ${LOCAL_WEB_IMAGE}"
    return 0
  fi
  echo "[local] building web dev image ${LOCAL_WEB_IMAGE} (FROM ${JDK_IMAGE})"
  docker build -t "${LOCAL_WEB_IMAGE}" \
    --build-arg "JDK_IMAGE=${JDK_IMAGE}" \
    -f "${LOCAL_ROOT}/Dockerfile.web-dev" \
    "${LOCAL_ROOT}"
}
