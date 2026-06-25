#!/usr/bin/env bash
# 从版本化 / infra 镜像目录下载离线镜像包并按架构 docker load。

detect_host_arch() {
  case "$(uname -m)" in
    x86_64 | amd64) printf 'amd64\n' ;;
    aarch64 | arm64) printf 'arm64\n' ;;
    *)
      echo "[image-pkg] unsupported host arch: $(uname -m), defaulting to amd64" >&2
      printf 'amd64\n'
      ;;
  esac
}

apm_images_pkg_base_url() {
  if [[ -n "${APM_IMAGES_PKG_BASE:-}" ]]; then
    printf '%s\n' "${APM_IMAGES_PKG_BASE}"
    return 0
  fi
  if [[ -n "${APM_PKG_BASE:-}" ]]; then
    local version
    version="$(resolve_apm_release_version)"
    printf '%s\n' "${APM_PKG_BASE%/}/${version}/images"
    return 0
  fi
  echo "[image-pkg] missing APM_PKG_BASE" >&2
  return 1
}

infra_images_pkg_base_url() {
  if [[ -n "${APM_INFRA_IMAGES_PKG_BASE:-}" ]]; then
    printf '%s\n' "${APM_INFRA_IMAGES_PKG_BASE}"
    return 0
  fi
  if [[ -n "${APM_PKG_BASE:-}" ]]; then
    printf '%s\n' "${APM_PKG_BASE%/}/infra/images"
    return 0
  fi
  echo "[image-pkg] missing APM_PKG_BASE" >&2
  return 1
}

is_infra_image_component() {
  case "$1" in
    doris-fe | doris-be | doris-stack | zookeeper) return 0 ;;
    *) return 1 ;;
  esac
}

image_pkg_base_for_component() {
  local component="$1"
  if is_infra_image_component "$component"; then
    infra_images_pkg_base_url
  else
    apm_images_pkg_base_url
  fi
}

image_tarball_name() {
  local component="$1"
  local version="$2"
  local arch="$3"
  local ext="${4:-${IMAGE_TARBALL_EXT:-.tar.gz}}"
  printf '%s-%s-%s%s\n' "$component" "$version" "$arch" "$ext"
}

doris_image_version() {
  local image="${1:-${DORIS_FE_IMAGE:-apache/doris:fe-4.1.1}}"
  local tag="${image##*:}"
  tag="${tag#fe-}"
  tag="${tag#be-}"
  printf '%s\n' "$tag"
}

zookeeper_image_version() {
  local image="${1:-${ZOOKEEPER_IMAGE:-bitnamilegacy/zookeeper:3.9}}"
  printf '%s\n' "${image##*:}"
}

resolve_apm_release_version() {
  if [[ -n "${APM_VERSION:-}" ]]; then
    printf '%s\n' "${APM_VERSION%-SNAPSHOT}"
    return 0
  fi
  if [[ -n "${ROOT:-}" && -f "${ROOT}/VERSION" ]]; then
    tr -d '[:space:]' <"${ROOT}/VERSION"
    return 0
  fi
  if [[ -n "${APM_INGEST_IMAGE:-}" ]]; then
    printf '%s\n' "${APM_INGEST_IMAGE##*:}"
    return 0
  fi
  echo "[image-pkg] missing APM_VERSION (set in env.sh)" >&2
  return 1
}

resolve_apm_ingest_image_ref() {
  local version="$1"
  if [[ -n "${APM_INGEST_IMAGE:-}" ]]; then
    printf '%s\n' "$APM_INGEST_IMAGE"
    return 0
  fi
  printf '%s\n' "${RUNTIME_IMAGE_NAMESPACE:-databuffhub}/ai-apm-ingest:${version}"
}

resolve_apm_web_image_ref() {
  local version="$1"
  if [[ -n "${APM_WEB_IMAGE:-}" ]]; then
    printf '%s\n' "$APM_WEB_IMAGE"
    return 0
  fi
  printf '%s\n' "${RUNTIME_IMAGE_NAMESPACE:-databuffhub}/ai-apm-web:${version}"
}

resolve_apm_demo_image_ref() {
  local version="$1"
  if [[ -n "${APM_DEMO_IMAGE:-}" ]]; then
    printf '%s\n' "$APM_DEMO_IMAGE"
    return 0
  fi
  printf '%s\n' "${RUNTIME_IMAGE_NAMESPACE:-databuffhub}/ai-apm-demo:${version}"
}

image_load_forced() {
  [[ "${FORCE_PULL_IMAGES:-0}" == "1" ]]
}

docker_image_exists() {
  local image="$1"
  if [[ "${IMAGE_LOAD_CMD:-}" == "ctr" ]] && command -v ctr >/dev/null 2>&1; then
    ctr -n k8s.io images ls name=="${image}" 2>/dev/null | grep -q .
    return $?
  fi
  docker image inspect "$image" >/dev/null 2>&1
}

image_download_quiet() {
  [[ "${IMAGE_DOWNLOAD_QUIET:-0}" == "1" ]]
}

# 下载进度走 stderr；导入阶段也用 stderr 提示，避免 curl | bash 时 stdout 非 TTY 导致静默。
image_pkg_interactive() {
  ! image_download_quiet && [[ -t 2 ]]
}

file_size_human() {
  local path="$1"
  local bytes
  bytes="$(stat -c '%s' "$path" 2>/dev/null || stat -f '%z' "$path" 2>/dev/null || echo 0)"
  format_bytes_human "$bytes"
}

format_bytes_human() {
  local bytes="${1:-0}"
  awk -v b="$bytes" 'BEGIN {
    if (b >= 1073741824) printf "%.1fG", b / 1073741824
    else if (b >= 1048576) printf "%.0fM", b / 1048576
    else if (b >= 1024) printf "%.0fK", b / 1024
    else printf "%dB", b
  }'
}

# 远程 tarball 总大小：只读 ${url}.size 侧车（openocta HEAD/Range 不可用）。
valid_remote_content_length() {
  local len="$1"
  [[ -n "$len" && "$len" =~ ^[0-9]+$ && "$len" -gt 1048576 ]]
}

probe_sidecar_content_length() {
  local url="$1"
  local len
  len="$(curl -fsSL --max-time 15 "${url}.size" 2>/dev/null | tr -d '[:space:]')"
  if valid_remote_content_length "$len"; then
    printf '%s\n' "$len"
  fi
}

resolve_tarball_content_length() {
  local pkg_base="$1"
  local name="$2"
  probe_sidecar_content_length "${pkg_base%/}/${name}"
}

probe_remote_content_length() {
  probe_sidecar_content_length "${1%/*}/$(basename "$1")" 2>/dev/null || true
}

# 不用 curl --progress-bar：CentOS7 curl 7.29 对 chunked 响应会误报 100% 并一直刷新。
download_file_via_curl_once() {
  local url="$1"
  local dest="$2"
  local label="${3:-$(basename "$dest")}"
  local expected="${4:-}"
  local resume="${5:-0}"

  local progress_interval="${IMAGE_DOWNLOAD_PROGRESS_INTERVAL:-10}"
  local min_delta=134217728 # 128MiB（chunked 无 Content-Length 时）
  local curl_pid start cur pct last_bytes=-1

  if [[ -n "$expected" && "$expected" -gt 0 ]]; then
    min_delta=$(( expected / 10 )) # 约每 10% 打印一次
    if [[ "$min_delta" -lt 1048576 ]]; then
      min_delta=1048576
    fi
  fi

  if [[ "$resume" == "1" && -f "$dest" ]]; then
    curl -fsSL -C - -o "$dest" "$url" &
  else
    curl -fsSL -o "$dest" "$url" &
  fi
  curl_pid=$!
  start=$SECONDS

  while kill -0 "$curl_pid" 2>/dev/null; do
    sleep "$progress_interval"
    if [[ ! -f "$dest" ]]; then
      continue
    fi
    cur="$(stat -c '%s' "$dest" 2>/dev/null || stat -f '%z' "$dest" 2>/dev/null || echo 0)"
    if [[ "$cur" -le "$last_bytes" ]]; then
      continue
    fi
    if [[ "$last_bytes" -ge 0 && $(( cur - last_bytes )) -lt "$min_delta" ]] \
      && kill -0 "$curl_pid" 2>/dev/null; then
      continue
    fi
    last_bytes="$cur"
    if [[ -n "$expected" ]]; then
      pct=$(( cur * 100 / expected ))
      if [[ "$pct" -gt 99 ]] && kill -0 "$curl_pid" 2>/dev/null; then
        pct=99
      fi
      echo "[pull-images]   ${label}: $(format_bytes_human "$cur") / $(format_bytes_human "$expected") (${pct}%, $(( SECONDS - start ))s)" >&2
    else
      echo "[pull-images]   ${label}: $(format_bytes_human "$cur") ($(( SECONDS - start ))s)" >&2
    fi
  done

  wait "$curl_pid"
}

download_file_via_curl() {
  local url="$1"
  local dest="$2"
  local label="${3:-$(basename "$dest")}"
  local expected="${4:-}"
  local max_attempts="${IMAGE_DOWNLOAD_RETRIES:-3}"
  local attempt resume=0

  for attempt in $(seq 1 "$max_attempts"); do
    if [[ "$attempt" -gt 1 ]]; then
      if [[ -f "$dest" ]]; then
        resume=1
        echo "[pull-images]   ${label}: 断点续传 (${attempt}/${max_attempts}) ..." >&2
      else
        resume=0
        echo "[pull-images]   ${label}: 重试下载 (${attempt}/${max_attempts}) ..." >&2
      fi
    fi
    if download_file_via_curl_once "$url" "$dest" "$label" "$expected" "$resume"; then
      return 0
    fi
  done
  return 1
}

validate_image_tarball() {
  local dest="$1"
  local bytes first
  bytes="$(stat -c '%s' "$dest" 2>/dev/null || stat -f '%z' "$dest" 2>/dev/null || echo 0)"
  if [[ "$bytes" -lt 1048576 ]]; then
    echo "[image-pkg] downloaded file too small (${bytes} bytes): ${dest}" >&2
    return 1
  fi
  if [[ "$dest" == *.gz ]]; then
    if ! gzip -t "$dest" 2>/dev/null; then
      echo "[image-pkg] invalid gzip tarball: ${dest}" >&2
      return 1
    fi
    return 0
  fi
  first="$(head -c 1 "$dest" 2>/dev/null || true)"
  if [[ "$first" == '<' ]]; then
    echo "[image-pkg] downloaded HTML instead of tarball (check URL / nginx): ${dest}" >&2
    return 1
  fi
  return 0
}

run_with_wait_hint() {
  local msg="$1"
  shift
  local hint_interval="${IMAGE_LOAD_PROGRESS_INTERVAL:-20}"

  "$@" &
  local cmd_pid=$!
  local start=$SECONDS
  while kill -0 "$cmd_pid" 2>/dev/null; do
    sleep "$hint_interval"
    if kill -0 "$cmd_pid" 2>/dev/null; then
      echo "[pull-images]   ${msg}... ($(( SECONDS - start ))s)" >&2
    fi
  done
  wait "$cmd_pid"
}

download_image_tarball() {
  local component="$1"
  local version="$2"
  local arch="$3"
  local dest_dir="$4"
  local ext="${5:-${IMAGE_TARBALL_EXT:-.tar.gz}}"

  local name url dest base expected=""
  name="$(image_tarball_name "$component" "$version" "$arch" "$ext")"
  base="$(image_pkg_base_for_component "$component")"
  url="${base%/}/${name}"
  dest="${dest_dir}/${name}"

  expected="$(probe_sidecar_content_length "$url" 2>/dev/null || true)"

  if image_pkg_interactive; then
    echo "[pull-images]   下载 ${name} ..." >&2
    if [[ -z "$expected" ]]; then
      echo "[pull-images]   未读取到 ${name}.size，进度无百分比" >&2
    fi
  fi

  if ! download_file_via_curl "$url" "$dest" "$name" "$expected"; then
    return 1
  fi
  if ! validate_image_tarball "$dest"; then
    rm -f "$dest"
    return 1
  fi
  if image_pkg_interactive; then
    echo "[pull-images]   下载完成 ($(file_size_human "$dest"))，开始导入本地镜像 (大镜像可能需数分钟) ..." >&2
  fi
}

image_pkg_download_failed_hint() {
  echo "[image-pkg] hint: large downloads may fail over HTTP/2 (curl error 92); retry or check nginx" >&2
  echo "[image-pkg] hint: run deploy/images/build-images.sh on build machine, or check nginx dir permissions (parent dirs must be 755)" >&2
}

_import_tarball_to_containerd() {
  local tarball="$1"
  if [[ "$tarball" == *.gz ]]; then
    gunzip -c "$tarball" | ctr -n k8s.io images import -
  else
    ctr -n k8s.io images import "$tarball"
  fi
}

_import_tarball_to_docker() {
  local tarball="$1"
  if [[ "$tarball" == *.gz ]]; then
    if command -v pv >/dev/null 2>&1; then
      gunzip -c "$tarball" | pv | docker load
    else
      gunzip -c "$tarball" | docker load
    fi
  elif command -v pv >/dev/null 2>&1; then
    pv "$tarball" | docker load
  else
    docker load -i "$tarball"
  fi
}

load_image_tarball() {
  local tarball="$1"
  local size
  size="$(file_size_human "$tarball")"

  if [[ "${IMAGE_LOAD_CMD:-}" == "ctr" ]] && command -v ctr >/dev/null 2>&1; then
    if image_pkg_interactive; then
      echo "[pull-images]   导入 ${size} 到 containerd (请耐心等待) ..." >&2
      run_with_wait_hint "containerd 导入中" _import_tarball_to_containerd "$tarball"
    else
      _import_tarball_to_containerd "$tarball" >/dev/null
    fi
    return 0
  fi

  if image_pkg_interactive; then
    echo "[pull-images]   导入 ${size} 到 docker (请耐心等待) ..." >&2
    run_with_wait_hint "docker 导入中" _import_tarball_to_docker "$tarball"
    return 0
  fi
  _import_tarball_to_docker "$tarball" >/dev/null
}

load_image_component() {
  local label="$1"
  local component="$2"
  local version="$3"
  local arch="$4"
  local tmpdir="$5"
  local image_ref="$6"
  local ext="${7:-${IMAGE_TARBALL_EXT:-.tar.gz}}"

  if ! image_load_forced && docker_image_exists "$image_ref"; then
    echo "[pull-images] ${label} 已存在，跳过"
    return 0
  fi

  echo "[pull-images] ${label}"
  if ! download_image_tarball "$component" "$version" "$arch" "$tmpdir" "$ext"; then
    if [[ "$ext" == ".tar.gz" ]]; then
      if ! download_image_tarball "$component" "$version" "$arch" "$tmpdir" .tar; then
        echo "[image-pkg] failed to download: $(image_tarball_name "$component" "$version" "$arch")" >&2
        image_pkg_download_failed_hint
        return 1
      fi
      ext=".tar"
    else
      echo "[image-pkg] failed to download: $(image_tarball_name "$component" "$version" "$arch" "$ext")" >&2
      image_pkg_download_failed_hint
      return 1
    fi
  fi
  load_image_tarball "${tmpdir}/$(image_tarball_name "$component" "$version" "$arch" "$ext")"
  echo "[pull-images] ${label} 完成"
}

all_docker_images_exist() {
  local ref
  for ref in "$@"; do
    if ! docker_image_exists "$ref"; then
      return 1
    fi
  done
  return 0
}

load_legacy_apm_components() {
  local version="$1"
  local arch="$2"
  local tmpdir="$3"
  local ingest_ref web_ref demo_ref

  ingest_ref="$(resolve_apm_ingest_image_ref "$version")"
  web_ref="$(resolve_apm_web_image_ref "$version")"
  demo_ref="$(resolve_apm_demo_image_ref "$version")"

  echo "[pull-images] fallback: 逐组件下载旧格式镜像包 (.tar)"
  load_image_component "${ingest_ref}" ai-apm-ingest "$version" "$arch" "$tmpdir" "$ingest_ref" .tar
  load_image_component "${web_ref}" ai-apm-web "$version" "$arch" "$tmpdir" "$web_ref" .tar
  load_image_component "${demo_ref}" ai-apm-demo "$version" "$arch" "$tmpdir" "$demo_ref" .tar
}

load_legacy_doris_components() {
  local doris_version="$1"
  local arch="$2"
  local tmpdir="$3"
  local fe_ref be_ref

  fe_ref="${DORIS_FE_IMAGE:-apache/doris:fe-${doris_version}}"
  be_ref="${DORIS_BE_IMAGE:-apache/doris:be-${doris_version}}"

  echo "[pull-images] fallback: 逐组件下载旧格式 Doris 镜像包 (.tar)"
  load_image_component "${fe_ref}" doris-fe "$doris_version" "$arch" "$tmpdir" "$fe_ref" .tar
  load_image_component "${be_ref}" doris-be "$doris_version" "$arch" "$tmpdir" "$be_ref" .tar
}

load_image_bundle() {
  local label="$1"
  local component="$2"
  local version="$3"
  local arch="$4"
  local tmpdir="$5"
  shift 5
  local refs=("$@")
  local ref tarball

  if ! image_load_forced && all_docker_images_exist "${refs[@]}"; then
    echo "[pull-images] ${label} 已存在，跳过"
    return 0
  fi

  echo "[pull-images] ${label}"
  if download_image_tarball "$component" "$version" "$arch" "$tmpdir"; then
    tarball="${tmpdir}/$(image_tarball_name "$component" "$version" "$arch")"
    load_image_tarball "$tarball"
    echo "[pull-images] ${label} 完成"
    return 0
  fi

  case "$component" in
    ai-apm-stack)
      load_legacy_apm_components "$version" "$arch" "$tmpdir"
      ;;
    doris-stack)
      load_legacy_doris_components "$version" "$arch" "$tmpdir"
      ;;
    *)
      echo "[image-pkg] failed to download bundle: $(image_tarball_name "$component" "$version" "$arch")" >&2
      image_pkg_download_failed_hint
      return 1
      ;;
  esac
  echo "[pull-images] ${label} 完成"
}

load_docker_stack_images() {
  local arch version doris_version tmpdir
  local ingest_ref web_ref demo_ref fe_ref be_ref

  ensure_image_pkg_command curl
  ensure_image_pkg_command docker

  arch="$(detect_host_arch)"
  version="$(resolve_apm_release_version)"
  doris_version="$(doris_image_version)"
  ingest_ref="$(resolve_apm_ingest_image_ref "$version")"
  web_ref="$(resolve_apm_web_image_ref "$version")"
  demo_ref="$(resolve_apm_demo_image_ref "$version")"
  fe_ref="${DORIS_FE_IMAGE:-apache/doris:fe-${doris_version}}"
  be_ref="${DORIS_BE_IMAGE:-apache/doris:be-${doris_version}}"

  if ! image_load_forced \
    && all_docker_images_exist "$ingest_ref" "$web_ref" "$demo_ref" "$fe_ref" "$be_ref"; then
    echo "[pull-images] 本地镜像已齐全，跳过下载"
    return 0
  fi

  tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/apm-image-pkg.XXXXXX")"
  echo "[pull-images] 加载离线镜像 (arch=${arch}, apm=${version}, doris=${doris_version})"

  load_image_bundle "APM stack (ingest+web+demo)" ai-apm-stack "$version" "$arch" "$tmpdir" \
    "$ingest_ref" "$web_ref" "$demo_ref"
  load_image_bundle "Doris stack (fe+be)" doris-stack "$doris_version" "$arch" "$tmpdir" \
    "$fe_ref" "$be_ref"

  rm -rf "$tmpdir"
}

load_k8s_stack_images() {
  local arch version doris_version zk_version tmpdir
  local ingest_ref web_ref demo_ref fe_ref be_ref zk_ref

  ensure_image_pkg_command curl

  arch="$(detect_host_arch)"
  version="$(resolve_apm_release_version)"
  doris_version="$(doris_image_version)"
  zk_version="$(zookeeper_image_version)"
  ingest_ref="$(resolve_apm_ingest_image_ref "$version")"
  web_ref="$(resolve_apm_web_image_ref "$version")"
  demo_ref="$(resolve_apm_demo_image_ref "$version")"
  fe_ref="${DORIS_FE_IMAGE:-apache/doris:fe-${doris_version}}"
  be_ref="${DORIS_BE_IMAGE:-apache/doris:be-${doris_version}}"
  zk_ref="${ZOOKEEPER_IMAGE:-bitnamilegacy/zookeeper:${zk_version}}"

  if ! image_load_forced \
    && all_docker_images_exist "$ingest_ref" "$web_ref" "$demo_ref" "$fe_ref" "$be_ref" "$zk_ref"; then
    echo "[pull-images] 本地镜像已齐全，跳过下载"
    return 0
  fi

  tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/apm-k8s-image-pkg.XXXXXX")"
  echo "[pull-images] 加载离线镜像 (arch=${arch}, apm=${version}, doris=${doris_version}, zk=${zk_version})"

  load_image_bundle "APM stack (ingest+web+demo)" ai-apm-stack "$version" "$arch" "$tmpdir" \
    "$ingest_ref" "$web_ref" "$demo_ref"
  load_image_bundle "Doris stack (fe+be)" doris-stack "$doris_version" "$arch" "$tmpdir" \
    "$fe_ref" "$be_ref"
  load_image_component "${zk_ref}" zookeeper "$zk_version" "$arch" "$tmpdir" "$zk_ref"

  rm -rf "$tmpdir"
}

load_k8s_apm_images() {
  local arch version tmpdir
  local ingest_ref web_ref demo_ref

  ensure_image_pkg_command curl

  arch="$(detect_host_arch)"
  version="$(resolve_apm_release_version)"
  ingest_ref="$(resolve_apm_ingest_image_ref "$version")"
  web_ref="$(resolve_apm_web_image_ref "$version")"
  demo_ref="$(resolve_apm_demo_image_ref "$version")"

  tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/apm-k8s-apm-image-pkg.XXXXXX")"
  echo "[pull-images] 强制更新 APM 镜像 (arch=${arch}, apm=${version})"

  load_image_bundle "APM stack (ingest+web+demo)" ai-apm-stack "$version" "$arch" "$tmpdir" \
    "$ingest_ref" "$web_ref" "$demo_ref"

  rm -rf "$tmpdir"
}

load_demo_image_from_pkg() {
  local arch version demo_ref tmpdir
  local ingest_ref web_ref

  ensure_image_pkg_command curl
  ensure_image_pkg_command docker

  arch="$(detect_host_arch)"
  version="$(resolve_apm_release_version)"
  demo_ref="$(resolve_apm_demo_image_ref "$version")"
  ingest_ref="$(resolve_apm_ingest_image_ref "$version")"
  web_ref="$(resolve_apm_web_image_ref "$version")"

  if ! image_load_forced && docker_image_exists "$demo_ref"; then
    echo "[pull-images] ${demo_ref} 已存在，跳过"
    return 0
  fi

  tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/apm-demo-image-pkg.XXXXXX")"
  echo "[pull-images] 加载 demo 镜像 (arch=${arch}, apm=${version})"
  load_image_bundle "APM stack (ingest+web+demo)" ai-apm-stack "$version" "$arch" "$tmpdir" \
    "$ingest_ref" "$web_ref" "$demo_ref"
  rm -rf "$tmpdir"
}

ensure_image_pkg_command() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "[image-pkg] required command not found: ${cmd}" >&2
    exit 1
  fi
}
