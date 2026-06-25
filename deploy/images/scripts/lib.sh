#!/usr/bin/env bash
# Shared helpers for deploy/images, deploy/docker, deploy/k8s build scripts.

_BUILD_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
source "${_BUILD_LIB_DIR}/../../env.sh"

export APM_IMAGES_ROOT="$(cd "${_BUILD_LIB_DIR}/.." && pwd)"
export APM_REPO_ROOT="$(cd "${APM_IMAGES_ROOT}/../.." && pwd)"
export APM_DEPLOY_ROOT="${APM_REPO_ROOT}/deploy"
export APM_DOCKER_SRC="${APM_DEPLOY_ROOT}/docker"
export APM_K8S_SRC="${APM_DEPLOY_ROOT}/k8s"
export APM_DOCKER_IMAGE_SRC="${APM_IMAGES_ROOT}"
export APM_COMMON_SRC="${APM_DEPLOY_ROOT}/common"
export APM_DEMO_SRC="${APM_REPO_ROOT}/ai-apm-demo"
export APM_DOCKER_DEMO_SRC="${APM_DOCKER_SRC}/demo"
export APM_K8S_DEMO_SRC="${APM_K8S_SRC}/demo"

project_version() {
  awk '
    /<revision>/ {
      line=$0
      sub(/.*<revision>/, "", line)
      sub(/<\/revision>.*/, "", line)
      gsub(/[[:space:]]/, "", line)
      if (line != "") {
        print line
        exit
      }
    }
  ' "${APM_REPO_ROOT}/pom.xml"
}

resolve_version() {
  if [[ -n "${VERSION:-}" ]]; then
    printf '%s\n' "$VERSION"
    return 0
  fi
  if [[ -n "${APM_VERSION:-}" ]]; then
    printf '%s\n' "$APM_VERSION"
    return 0
  fi
  project_version
}

# Strip Maven -SNAPSHOT for release package / directory names.
normalize_release_version() {
  local ver="$1"
  ver="${ver%-SNAPSHOT}"
  printf '%s\n' "$ver"
}

resolve_release_version() {
  normalize_release_version "$(resolve_version)"
}

project_doris_version() {
  if [[ -n "${DORIS_VERSION:-}" ]]; then
    printf '%s\n' "$DORIS_VERSION"
    return 0
  fi
  awk '
    /<doris\.version>/ {
      line=$0
      sub(/.*<doris\.version>/, "", line)
      sub(/<\/doris\.version>.*/, "", line)
      gsub(/[[:space:]]/, "", line)
      if (line != "") {
        print line
        exit
      }
    }
  ' "${APM_REPO_ROOT}/pom.xml"
}

demo_jar_path() {
  printf '%s\n' "${APM_DEMO_SRC}/target/demo-seeder.jar"
}

runtime_image_namespace() {
  printf '%s\n' "${RUNTIME_IMAGE_NAMESPACE:-databuffhub}"
}

runtime_image_ref() {
  local component="$1"
  local tag="${2:-$(resolve_release_version)}"
  printf '%s/%s:%s\n' "$(runtime_image_namespace)" "$component" "$tag"
}

ingest_image_ref() {
  runtime_image_ref ai-apm-ingest "${1:-$(resolve_release_version)}"
}

web_image_ref() {
  runtime_image_ref ai-apm-web "${1:-$(resolve_release_version)}"
}

demo_image_ref() {
  runtime_image_ref ai-apm-demo "${1:-$(resolve_release_version)}"
}

openjdk_pull_image() {
  if [[ -n "${OPENJDK_REGISTRY:-}" ]]; then
    printf '%s/openjdk:17.0.2-jdk\n' "${OPENJDK_REGISTRY%/}"
    return 0
  fi
  printf '%s\n' "${OPENJDK_IMAGE:?set OPENJDK_IMAGE in deploy/env.sh}"
}

openjdk_dockerfile_from() {
  openjdk_pull_image
}

openjdk_image_for_platform() {
  local platform="$1"

  if [[ -n "${OPENJDK_REGISTRY:-}" ]]; then
    printf '%s/openjdk:17.0.2-jdk\n' "${OPENJDK_REGISTRY%/}"
    return 0
  fi
  printf '%s\n' "${OPENJDK_IMAGE:?set OPENJDK_IMAGE in deploy/env.sh}"
}

prepare_demo_build_context() {
  local jar_path="$1"
  local ctx
  ctx="$(mktemp -d "${TMPDIR:-/tmp}/apm-image-demo.XXXXXX")"

  cp -f "${APM_DOCKER_IMAGE_SRC}/demo/Dockerfile" "${ctx}/"
  rm -f "${ctx}/Dockerfile.bak"
  cp -f "${APM_DOCKER_IMAGE_SRC}/demo/start.sh" "${ctx}/start.sh"
  cp -f "$jar_path" "${ctx}/demo-seeder.jar"

  printf '%s\n' "$ctx"
}

publish_demo_image() {
  local demo_version="$1"
  local demo_jar="$2"

  build_and_export_service_tarballs demo ai-apm-demo "$demo_jar" "$demo_version"
}

write_demo_compose_env() {
  local env_file="$1"
  local demo_version="$2"

  cat >"$env_file" <<EOF
APM_VERSION=${demo_version}
APM_DEMO_IMAGE=$(runtime_image_ref ai-apm-demo "$demo_version")
EOF
}

render_demo_k8s_manifest() {
  local src="$1"
  local dest="$2"
  local demo_version="$3"
  local registry="$4"
  local otlp_endpoint="${5:-http://ai-apm-ingest:4318}"

  python3 - "$src" "$dest" "$demo_version" "$registry" "$otlp_endpoint" <<'PY'
import sys

src, dest, version, registry, otlp_endpoint = sys.argv[1:6]
text = open(src, encoding="utf-8").read()
image = f"{registry}/ai-apm-demo:{version}"
text = text.replace("__DEMO_IMAGE__", image)
text = text.replace(
    "http://ai-apm-ingest:4318",
    otlp_endpoint,
)
open(dest, "w", encoding="utf-8").write(text)
PY
}

ingest_jar_path() {
  local ver
  ver="$(project_version)"
  printf '%s\n' "${APM_REPO_ROOT}/ai-apm-ingest/target/ai-apm-ingest-${ver}.jar"
}

web_jar_path() {
  local ver
  ver="$(project_version)"
  printf '%s\n' "${APM_REPO_ROOT}/ai-apm-web/target/ai-apm-web-${ver}.jar"
}

pkg_base_url() {
  printf '%s\n' "${APM_PKG_BASE:?set APM_PKG_BASE in deploy/env.sh}"
}

version_pkg_base_url() {
  local version="${1:-$(resolve_release_version)}"
  printf '%s\n' "$(pkg_base_url)/${version}"
}

version_images_pkg_base_url() {
  local version="${1:-$(resolve_release_version)}"
  if [[ -n "${APM_IMAGES_PKG_BASE:-}" ]]; then
    printf '%s\n' "${APM_IMAGES_PKG_BASE}"
    return 0
  fi
  printf '%s\n' "$(version_pkg_base_url "$version")/images"
}

infra_images_pkg_base_url() {
  if [[ -n "${APM_INFRA_IMAGES_PKG_BASE:-}" ]]; then
    printf '%s\n' "${APM_INFRA_IMAGES_PKG_BASE}"
    return 0
  fi
  printf '%s\n' "$(pkg_base_url)/infra/images"
}

version_docker_pkg_base_url() {
  local version="${1:-$(resolve_release_version)}"
  printf '%s\n' "$(version_pkg_base_url "$version")/docker"
}

version_k8s_pkg_base_url() {
  local version="${1:-$(resolve_release_version)}"
  printf '%s\n' "$(version_pkg_base_url "$version")/k8s"
}

# 兼容旧名：指向当前版本的 images 目录
images_pkg_base_url() {
  version_images_pkg_base_url "${1:-$(resolve_release_version)}"
}

pkg_upload_host() {
  printf '%s\n' "${APM_PKG_UPLOAD_HOST:?set APM_PKG_UPLOAD_HOST in deploy/env.sh}"
}

pkg_upload_user() {
  printf '%s\n' "${APM_PKG_UPLOAD_USER:?set APM_PKG_UPLOAD_USER in deploy/env.sh}"
}

pkg_upload_pass() {
  printf '%s\n' "${APM_PKG_UPLOAD_PASS:?set APM_PKG_UPLOAD_PASS in deploy/env.sh}"
}

pkg_remote_dir() {
  printf '%s\n' "${APM_PKG_REMOTE_DIR:?set APM_PKG_REMOTE_DIR in deploy/env.sh}"
}

version_images_remote_dir() {
  local version="${1:-$(resolve_release_version)}"
  printf '%s\n' "${APM_PKG_IMAGES_REMOTE_DIR:-$(pkg_remote_dir)/${version}/images}"
}

infra_images_remote_dir() {
  printf '%s\n' "$(pkg_remote_dir)/infra/images"
}

version_docker_pkg_remote_dir() {
  local version="${1:-$(resolve_release_version)}"
  printf '%s\n' "$(pkg_remote_dir)/${version}/docker"
}

version_k8s_pkg_remote_dir() {
  local version="${1:-$(resolve_release_version)}"
  printf '%s\n' "$(pkg_remote_dir)/${version}/k8s"
}

images_remote_dir() {
  version_images_remote_dir "${1:-$(resolve_release_version)}"
}

_APM_PKG_SSH_CTRL="${TMPDIR:-/tmp}/apm-pkg-ssh-${USER:-unknown}-$$"
_APM_PKG_SSH_SOCK="${_APM_PKG_SSH_CTRL}/master"
mkdir -p "$_APM_PKG_SSH_CTRL" 2>/dev/null || true

declare -a _APM_PKG_SSH_OPTS=(
  -o StrictHostKeyChecking=no
  -o UserKnownHostsFile=/dev/null
  -o ControlMaster=auto
  -o ControlPersist=120
  -o ControlPath="${_APM_PKG_SSH_SOCK}"
)

pkg_upload_ssh() {
  sshpass -p "$(pkg_upload_pass)" ssh \
    "${_APM_PKG_SSH_OPTS[@]}" \
    "$(pkg_upload_user)@$(pkg_upload_host)" "$@"
}

pkg_upload_scp() {
  sshpass -p "$(pkg_upload_pass)" scp \
    "${_APM_PKG_SSH_OPTS[@]}" \
    "$@"
}

pkg_url_for_remote_dir() {
  local remote_dir="$1"
  local base rel
  base="$(pkg_base_url)"
  base="${base%/}"
  rel="${remote_dir#$(pkg_remote_dir)}"
  rel="${rel#/}"
  if [[ -n "$rel" ]]; then
    printf '%s/%s\n' "$base" "$rel"
  else
    printf '%s\n' "$base"
  fi
}

fix_remote_static_permissions() {
  local remote_dir="$1"
  shift
  local files=("$@")
  local remote_chmod file remote_name dir pkg_root

  pkg_root="$(pkg_remote_dir)"
  remote_chmod=""
  dir="$remote_dir"
  while [[ "$dir" == "${pkg_root}"* ]]; do
    remote_chmod+="chmod 755 '${dir}' && "
    if [[ "$dir" == "$pkg_root" ]]; then
      break
    fi
    dir="$(dirname "$dir")"
  done
  for file in "${files[@]}"; do
    remote_name="$(basename "$file")"
    remote_chmod+="chmod 644 '${remote_dir}/${remote_name}' && "
  done
  remote_chmod="${remote_chmod% && }"

  pkg_upload_ssh "$remote_chmod"
}

upload_files_to_remote() {
  local remote_dir="$1"
  shift
  local files=("$@")
  local file remote_name scp_dest

  if [[ ${#files[@]} -eq 0 ]]; then
    return 0
  fi

  ensure_command sshpass

  for file in "${files[@]}"; do
    if [[ ! -f "$file" ]]; then
      echo "[build] missing file for upload: ${file}" >&2
      exit 1
    fi
  done

  pkg_upload_ssh "mkdir -p '${remote_dir}'"

  scp_dest="$(pkg_upload_user)@$(pkg_upload_host):${remote_dir}/"
  for file in "${files[@]}"; do
    remote_name="$(basename "$file")"
    echo "[build] upload ${remote_name} -> $(pkg_upload_user)@$(pkg_upload_host):${remote_dir}/${remote_name}"
  done
  echo "[build] scp batch (${#files[@]} file(s)) -> ${remote_dir}/"
  pkg_upload_scp "${files[@]}" "$scp_dest"

  # nginx 以非 root 用户运行：目录须 755（可遍历），文件须 644（可读），否则 HTTP 403
  fix_remote_static_permissions "$remote_dir" "${files[@]}"
}

image_tarball_name() {
  local component="$1"
  local version="$2"
  local arch="$3"
  local ext="${4:-${IMAGE_TARBALL_EXT:-.tar.gz}}"
  printf '%s-%s-%s%s\n' "$component" "$version" "$arch" "$ext"
}

compress_image_tarball() {
  local raw="$1"
  local gz="${raw}.gz"
  gzip -1 -c "$raw" >"$gz"
  rm -f "$raw"
  printf '%s\n' "$gz"
}

doris_image_version() {
  local image="${1:-${DORIS_FE_IMAGE}}"
  local tag="${image##*:}"
  tag="${tag#fe-}"
  tag="${tag#be-}"
  printf '%s\n' "$tag"
}

zookeeper_image_version() {
  local image="${1:-${ZOOKEEPER_IMAGE}}"
  printf '%s\n' "${image##*:}"
}

publish_files_to_remote() {
  if [[ "${SKIP_PKG_UPLOAD:-${SKIP_FTP_UPLOAD:-0}}" == "1" ]]; then
    echo "[build] SKIP_PKG_UPLOAD=1 — skip upload"
    return 0
  fi
  if [[ "$#" -lt 2 ]]; then
    return 0
  fi

  local remote_dir="$1"
  shift
  local files=("$@")
  local base url file remote_name

  base="$(pkg_url_for_remote_dir "$remote_dir")"
  base="${base%/}"

  echo "[build] upload ${#files[@]} file(s) -> $(pkg_upload_host):${remote_dir}/"
  for file in "${files[@]}"; do
    if [[ ! -f "$file" ]]; then
      echo "[build] missing file for upload: ${file}" >&2
      exit 1
    fi
    remote_name="$(basename "$file")"
    url="${base}/${remote_name}"
    echo "[build]   ${remote_name}  (HTTP after upload: ${url})"
  done

  upload_files_to_remote "$remote_dir" "${files[@]}"
}

write_image_tarball_size_sidecar() {
  local tarball="$1"
  { stat -c '%s' "$tarball" 2>/dev/null || stat -f '%z' "$tarball"; } >"${tarball}.size"
}

local_images_pkg_dir() {
  local version="${1:-$(resolve_release_version)}"
  printf '%s\n' "${APM_BUILD_DIST:-${APM_IMAGES_ROOT}/dist}/${version}/images"
}

publish_image_pkg() {
  if [[ "${SKIP_IMAGE_PKG_UPLOAD:-${SKIP_PKG_UPLOAD:-${SKIP_FTP_UPLOAD:-0}}}" == "1" ]]; then
    echo "[build] SKIP_IMAGE_PKG_UPLOAD=1 — skip image tarball upload"
    return 0
  fi
  if [[ "$#" -lt 2 ]]; then
    return 0
  fi

  local version="$1"
  shift
  local tarballs=("$@") tarball upload_files=()
  for tarball in "${tarballs[@]}"; do
    write_image_tarball_size_sidecar "$tarball"
    upload_files+=("$tarball" "${tarball}.size")
  done
  publish_files_to_remote "$(version_images_remote_dir "$version")" "${upload_files[@]}"
}

publish_infra_image_pkg() {
  if [[ "${SKIP_IMAGE_PKG_UPLOAD:-${SKIP_PKG_UPLOAD:-${SKIP_FTP_UPLOAD:-0}}}" == "1" ]]; then
    echo "[build] SKIP_IMAGE_PKG_UPLOAD=1 — skip image tarball upload"
    return 0
  fi
  if [[ "$#" -lt 1 ]]; then
    return 0
  fi

  local tarballs=("$@") tarball upload_files=()
  for tarball in "${tarballs[@]}"; do
    write_image_tarball_size_sidecar "$tarball"
    upload_files+=("$tarball" "${tarball}.size")
  done
  publish_files_to_remote "$(infra_images_remote_dir)" "${upload_files[@]}"
}

pull_and_save_public_image_tarball() {
  local image_ref="$1"
  local arch="$2"
  local dest="$3"

  echo "[build] pull ${image_ref} (linux/${arch}) ..."
  docker pull --platform "linux/${arch}" "$image_ref"
  docker save -o "$dest" "$image_ref"
}

pull_public_image() {
  local image_ref="$1"
  local arch="$2"

  echo "[build] pull ${image_ref} (linux/${arch}) ..."
  docker pull --platform "linux/${arch}" "$image_ref"
}

# 已 pull 的镜像直接 save | gzip，避免 fe.tar + be.tar + load + save 的峰值磁盘占用
save_pulled_images_gz() {
  local dest_gz="$1"
  shift
  local refs=("$@")

  echo "[build] export $(basename "$dest_gz") ..."
  docker save "${refs[@]}" | gzip -1 >"$dest_gz"
  write_image_tarball_size_sidecar "$dest_gz"
}

rmi_public_images_quiet() {
  local ref
  for ref in "$@"; do
    docker rmi "$ref" >/dev/null 2>&1 || true
  done
}

build_and_export_service_tarballs() {
  local svc="$1"
  local component="$2"
  local jar_path="$3"
  local release_version="$4"
  local image_ref ctx arch tarball dist_dir tarballs=()

  image_ref="$(runtime_image_ref "$component" "$release_version")"
  if [[ "$svc" == "demo" ]]; then
    ctx="$(prepare_demo_build_context "$jar_path")"
  else
    ctx="$(prepare_image_build_context "$svc" "$jar_path")"
  fi

  dist_dir="$(mktemp -d "${TMPDIR:-/tmp}/apm-image-export.XXXXXX")"
  ensure_buildx

  for arch in $(image_arch_list); do
    echo "[build] buildx ${image_ref} (linux/${arch}) ..."
    tarball="${dist_dir}/$(image_tarball_name "$component" "$release_version" "$arch" .tar)"
    buildx_export_image_tarball "$image_ref" "$ctx" "linux/${arch}" "$tarball"
    verify_image_tarball_arch "$tarball" "$arch"
    tarballs+=("$(compress_image_tarball "$tarball")")
  done

  rm -rf "$ctx"
  publish_image_pkg "$release_version" "${tarballs[@]}"
  rm -rf "$dist_dir"
}

# ingest + web + demo 合并导出（Docker / K8s 共用同一离线包）
export_apm_stack_tarballs() {
  local release_version="$1"
  local ingest_jar="$2"
  local web_jar="$3"
  local demo_jar="$4"
  local ingest_ref web_ref demo_ref
  local ingest_ctx web_ctx demo_ctx
  local arch dist_dir dist_cleanup=0 tmp_load raw_tar gz_tar tarballs=()

  ingest_ref="$(ingest_image_ref "$release_version")"
  web_ref="$(web_image_ref "$release_version")"
  demo_ref="$(demo_image_ref "$release_version")"
  ingest_ctx="$(prepare_image_build_context ingest "$ingest_jar")"
  web_ctx="$(prepare_image_build_context web "$web_jar")"
  demo_ctx="$(prepare_demo_build_context "$demo_jar")"

  if [[ "${SKIP_LOCAL_IMAGE_PKG_KEEP:-0}" == "1" ]]; then
    dist_dir="$(mktemp -d "${TMPDIR:-/tmp}/apm-stack-export.XXXXXX")"
    dist_cleanup=1
  else
    dist_dir="$(local_images_pkg_dir "$release_version")"
    mkdir -p "$dist_dir"
  fi
  tmp_load="$(mktemp -d "${TMPDIR:-/tmp}/apm-stack-load.XXXXXX")"
  ensure_buildx

  for arch in $(image_arch_list); do
    echo "[build] buildx ai-apm-stack (linux/${arch}): ingest + web + demo ..."
    buildx_export_image_tarball "$ingest_ref" "$ingest_ctx" "linux/${arch}" "${tmp_load}/ingest.tar"
    buildx_export_image_tarball "$web_ref" "$web_ctx" "linux/${arch}" "${tmp_load}/web.tar"
    buildx_export_image_tarball "$demo_ref" "$demo_ctx" "linux/${arch}" "${tmp_load}/demo.tar"

    docker load -i "${tmp_load}/ingest.tar"
    docker load -i "${tmp_load}/web.tar"
    docker load -i "${tmp_load}/demo.tar"
    rm -f "${tmp_load}/"*.tar

    raw_tar="${dist_dir}/$(image_tarball_name ai-apm-stack "$release_version" "$arch" .tar)"
    docker save -o "$raw_tar" "$ingest_ref" "$web_ref" "$demo_ref"
    verify_image_tarball_arch "$raw_tar" "$arch"
    gz_tar="$(compress_image_tarball "$raw_tar")"
    tarballs+=("$gz_tar")
    echo "[build]   -> ${gz_tar}"
    rmi_public_images_quiet "$ingest_ref" "$web_ref" "$demo_ref"
  done

  rm -rf "$ingest_ctx" "$web_ctx" "$demo_ctx" "$tmp_load"
  publish_image_pkg "$release_version" "${tarballs[@]}"
  if [[ "$dist_cleanup" == "1" ]]; then
    rm -rf "$dist_dir"
  fi
}

export_infra_image_tarballs() {
  local doris_version zk_version arch dist_dir gz_tar tarballs=()

  doris_version="$(doris_image_version)"
  zk_version="$(zookeeper_image_version)"
  dist_dir="$(mktemp -d "${TMPDIR:-/tmp}/apm-infra-image-tarballs.XXXXXX")"

  for arch in $(image_arch_list); do
    echo "[build] doris-stack (linux/${arch}): pull fe + be, save | gzip ..."
    pull_public_image "${DORIS_FE_IMAGE}" "$arch"
    pull_public_image "${DORIS_BE_IMAGE}" "$arch"
    gz_tar="${dist_dir}/$(image_tarball_name doris-stack "$doris_version" "$arch")"
    save_pulled_images_gz "$gz_tar" "${DORIS_FE_IMAGE}" "${DORIS_BE_IMAGE}"
    tarballs+=("$gz_tar")
    rmi_public_images_quiet "${DORIS_FE_IMAGE}" "${DORIS_BE_IMAGE}"

    echo "[build] zookeeper (linux/${arch}): pull, save | gzip ..."
    pull_public_image "${ZOOKEEPER_IMAGE}" "$arch"
    gz_tar="${dist_dir}/$(image_tarball_name zookeeper "$zk_version" "$arch")"
    save_pulled_images_gz "$gz_tar" "${ZOOKEEPER_IMAGE}"
    tarballs+=("$gz_tar")
    rmi_public_images_quiet "${ZOOKEEPER_IMAGE}"
  done

  publish_infra_image_pkg "${tarballs[@]}"
  rm -rf "$dist_dir"
}

publish_pkg() {
  publish_root_pkg "$@"
}

publish_root_pkg() {
  publish_files_to_remote "$(pkg_remote_dir)" "$@"
}

publish_version_docker_pkg() {
  local version="$1"
  shift
  publish_files_to_remote "$(version_docker_pkg_remote_dir "$version")" "$@"
}

publish_version_k8s_pkg() {
  local version="$1"
  shift
  publish_files_to_remote "$(version_k8s_pkg_remote_dir "$version")" "$@"
}

publish_version_manifest() {
  local version="${1:-$(resolve_release_version)}"
  local tmp_dir manifest resolve_lib dist_resolve

  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/apm-version-manifest.XXXXXX")"
  printf '%s\n' "$version" >"${tmp_dir}/VERSION"
  publish_pkg "${tmp_dir}/VERSION"
  rm -rf "$tmp_dir"
  echo "[build]   latest version: ${version} (${pkg_base_url%/}/VERSION)"

  resolve_lib="${APM_COMMON_SRC}/scripts/resolve-install-version.sh"
  if [[ ! -f "$resolve_lib" ]]; then
    echo "[build] missing resolve-install-version.sh: ${resolve_lib}" >&2
    exit 1
  fi
  dist_resolve="${APM_BUILD_DIST:-${TMPDIR:-/tmp}}/resolve-install-version.sh"
  mkdir -p "$(dirname "$dist_resolve")"
  cp -f "$resolve_lib" "$dist_resolve"
  chmod +x "$dist_resolve"
  publish_pkg "$dist_resolve"

  compose_check="${APM_COMMON_SRC}/scripts/check-compose.sh"
  if [[ ! -f "$compose_check" ]]; then
    echo "[build] missing check-compose.sh: ${compose_check}" >&2
    exit 1
  fi
  dist_compose_check="${APM_BUILD_DIST:-${TMPDIR:-/tmp}}/check-compose.sh"
  cp -f "$compose_check" "$dist_compose_check"
  chmod +x "$dist_compose_check"
  publish_pkg "$dist_compose_check"
}

detect_image_arch() {
  case "$(uname -m)" in
    x86_64 | amd64) printf 'amd64\n' ;;
    aarch64 | arm64) printf 'arm64\n' ;;
    *)
      echo "[build] unsupported host arch: $(uname -m), defaulting to amd64" >&2
      printf 'amd64\n'
      ;;
  esac
}

image_platforms() {
  printf '%s\n' "${IMAGE_PLATFORMS:-linux/amd64,linux/arm64}"
}

image_arch_list() {
  local platforms="${1:-$(image_platforms)}"
  local item arch
  for item in ${platforms//,/ }; do
    arch="${item#linux/}"
    printf '%s\n' "$arch"
  done
}

ensure_buildx() {
  ensure_command docker
  if ! docker buildx version >/dev/null 2>&1; then
    echo "[build] docker buildx is required for multi-arch images" >&2
    exit 1
  fi

  local builder="${BUILDX_BUILDER:-databuff-multiarch}"
  if ! docker buildx inspect "$builder" >/dev/null 2>&1; then
    echo "[build] creating buildx builder ${builder} ..."
    docker buildx create --name "$builder" --driver docker-container --use
  else
    docker buildx use "$builder" >/dev/null 2>&1
  fi
  docker buildx inspect --bootstrap >/dev/null
}

prepare_image_build_context() {
  local svc="$1"
  local jar_path="$2"
  local ctx
  ctx="$(mktemp -d "${TMPDIR:-/tmp}/apm-image-${svc}.XXXXXX")"

  cp -f "${APM_DOCKER_IMAGE_SRC}/${svc}/Dockerfile" "${ctx}/"
  cp -f "${APM_DOCKER_IMAGE_SRC}/${svc}/start.sh" "${ctx}/"
  cp -f "${APM_DOCKER_IMAGE_SRC}/${svc}/application.yml" "${ctx}/"
  cp -f "$jar_path" "${ctx}/"

  if [[ "$svc" == "web" ]]; then
    mkdir -p "${ctx}/skills"
    cp -R "${APM_COMMON_SRC}/skills/." "${ctx}/skills/"
  fi

  printf '%s\n' "$ctx"
}

buildx_image() {
  local image_ref="$1"
  local ctx="$2"
  local platform="$3"
  shift 3
  local openjdk_image
  openjdk_image="$(openjdk_image_for_platform "$platform")"

  docker buildx build \
    --pull \
    --provenance=false \
    --sbom=false \
    --progress="${BUILDX_PROGRESS:-auto}" \
    --platform "$platform" \
    --build-arg "OPENJDK_IMAGE=${openjdk_image}" \
    -t "$image_ref" \
    "$@" \
    "$ctx"
}

# buildx --load 只能加载构建机原生架构；跨平台须直接导出 tar，否则 amd64 包会混入 arm64 二进制。
buildx_export_image_tarball() {
  local image_ref="$1"
  local ctx="$2"
  local platform="$3"
  local dest="$4"
  local openjdk_image
  openjdk_image="$(openjdk_image_for_platform "$platform")"

  docker buildx build \
    --pull \
    --provenance=false \
    --sbom=false \
    --progress="${BUILDX_PROGRESS:-auto}" \
    --platform "$platform" \
    --build-arg "OPENJDK_IMAGE=${openjdk_image}" \
    -t "$image_ref" \
    --output "type=docker,dest=${dest}" \
    "$ctx"
}

verify_image_tarball_arch() {
  local tarball="$1"
  local expected_arch="$2"
  local tmpdir layer bash_bin actual_arch

  if [[ ! -s "$tarball" ]]; then
    echo "[build] missing or empty image tarball: ${tarball}" >&2
    exit 1
  fi

  tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/apm-image-verify.XXXXXX")"
  tar -xf "$tarball" -C "$tmpdir" 2>/dev/null || {
    echo "[build] cannot read image tarball: ${tarball}" >&2
    rm -rf "$tmpdir"
    exit 1
  }

  layer="$(python3 - <<'PY' "$tmpdir"
import json, pathlib, sys
root = pathlib.Path(sys.argv[1])
manifest = json.loads((root / "manifest.json").read_text())
print(root / manifest[0]["Layers"][0])
PY
)"
  bash_bin="${tmpdir}/bash.bin"
  tar -xf "$layer" -C "$tmpdir" usr/bin/bash 2>/dev/null || true
  if [[ ! -f "${tmpdir}/usr/bin/bash" ]]; then
    echo "[build] cannot verify ${tarball}: missing usr/bin/bash in base layer" >&2
    rm -rf "$tmpdir"
    exit 1
  fi
  cp -f "${tmpdir}/usr/bin/bash" "$bash_bin"

  case "$(file -b "$bash_bin")" in
    *x86-64*) actual_arch="amd64" ;;
    *ARM\ aarch64*) actual_arch="arm64" ;;
    *)
      echo "[build] cannot detect arch for ${tarball}: $(file -b "$bash_bin")" >&2
      rm -rf "$tmpdir" "$bash_bin"
      exit 1
      ;;
  esac

  rm -rf "$tmpdir" "$bash_bin"

  if [[ "$actual_arch" != "$expected_arch" ]]; then
    echo "[build] arch mismatch in ${tarball}: expected ${expected_arch}, got ${actual_arch}" >&2
    exit 1
  fi
}

publish_apm_images() {
  local release_version="$1"
  local ingest_jar="$2"
  local web_jar="$3"
  local demo_jar="$4"

  export_apm_stack_tarballs "$release_version" "$ingest_jar" "$web_jar" "$demo_jar"
}

write_compose_env() {
  local env_file="$1"
  local release_version="$2"

  cat >"$env_file" <<EOF
APM_INGEST_IMAGE=$(ingest_image_ref "$release_version")
APM_WEB_IMAGE=$(web_image_ref "$release_version")
INGEST_JAVA_TOOL_OPTIONS="-Xms512m -Xmx1536m -Duser.timezone=Asia/Shanghai"
WEB_JAVA_TOOL_OPTIONS="-Xms256m -Xmx700m -Duser.timezone=Asia/Shanghai"
INGEST_MEM_LIMIT=2g
WEB_MEM_LIMIT=1g
INGEST_CPUS=2
WEB_CPUS=1
DORIS_FE_IMAGE=${DORIS_FE_IMAGE}
DORIS_BE_IMAGE=${DORIS_BE_IMAGE}
DORIS_FE_CPUS=1
DORIS_BE_CPUS=2
DORIS_FE_MEM_LIMIT=2g
DORIS_BE_MEM_LIMIT=3g
EOF
}

copy_install_script() {
  local src="$1"
  local dest="$2"
  render_pkg_urls "$src" "$dest"
  chmod +x "$dest"
}

render_pkg_urls() {
  local src="$1"
  local dest="$2"
  local base version
  base="$(pkg_base_url)"
  base="${base%/}"
  version="$(resolve_release_version)"
  sed \
    -e "s|__APM_PKG_BASE__|${base}|g" \
    -e "s|__APM_VERSION__|${version}|g" \
    "$src" > "$dest"
}

patch_pkg_urls_in_file() {
  local file="$1"
  local tmp
  tmp="$(mktemp)"
  render_pkg_urls "$file" "$tmp"
  mv "$tmp" "$file"
}

stage_runtime_env_sh() {
  local dest="$1"
  local version="${2:-$(resolve_release_version)}"

  cp -f "${APM_DEPLOY_ROOT}/env.sh" "$dest"
  if sed --version >/dev/null 2>&1; then
    sed -i "s/^export APM_VERSION=.*/export APM_VERSION=${version}/" "$dest"
  else
    sed -i '' "s/^export APM_VERSION=.*/export APM_VERSION=${version}/" "$dest"
  fi
}

log_pkg_publish_targets() {
  local version
  version="$(resolve_release_version)"
  echo "[build] APM_PKG_BASE=$(pkg_base_url)"
  echo "[build] version images -> $(version_images_pkg_base_url "$version")"
  echo "[build] infra images   -> $(infra_images_pkg_base_url)"
  echo "[build] docker pkgs    -> $(version_docker_pkg_base_url "$version")"
  echo "[build] k8s pkgs       -> $(version_k8s_pkg_base_url "$version")"
  echo "[build] upload root    -> $(pkg_upload_user)@$(pkg_upload_host):$(pkg_remote_dir)"
}

ensure_command() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "[build] required command not found: ${cmd}" >&2
    exit 1
  fi
}

mvn_package_modules() {
  if [[ "${SKIP_BUILD:-0}" == "1" ]]; then
    echo "[build] SKIP_BUILD=1, reusing existing jars"
    return 0
  fi
  cd "$APM_REPO_ROOT"
  echo "[build] compiling ai-apm-ingest ..."
  mvn clean package -Dmaven.test.skip=true -pl ai-apm-ingest -am -q
  echo "[build] compiling ai-apm-web ..."
  mvn clean package -Dmaven.test.skip=true -pl ai-apm-web -am -q
}

mvn_package_demo() {
  if [[ "${SKIP_BUILD:-0}" == "1" ]]; then
    echo "[build] SKIP_BUILD=1, reusing existing demo jar"
    return 0
  fi
  cd "$APM_REPO_ROOT"
  echo "[build] compiling ai-apm-demo ..."
  mvn clean package -Dmaven.test.skip=true -pl ai-apm-demo -am -q
}

inject_doris_host_ports() {
  local compose="$1"
  if grep -q '"8030:8030"' "$compose" 2>/dev/null; then
    return 0
  fi
  python3 - "$compose" <<'PY'
import sys

path = sys.argv[1]
text = open(path, encoding="utf-8").read()
if "8030:8030" in text:
    sys.exit(0)
needles = [
    (
        "        exec bash entry_point.sh\n    volumes:",
        """        exec bash entry_point.sh
    ports:
      - \"8030:8030\"
      - \"9030:9030\"
      - \"8040:8040\"
    volumes:""",
    ),
    (
        "        exec bash init_fe.sh\n    volumes:",
        """        exec bash init_fe.sh
    ports:
      - \"8030:8030\"
      - \"9030:9030\"
    volumes:""",
    ),
]
for needle, block in needles:
    if needle in text:
        open(path, "w", encoding="utf-8").write(text.replace(needle, block, 1))
        sys.exit(0)
raise SystemExit("cannot inject doris ports: unexpected docker-compose.yml layout")
PY
}

create_tarball() {
  local stage_dir="$1"
  local archive_path="$2"
  local base_name
  base_name="$(basename "$stage_dir")"
  mkdir -p "$(dirname "$archive_path")"
  COPYFILE_DISABLE=1 tar --no-xattrs -czf "$archive_path" -C "$(dirname "$stage_dir")" "$base_name"
  echo "[build] package: ${archive_path}"
}
