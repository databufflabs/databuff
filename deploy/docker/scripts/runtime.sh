#!/usr/bin/env bash
# Runtime helpers shared by start/stop scripts in the deploy package.
#
# Readiness probes containers via Docker health (container-internal ports).
# Do not curl host-mapped ports here — users may remap 4318/27403 on the host.

# Listen ports inside containers (fixed; independent of host publish mapping).
INGEST_CONTAINER_HTTP_PORT="${INGEST_CONTAINER_HTTP_PORT:-4318}"
WEB_CONTAINER_HTTP_PORT="${WEB_CONTAINER_HTTP_PORT:-27403}"

ensure_vm_max_map_count() {
  local required=2000000 current
  current="$(sysctl -n vm.max_map_count 2>/dev/null || echo 0)"
  if [ "$current" -lt "$required" ]; then
    echo "[start] raising vm.max_map_count ${current} -> ${required}"
    sysctl -w "vm.max_map_count=${required}" >/dev/null 2>&1 || true
  fi
}

detect_local_ip() {
  ip=""
  if command -v ip >/dev/null 2>&1; then
    ip="$(ip route get 1.1.1.1 2>/dev/null | awk '{for (i=1;i<=NF;i++) if ($i=="src") {print $(i+1); exit}}')"
  fi
  if [ -z "$ip" ] && command -v ipconfig >/dev/null 2>&1; then
    for iface in en0 en1; do
      ip="$(ipconfig getifaddr "$iface" 2>/dev/null || true)"
      [ -n "$ip" ] && break
    done
  fi
  if [ -z "$ip" ] && command -v hostname >/dev/null 2>&1; then
    ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
  fi
  if [ -z "$ip" ]; then
    echo "127.0.0.1"
  else
    echo "$ip"
  fi
}

# Host-published port for a container listen port (e.g. 4318/tcp -> 84318).
# Falls back to env override or the container port itself.
published_host_port() {
  local container="$1"
  local container_port="$2"
  local fallback="${3:-$container_port}"
  local mapping=""

  if command -v docker >/dev/null 2>&1; then
    mapping="$(docker port "$container" "${container_port}/tcp" 2>/dev/null | head -n1 || true)"
  fi
  if [ -n "$mapping" ]; then
    echo "${mapping##*:}"
    return 0
  fi
  echo "$fallback"
}

apm_web_host_port() {
  published_host_port "${WEB_SERVICE:-ai-apm-web}" "$WEB_CONTAINER_HTTP_PORT" "${WEB_HTTP_PORT:-$WEB_CONTAINER_HTTP_PORT}"
}

apm_ingest_host_port() {
  published_host_port "${INGEST_SERVICE:-ai-apm-ingest}" "$INGEST_CONTAINER_HTTP_PORT" "${INGEST_HTTP_PORT:-$INGEST_CONTAINER_HTTP_PORT}"
}

check_http_ready() {
  url="$1"
  if command -v curl >/dev/null 2>&1; then
    curl -sf --max-time 3 "$url" >/dev/null 2>&1
    return $?
  fi
  host="${url#http://}"
  host="${host%%/*}"
  port="${host#*:}"
  host="${host%%:*}"
  if [ "$port" = "$host" ]; then
    port=80
  fi
  bash -c "exec 3<>/dev/tcp/${host}/${port}" >/dev/null 2>&1
}

wait_for_http_ready() {
  url="$1"
  label="$2"
  timeout="${3:-300}"
  interval="${4:-3}"
  elapsed=0
  next_log=0

  while [ "$elapsed" -lt "$timeout" ]; do
    if check_http_ready "$url"; then
      echo "[start] ${label} ready (${elapsed}s)"
      return 0
    fi
    if [ "$elapsed" -ge "$next_log" ]; then
      echo "[start] waiting for ${label} (${elapsed}s/${timeout}s) ..."
      next_log=$((elapsed + 30))
    fi
    sleep "$interval"
    elapsed=$((elapsed + interval))
  done

  echo "[start] timeout waiting for ${label} (${url}) after ${timeout}s" >&2
  return 1
}

container_health_status() {
  local name="$1"
  docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$name" 2>/dev/null || echo "missing"
}

# Probe readiness inside the container (compose healthcheck uses internal ports).
check_container_ready() {
  local name="$1"
  local status
  status="$(container_health_status "$name")"
  [ "$status" = "healthy" ]
}

wait_for_container_ready() {
  local name="$1"
  local label="$2"
  local timeout="${3:-300}"
  local interval="${4:-3}"
  local elapsed=0
  local next_log=0
  local status

  while [ "$elapsed" -lt "$timeout" ]; do
    status="$(container_health_status "$name")"
    if [ "$status" = "healthy" ]; then
      echo "[start] ${label} ready (${elapsed}s)"
      return 0
    fi
    if [ "$elapsed" -ge "$next_log" ]; then
      echo "[start] waiting for ${label} [${name}: ${status}] (${elapsed}s/${timeout}s) ..."
      next_log=$((elapsed + 30))
    fi
    sleep "$interval"
    elapsed=$((elapsed + interval))
  done

  echo "[start] timeout waiting for ${label} (container ${name}, last status=${status:-unknown}) after ${timeout}s" >&2
  return 1
}

wait_for_apm_services_ready() {
  timeout="${APM_READY_TIMEOUT:-300}"
  failed=0
  ingest_name="${INGEST_SERVICE:-ai-apm-ingest}"
  web_name="${WEB_SERVICE:-ai-apm-web}"

  echo "[start] waiting for ingest and web container health (timeout ${timeout}s) ..."
  wait_for_container_ready "$ingest_name" "ingest" "$timeout" &
  pid_ingest=$!
  wait_for_container_ready "$web_name" "web" "$timeout" &
  pid_web=$!

  wait "$pid_ingest" || failed=1
  wait "$pid_web" || failed=1

  if [ "$failed" -ne 0 ]; then
    echo "[start] one or more services are not ready; check: docker compose logs ai-apm-ingest ai-apm-web" >&2
    return 1
  fi
}

print_apm_ready_summary() {
  host_ip="$(detect_local_ip)"
  web_port="$(apm_web_host_port)"
  ingest_port="$(apm_ingest_host_port)"
  CYN='\033[36m'
  GRN='\033[32m'
  YLW='\033[33m'
  BLD='\033[1m'
  RST='\033[0m'

  echo ""
  echo -e "${CYN}========================================================${RST}"
  echo -e "${GRN}${BLD} 服务已就绪${RST}"
  echo -e "${CYN}========================================================${RST}"
  echo ""
  echo -e "  ${CYN}Web UI${RST}"
  echo "    http://${host_ip}:${web_port}"
  echo -e "  ${CYN}登录账号${RST}"
  echo -e "    用户名: ${BLD}admin${RST}"
  echo -e "    密码:   ${YLW}Databuff@123${RST}"
  echo -e "  ${CYN}Ingest${RST}"
  echo "    http://${host_ip}:${ingest_port}/v1/traces"
  echo ""
  echo -e "${CYN}========================================================${RST}"
  echo ""
}

bootstrap_web_for_troubleshooting() {
  local reason="${1:-Doris 未就绪}"
  web_service="${WEB_SERVICE:-ai-apm-web}"
  timeout="${APM_READY_TIMEOUT:-300}"

  echo "[start] ${reason}; starting ${web_service} for troubleshooting" >&2
  compose_cmd up -d --no-deps --force-recreate "$web_service"

  if [ "${START_SKIP_READY:-0}" != "1" ]; then
    wait_for_container_ready "$web_service" "web" "$timeout" || true
  fi

  if [ "${START_SKIP_SUMMARY:-0}" != "1" ]; then
    print_web_bootstrap_summary "$reason"
  fi
}

print_web_bootstrap_summary() {
  local reason="${1:-Doris 未就绪}"
  host_ip="$(detect_local_ip)"
  web_port="$(apm_web_host_port)"
  CYN='\033[36m'
  YLW='\033[33m'
  BLD='\033[1m'
  RST='\033[0m'

  echo ""
  echo -e "${CYN}========================================================${RST}"
  echo -e "${YLW}${BLD} ${reason} — Web 排障模式${RST}"
  echo -e "${CYN}========================================================${RST}"
  echo ""
  echo -e "  ${CYN}Web UI${RST}"
  echo "    http://${host_ip}:${web_port}"
  echo -e "  ${CYN}登录账号${RST}"
  echo -e "    用户名: ${BLD}admin${RST}"
  echo -e "    密码:   ${YLW}Databuff@123${RST}"
  echo ""
  echo -e "  ${CYN}下一步${RST}"
  echo "    1. 打开「配置 → 大模型」，填写 API Key 并保存"
  echo "    2. 打开「AI 平台」，选择运维专家"
  echo "    3. 提供机器 SSH 信息（地址、用户名、密码或密钥），由专家登录排查"
  echo "    4. Doris 修复后 Web 约每分钟自动重探；GET /health 中 doris 变为 UP 即退出排障模式（无需重启 Web）"
  echo ""
  echo -e "${CYN}========================================================${RST}"
  echo ""
}
