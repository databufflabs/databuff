#!/usr/bin/env bash
# Runtime helpers shared by start/stop scripts in the deploy package.

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

wait_for_apm_services_ready() {
  timeout="${APM_READY_TIMEOUT:-300}"
  failed=0

  echo "[start] waiting for ingest and web to become ready (timeout ${timeout}s) ..."
  wait_for_http_ready "http://127.0.0.1:4318/health" "ingest" "$timeout" &
  pid_ingest=$!
  wait_for_http_ready "http://127.0.0.1:27403/health" "web" "$timeout" &
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
  echo "    http://${host_ip}:27403"
  echo -e "  ${CYN}登录账号${RST}"
  echo -e "    用户名: ${BLD}admin${RST}"
  echo -e "    密码:   ${YLW}Databuff@123${RST}"
  echo -e "  ${CYN}Ingest${RST}"
  echo "    http://${host_ip}:4318/v1/traces"
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
    host_ip="$(detect_local_ip)"
    wait_for_http_ready "http://${host_ip}:27403/health" "web" "$timeout" || true
  fi

  if [ "${START_SKIP_SUMMARY:-0}" != "1" ]; then
    print_web_bootstrap_summary "$reason"
  fi
}

print_web_bootstrap_summary() {
  local reason="${1:-Doris 未就绪}"
  host_ip="$(detect_local_ip)"
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
  echo "    http://${host_ip}:27403"
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
