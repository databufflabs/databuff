#!/usr/bin/env bash
# 本地一键启动：编译 ingest / web / demo → 挂载 JAR 启动 Doris（含初始化 SQL）+ ingest + web + demo
# 首次使用前需执行 ./build-jdk-images.sh 构建 JDK 开发镜像
#
# Usage:
#   ./start.sh [ingest|web|demo|doris ...]
#
# Optional:
#   SKIP_BUILD=1          复用已有 Maven 产物
#   START_SKIP_READY=1    不等待 health check

set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

chmod +x "${ROOT}/scripts/"*.sh 2>/dev/null || true

# shellcheck source=scripts/lib.sh
. "${ROOT}/scripts/lib.sh"
load_local_env
ensure_local_jdk_image

# shellcheck source=scripts/compose-env.sh
. "${ROOT}/scripts/compose-env.sh"
# shellcheck source=../docker/scripts/runtime.sh
. "${ROOT}/../docker/scripts/runtime.sh"

# 解析要启动的服务；无参数时启动全部
UP_SERVICES=()
if [ "$#" -eq 0 ] && [ -z "${SERVICES:-}" ]; then
  UP_SERVICES=(
    "$DORIS_FE_SERVICE" "$DORIS_BE_SERVICE"
    "$INGEST_SERVICE" "$WEB_SERVICE" "$DEMO_SERVICE"
  )
else
  _raw=("$@")
  if [ ${#_raw[@]} -eq 0 ] && [ -n "${SERVICES:-}" ]; then
    IFS=',' read -ra _raw <<< "$SERVICES"
  fi
  _want_doris=0
  _seen="|"
  for _arg in "${_raw[@]}"; do
    _name="$(printf '%s' "$_arg" | tr '[:upper:]' '[:lower]' | sed 's/^ai-apm-//')"
    case "$_name" in
      ingest|ing)
        case "$_seen" in *"|${INGEST_SERVICE}|"*) ;; *)
          UP_SERVICES+=("$INGEST_SERVICE"); _seen="${_seen}${INGEST_SERVICE}|"; _want_doris=1 ;; esac
        ;;
      web)
        case "$_seen" in *"|${WEB_SERVICE}|"*) ;; *)
          UP_SERVICES+=("$WEB_SERVICE"); _seen="${_seen}${WEB_SERVICE}|"; _want_doris=1 ;; esac
        ;;
      demo)
        case "$_seen" in *"|${DEMO_SERVICE}|"*) ;; *)
          UP_SERVICES+=("$DEMO_SERVICE"); _seen="${_seen}${DEMO_SERVICE}|"; _want_doris=1 ;; esac
        case "$_seen" in *"|${INGEST_SERVICE}|"*) ;; *)
          UP_SERVICES+=("$INGEST_SERVICE"); _seen="${_seen}${INGEST_SERVICE}|" ;; esac
        ;;
      doris|fe|be|doris-fe|doris-be)
        _want_doris=1
        ;;
      *)
        echo "[start] unknown service: ${_arg} (expected: ingest, web, demo, doris)" >&2
        exit 1
        ;;
    esac
  done
  if [ "$_want_doris" = 1 ]; then
    UP_SERVICES=("$DORIS_FE_SERVICE" "$DORIS_BE_SERVICE" "${UP_SERVICES[@]}")
  fi
  if [ ${#UP_SERVICES[@]} -eq 0 ]; then
    echo "[start] no services selected" >&2
    exit 1
  fi
fi

ensure_vm_max_map_count

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  build_ingest_web
  build_demo
fi
verify_ingest_web_jars
verify_demo_jar
sync_all_run_dirs

_up_apps=()
for _svc in "${UP_SERVICES[@]}"; do
  case "$_svc" in
    "$DORIS_FE_SERVICE"|"$DORIS_BE_SERVICE") ;;
    *) _up_apps+=("$_svc") ;;
  esac
done

_up_services_include_web() {
  local _svc
  for _svc in "${UP_SERVICES[@]}"; do
    if [ "$_svc" = "$WEB_SERVICE" ]; then
      return 0
    fi
  done
  return 1
}

_handle_doris_start_failure() {
  if _up_services_include_web; then
    bootstrap_web_for_troubleshooting
  fi
  exit 1
}

if doris_has_data; then
  echo "[start] doris data exists, starting selected services"
  _up_doris=()
  for _svc in "${UP_SERVICES[@]}"; do
    case "$_svc" in
      "$DORIS_FE_SERVICE"|"$DORIS_BE_SERVICE") _up_doris+=("$_svc") ;;
    esac
  done
  if [ ${#_up_doris[@]} -gt 0 ]; then
    compose_up_wait "${_up_doris[@]}" || _handle_doris_start_failure
  fi
  if [ ${#_up_apps[@]} -gt 0 ]; then
    compose_up "${_up_apps[@]}"
  fi
else
  echo "[start] initializing doris"
  mkdir -p "${ROOT}/data/fe-meta" "${ROOT}/data/be-storage"
  compose_up_wait "$DORIS_FE_SERVICE" "$DORIS_BE_SERVICE" || _handle_doris_start_failure
  "${ROOT}/scripts/init-doris.sh"
  if [ ${#_up_apps[@]} -gt 0 ]; then
    compose_up "${_up_apps[@]}"
  fi
fi

_to_restart=()
for _svc in "${UP_SERVICES[@]}"; do
  case "$_svc" in
    "$INGEST_SERVICE"|"$WEB_SERVICE") _to_restart+=("$_svc") ;;
  esac
done
if [ ${#_to_restart[@]} -gt 0 ]; then
  echo "[start] force restarting ${_to_restart[*]} to load updated jars"
  compose_cmd restart "${_to_restart[@]}"
fi

if [ "${START_SKIP_READY:-0}" != "1" ]; then
  _wait_failed=0
  _wait_pids=()
  for _svc in "${UP_SERVICES[@]}"; do
    case "$_svc" in
      "$INGEST_SERVICE")
        wait_for_container_ready "$INGEST_SERVICE" "ingest" "${APM_READY_TIMEOUT:-300}" &
        _wait_pids+=("$!")
        ;;
      "$WEB_SERVICE")
        wait_for_container_ready "$WEB_SERVICE" "web" "${APM_READY_TIMEOUT:-300}" &
        _wait_pids+=("$!")
        ;;
    esac
  done
  if [ ${#_wait_pids[@]} -gt 0 ]; then
    echo "[start] waiting for selected services container health ..."
    for _pid in "${_wait_pids[@]}"; do
      wait "$_pid" || _wait_failed=1
    done
    if [ "$_wait_failed" -ne 0 ]; then
      echo "[start] services not fully ready yet; see summary below" >&2
    fi
  fi
fi

if [ "${START_SKIP_SUMMARY:-0}" != "1" ]; then
  print_apm_ready_summary
  echo "[start] demo OTLP target: http://ai-apm-ingest:4318/v1/traces"
  echo "[start] demo logs: docker logs -f ${DEMO_SERVICE}"
fi
