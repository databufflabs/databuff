#!/usr/bin/env bash
# Runtime Doris outage → ops expert recovery E2E (open-source release gate B).
#
# ## Release gates (A / B / C — complementary; none substitutes for another)
#   A:  deploy/test/doris-failover-e2e.sh
#       install-time Doris failure → Web bootstrap / troubleshooting
#   B (this script): deploy/test/doris-runtime-failover-e2e.sh
#       runtime outage → 运维专家 (ops) chat recovery
#       Main evidence = ops session; /health is auxiliary.
#       Requires LLM API Key. Do NOT docker start FE/BE to fake recovery.
#   C:  deploy/test/run-tests.sh — API regression
# Ops docs: docs/运维参考/Docker运维.md 「发布验收 / Release gate」
#
# ## Acceptance main path (required — NOT curl /health alone)
#   1. Full stack healthy
#   2. Inject runtime Doris unavailability: docker stop FE/BE
#   3. Confirm Web still login + AI platform (page / experts API)
#   4. Drive recovery via 运维专家 (expertId=ops) chat — Bash/tools on host/remote
#   5. Assert Doris usable again; /health doris=UP + recovered logs are AUXILIARY only
#   Main evidence = ops session (submit + poll messages / tool calls).
#
# ## Boundary vs deploy/test/doris-failover-e2e.sh (release gate A)
#   - Gate A: install-time Doris failure injection → Web troubleshooting bootstrap
#   - Gate B (this script): steady-state outage → ops-expert-driven recovery (no script docker start)
#   Gates A/B and run-tests.sh (C) are complementary; none substitutes for another.
#
# ## Critical design rule
#   This script MAY docker stop FE/BE to inject failure.
#   This script MUST NOT docker start FE/BE in the recovery phase to impersonate the ops expert.
#   Exit-trap cleanup may restart Doris so the lab is not left broken — that is NOT gate evidence.
#
# Usage (deploy/local preferred — containers ai-apm-*, web :27403):
#   # stack up + LLM API Key configured in Web → 配置 → 大模型
#   ./deploy/test/doris-runtime-failover-e2e.sh
#
# Health-only smoke (NOT a valid release gate):
#   SKIP_OPS_EXPERT=1 ./deploy/test/doris-runtime-failover-e2e.sh
#
# Environment:
#   WEB_BASE_URL              default http://127.0.0.1:27403
#   WEB_CONTAINER             default ai-apm-web
#   DORIS_FE_CONTAINER        default ai-apm-doris-fe
#   DORIS_BE_CONTAINER        default ai-apm-doris-be
#   ADMIN_ACCOUNT             default admin
#   ADMIN_PASSWORD            default Databuff@123
#   HEALTH_WAIT_SEC           outage → doris:UNAVAILABLE wait (default 90)
#   OPS_RECOVERY_WAIT_SEC     after ops chat, wait for doris:UP (default 300)
#   OPS_POLL_TIMEOUT_SEC      ops session poll timeout (default 600)
#   OPS_POLL_INTERVAL_SEC     default 3
#   API_MAX_TIME_SEC          curl max-time for login/experts/portal (default 10)
#   POLL_INTERVAL_SEC         health poll interval (default 2)
#   OPS_EXPERT_ID             default ops
#   OPS_PROMPT                override recovery prompt (optional)
#   OPS_FOLLOWUP              optional second user message after first round
#   OPS_HOST_HINT             injected into prompt (docker/ssh/compose hints)
#   OPS_EVIDENCE_FILE         save full session JSON (default /tmp/doris-runtime-ops-evidence.json)
#   REQUIRE_OPS_TOOL_EVIDENCE 1 = require Bash/docker tool evidence in session (default 1)
#   SKIP_OPS_EXPERT           1 = health/login smoke only; prints 非发版有效门禁 (default 0)
#   SKIP_BASELINE / SKIP_OUTAGE / SKIP_OPS_RECOVERY
#   SKIP_STARTUP_PROBE        1 default — optional S4 not part of ops main path
#   KEEP_DORIS_DOWN           1 = skip exit-trap cleanup restore
#   STOP_AFTER                baseline|outage|ops|all
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OPS_CHAT_PY="${SCRIPT_DIR}/lib/doris_runtime_ops_chat.py"

WEB_BASE_URL="${WEB_BASE_URL:-http://127.0.0.1:27403}"
WEB_CONTAINER="${WEB_CONTAINER:-ai-apm-web}"
DORIS_FE_CONTAINER="${DORIS_FE_CONTAINER:-ai-apm-doris-fe}"
DORIS_BE_CONTAINER="${DORIS_BE_CONTAINER:-ai-apm-doris-be}"
ADMIN_ACCOUNT="${ADMIN_ACCOUNT:-admin}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-Databuff@123}"
HEALTH_WAIT_SEC="${HEALTH_WAIT_SEC:-90}"
OPS_RECOVERY_WAIT_SEC="${OPS_RECOVERY_WAIT_SEC:-300}"
OPS_POLL_TIMEOUT_SEC="${OPS_POLL_TIMEOUT_SEC:-600}"
OPS_POLL_INTERVAL_SEC="${OPS_POLL_INTERVAL_SEC:-3}"
API_MAX_TIME_SEC="${API_MAX_TIME_SEC:-10}"
POLL_INTERVAL_SEC="${POLL_INTERVAL_SEC:-2}"
OPS_EXPERT_ID="${OPS_EXPERT_ID:-ops}"
OPS_FOLLOWUP="${OPS_FOLLOWUP:-}"
OPS_HOST_HINT="${OPS_HOST_HINT:-}"
OPS_EVIDENCE_FILE="${OPS_EVIDENCE_FILE:-/tmp/doris-runtime-ops-evidence.json}"
REQUIRE_OPS_TOOL_EVIDENCE="${REQUIRE_OPS_TOOL_EVIDENCE:-1}"
STOP_AFTER="${STOP_AFTER:-all}"

SKIP_BASELINE="${SKIP_BASELINE:-0}"
SKIP_OUTAGE="${SKIP_OUTAGE:-0}"
SKIP_OPS_RECOVERY="${SKIP_OPS_RECOVERY:-0}"
SKIP_OPS_EXPERT="${SKIP_OPS_EXPERT:-0}"
SKIP_STARTUP_PROBE="${SKIP_STARTUP_PROBE:-1}"
KEEP_DORIS_DOWN="${KEEP_DORIS_DOWN:-0}"

LOG_PREFIX="[doris-runtime-failover-e2e]"
NEED_DORIS_CLEANUP=0
GATE_MODE="release" # release | smoke_invalid

log() { echo "${LOG_PREFIX} $*"; }
warn() { echo "${LOG_PREFIX} WARN: $*" >&2; }
fail() { echo "${LOG_PREFIX} ERROR: $*" >&2; exit 1; }

maybe_stop() {
  local phase="$1"
  if [[ "$STOP_AFTER" == "$phase" ]]; then
    log "STOP_AFTER=${phase}; exiting"
    exit 0
  fi
}

require_cmds() {
  command -v curl >/dev/null 2>&1 || fail "curl is required"
  command -v docker >/dev/null 2>&1 || fail "docker is required"
  command -v python3 >/dev/null 2>&1 || fail "python3 is required"
  [[ -f "$OPS_CHAT_PY" ]] || fail "missing ${OPS_CHAT_PY}"
}

utc_now() { date -u +%Y-%m-%dT%H:%M:%SZ; }

container_running() {
  docker ps --format '{{.Names}}' | grep -qx "$1"
}

web_pid() {
  docker inspect -f '{{.State.Pid}}' "$WEB_CONTAINER" 2>/dev/null \
    || fail "cannot inspect PID of ${WEB_CONTAINER}"
}

# Injection only — recovery path must NOT call this as "pass".
stop_doris_inject() {
  log "inject: docker stop ${DORIS_FE_CONTAINER} ${DORIS_BE_CONTAINER}"
  docker stop "$DORIS_FE_CONTAINER" "$DORIS_BE_CONTAINER" >/dev/null
  NEED_DORIS_CLEANUP=1
}

# Cleanup only (exit trap / smoke leave-behind). Never counted as ops recovery evidence.
cleanup_start_doris() {
  log "cleanup (NOT gate evidence): docker start ${DORIS_FE_CONTAINER} ${DORIS_BE_CONTAINER}"
  docker start "$DORIS_FE_CONTAINER" "$DORIS_BE_CONTAINER" >/dev/null 2>&1 || true
  NEED_DORIS_CLEANUP=0
}

on_exit() {
  local rc=$?
  set +e
  if [[ "$KEEP_DORIS_DOWN" != "1" && "$NEED_DORIS_CLEANUP" == "1" ]]; then
    warn "exit trap cleanup restore Doris — this is NOT release-gate recovery evidence"
    cleanup_start_doris
  fi
  # EXIT trap must re-exit with original status (bare return can collapse to 0)
  exit "$rc"
}
trap on_exit EXIT

curl_capture() {
  local out_file="$1"
  shift
  local meta
  meta="$(curl -sS -o "$out_file" -w '%{http_code} %{time_total}' --max-time "$API_MAX_TIME_SEC" "$@" || true)"
  if [[ -z "$meta" ]]; then
    echo "000 999"
  else
    echo "$meta"
  fi
}

health_json() {
  curl -sf --max-time "$API_MAX_TIME_SEC" "${WEB_BASE_URL}/health" 2>/dev/null || true
}

assert_health() {
  local expect_status="$1"
  local expect_doris="$2"
  local label="$3"
  local body status doris
  body="$(health_json)"
  [[ -n "$body" ]] || fail "${label}: GET /health failed or empty"
  status="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1]).get("status",""))' "$body")"
  doris="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1]).get("doris",""))' "$body")"
  [[ "$status" == "$expect_status" ]] || fail "${label}: /health status=${status} expected ${expect_status} body=${body}"
  [[ "$doris" == "$expect_doris" ]] || fail "${label}: /health doris=${doris} expected ${expect_doris} body=${body}"
  log "${label}: /health auxiliary OK status=${status} doris=${doris}"
}

wait_for_doris_health() {
  local expect_doris="$1"
  local timeout_sec="$2"
  local label="$3"
  local deadline status doris body started elapsed
  started=$SECONDS
  deadline=$((SECONDS + timeout_sec))
  while (( SECONDS < deadline )); do
    body="$(health_json)"
    if [[ -n "$body" ]]; then
      status="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1]).get("status",""))' "$body" 2>/dev/null || true)"
      doris="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1]).get("doris",""))' "$body" 2>/dev/null || true)"
      if [[ "$status" == "UP" && "$doris" == "$expect_doris" ]]; then
        elapsed=$((SECONDS - started))
        log "${label}: doris=${expect_doris} after ${elapsed}s (limit ${timeout_sec}s)"
        return 0
      fi
      log "${label}: waiting doris=${expect_doris} (now status=${status:-?} doris=${doris:-?})"
    else
      log "${label}: waiting /health reachable..."
    fi
    sleep "$POLL_INTERVAL_SEC"
  done
  fail "${label}: timed out after ${timeout_sec}s waiting for doris=${expect_doris} (last=$(health_json))"
}

assert_login_page() {
  local label="$1"
  local tmp meta code t
  tmp="$(mktemp)"
  meta="$(curl_capture "$tmp" "${WEB_BASE_URL}/databuff/login")"
  rm -f "$tmp"
  code="$(awk '{print $1}' <<<"$meta")"
  t="$(awk '{print $2}' <<<"$meta")"
  [[ "$code" == "200" ]] || fail "${label}: login page HTTP ${code}"
  log "${label}: login page OK HTTP 200 time=${t}s"
}

login_token() {
  local tmp meta code token
  tmp="$(mktemp)"
  meta="$(curl_capture "$tmp" -X POST "${WEB_BASE_URL}/webapi/user/login" \
    -H 'Content-Type: application/json' \
    -d "{\"account\":\"${ADMIN_ACCOUNT}\",\"password\":\"${ADMIN_PASSWORD}\"}")"
  code="$(awk '{print $1}' <<<"$meta")"
  [[ "$code" == "200" ]] || fail "login HTTP ${code} body=$(cat "$tmp")"
  token="$(python3 -c '
import json,sys
d=json.loads(open(sys.argv[1]).read())
data=d.get("data") if isinstance(d,dict) else None
tok=(data.get("token") if isinstance(data,dict) else None) or (d.get("token") if isinstance(d,dict) else None) or ""
print(tok)
' "$tmp")"
  rm -f "$tmp"
  [[ -n "$token" ]] || fail "login missing token"
  printf '%s' "$token"
}

assert_login_api() {
  local label="$1"
  local tmp meta code time_total
  tmp="$(mktemp)"
  meta="$(curl_capture "$tmp" -X POST "${WEB_BASE_URL}/webapi/user/login" \
    -H 'Content-Type: application/json' \
    -d "{\"account\":\"${ADMIN_ACCOUNT}\",\"password\":\"${ADMIN_PASSWORD}\"}")"
  code="$(awk '{print $1}' <<<"$meta")"
  time_total="$(awk '{print $2}' <<<"$meta")"
  [[ "$code" == "200" ]] || fail "${label}: login API HTTP ${code} body=$(cat "$tmp")"
  rm -f "$tmp"
  log "${label}: login API OK time=${time_total}s"
}

assert_experts_ops() {
  local label="$1"
  local token="$2"
  local tmp meta code time_total has_ops
  tmp="$(mktemp)"
  meta="$(curl_capture "$tmp" -H "Authorization: Bearer ${token}" \
    "${WEB_BASE_URL}/webapi/api/v1/ai/experts")"
  code="$(awk '{print $1}' <<<"$meta")"
  time_total="$(awk '{print $2}' <<<"$meta")"
  [[ "$code" == "200" ]] || fail "${label}: experts HTTP ${code} body=$(head -c 300 "$tmp")"
  has_ops="$(python3 -c '
import json,sys
raw=open(sys.argv[1]).read()
d=json.loads(raw)
items=d if isinstance(d,list) else (d.get("data") or d.get("items") or d.get("experts") or [])
ok=any(isinstance(it,dict) and str(it.get("expertId",""))=="ops" for it in items) if isinstance(items,list) else False
print("1" if ok or ("\"expertId\":\"ops\"" in raw or "\"expertId\": \"ops\"" in raw) else "0")
' "$tmp")"
  rm -f "$tmp"
  [[ "$has_ops" == "1" ]] || fail "${label}: experts missing expertId=ops"
  log "${label}: AI experts OK (ops) time=${time_total}s"
}

assert_basic_all_services() {
  local label="$1"
  local token="$2"
  local expect_nonempty="$3"
  local tmp meta code time_total count
  tmp="$(mktemp)"
  meta="$(curl_capture "$tmp" -X POST "${WEB_BASE_URL}/webapi/service/basicAllServices" \
    -H "Authorization: Bearer ${token}" \
    -H 'Content-Type: application/json' \
    -d '{}')"
  code="$(awk '{print $1}' <<<"$meta")"
  time_total="$(awk '{print $2}' <<<"$meta")"
  [[ "$code" == "200" ]] || fail "${label}: basicAllServices HTTP ${code}"
  count="$(python3 -c '
import json,sys
d=json.loads(open(sys.argv[1]).read())
data=d.get("data") if isinstance(d,dict) else d
print(len(data) if isinstance(data,list) else (0 if data is None else 1))
' "$tmp")"
  rm -f "$tmp"
  if [[ "$expect_nonempty" == "1" ]]; then
    [[ "$count" -gt 0 ]] || fail "${label}: basicAllServices expected non-empty (got ${count})"
    log "${label}: basicAllServices non-empty count=${count} time=${time_total}s"
  else
    [[ "$count" -eq 0 ]] || fail "${label}: basicAllServices expected empty fail-fast (got ${count})"
    log "${label}: basicAllServices empty fail-fast time=${time_total}s"
  fi
}

assert_ai_login_sla() {
  local label="$1"
  local expect_services_nonempty="$2"
  local token
  assert_login_page "$label"
  assert_login_api "$label"
  token="$(login_token)"
  assert_experts_ops "$label" "$token"
  assert_basic_all_services "$label" "$token" "$expect_services_nonempty"
}

assert_web_logs_contain() {
  local label="$1"
  local since="$2"
  local pattern="$3"
  local logs
  logs="$(docker logs --since "$since" "$WEB_CONTAINER" 2>&1 || true)"
  echo "$logs" | grep -F -q "$pattern" \
    || fail "${label}: web logs since ${since} missing '${pattern}'"
  log "${label}: log auxiliary OK (${pattern})"
}

default_ops_host_hint() {
  if [[ -n "$OPS_HOST_HINT" ]]; then
    printf '%s' "$OPS_HOST_HINT"
    return 0
  fi
  cat <<EOF
Bash 工具在 Web 进程所在环境执行（Docker 部署时通常在 ai-apm-web 容器内）。
若当前 shell 没有 docker / 看不到宿主机容器，请用 ssh/sshpass 到宿主机执行恢复，例如：
  docker start ${DORIS_FE_CONTAINER} ${DORIS_BE_CONTAINER}
或进入安装目录后 compose/start 等价命令。必须执行命令并依据真实输出判断，直到
  curl -sS ${WEB_BASE_URL}/health
显示 doris 为 UP。不要只给手工建议。
EOF
}

build_ops_prompt() {
  if [[ -n "${OPS_PROMPT:-}" ]]; then
    printf '%s' "$OPS_PROMPT"
    return 0
  fi
  local hint
  hint="$(default_ops_host_hint)"
  cat <<EOF
【运行时 Doris 故障自愈验收】
我在本机运行了 DataBuff APM（Web ${WEB_BASE_URL}）。稳态下 Doris 容器被人为停止：
  ${DORIS_FE_CONTAINER} / ${DORIS_BE_CONTAINER}
当前 GET ${WEB_BASE_URL}/health 应为 status=UP 且 doris=UNAVAILABLE；Web 排障模式已开，登录与 AI 平台仍可用。

请你（运维专家 expertId=${OPS_EXPERT_ID}）使用 Bash 等工具亲自排查并恢复 Doris，使集群重新可用。
恢复动作须由你执行（docker start / compose up / 安装目录 start 等），不要只输出步骤让我去点。

环境提示：
${hint}

完成后用简短中文说明：做了什么命令、关键输出、以及 /health 是否已恢复。
EOF
}

check_llm_ready_or_fail() {
  local out rc
  set +e
  out="$(python3 "$OPS_CHAT_PY" --base-url "$WEB_BASE_URL" --account "$ADMIN_ACCOUNT" \
    --password "$ADMIN_PASSWORD" --check-ready-only --http-timeout "$API_MAX_TIME_SEC" 2>&1)"
  rc=$?
  set -e
  if [[ "$rc" -eq 0 ]]; then
    log "LLM ready=true (AI status)"
    return 0
  fi
  fail "LLM API Key / provider not ready. Configure Web → 配置 → 大模型 (GET /webapi/api/v1/config/ai/status ready=true). For health-only smoke (非发版有效门禁) set SKIP_OPS_EXPERT=1. detail=${out}"
}

step_baseline() {
  if [[ "$SKIP_BASELINE" == "1" ]]; then
    log "skip baseline (SKIP_BASELINE=1)"
    return 0
  fi
  log "=== S0 baseline (full stack) ==="
  container_running "$WEB_CONTAINER" || fail "web container ${WEB_CONTAINER} not running"
  container_running "$DORIS_FE_CONTAINER" || fail "Doris FE not running — start deploy/local (or install) first"
  container_running "$DORIS_BE_CONTAINER" || fail "Doris BE not running — start deploy/local (or install) first"
  assert_health "UP" "UP" "baseline"
  assert_ai_login_sla "baseline" "1"
  log "baseline PASS"
  maybe_stop baseline
}

step_outage() {
  if [[ "$SKIP_OUTAGE" == "1" ]]; then
    log "skip outage (SKIP_OUTAGE=1)"
    return 0
  fi
  log "=== S2 runtime outage inject ==="
  local since
  since="$(utc_now)"
  stop_doris_inject
  wait_for_doris_health "UNAVAILABLE" "$HEALTH_WAIT_SEC" "outage"
  assert_health "UP" "UNAVAILABLE" "outage"
  assert_ai_login_sla "outage" "0"
  assert_web_logs_contain "outage" "$since" "periodic probe failed"
  log "outage inject + AI/login still available PASS"
  maybe_stop outage
}

step_ops_recovery() {
  if [[ "$SKIP_OPS_RECOVERY" == "1" ]]; then
    log "skip ops recovery (SKIP_OPS_RECOVERY=1)"
    return 0
  fi

  if [[ "$SKIP_OPS_EXPERT" == "1" ]]; then
    GATE_MODE="smoke_invalid"
    warn "============================================================"
    warn "SKIP_OPS_EXPERT=1 → 仅 health/login 烟雾，非发版有效门禁"
    warn "发版门禁 B 必须：运维专家(ops)对话恢复 Doris（脚本禁止 docker start 冒充）"
    warn "============================================================"
    log "smoke_invalid complete (no ops expert recovery)"
    maybe_stop ops
    return 0
  fi

  log "=== S3 main path: ops expert recovery (expertId=${OPS_EXPERT_ID}) ==="
  check_llm_ready_or_fail

  local pid_before pid_after since prompt out rc session_id has_tool
  pid_before="$(web_pid)"
  [[ "$pid_before" != "0" && -n "$pid_before" ]] || fail "web PID invalid: ${pid_before}"
  since="$(utc_now)"
  prompt="$(build_ops_prompt)"

  log "submit ops chat → ${OPS_CHAT_PY} (evidence → ${OPS_EVIDENCE_FILE})"
  set +e
  out="$(python3 "$OPS_CHAT_PY" \
    --base-url "$WEB_BASE_URL" \
    --account "$ADMIN_ACCOUNT" \
    --password "$ADMIN_PASSWORD" \
    --expert-id "$OPS_EXPERT_ID" \
    --message "$prompt" \
    --follow-up "$OPS_FOLLOWUP" \
    --poll-interval "$OPS_POLL_INTERVAL_SEC" \
    --poll-timeout "$OPS_POLL_TIMEOUT_SEC" \
    --http-timeout 120 \
    --evidence-out "$OPS_EVIDENCE_FILE" 2>&1)"
  rc=$?
  set -e
  echo "$out" | tail -n 40
  [[ "$rc" -eq 0 ]] || fail "ops expert chat failed rc=${rc} (see above). No LLM key → configure AI provider or SKIP_OPS_EXPERT=1"

  session_id="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1]).get("sessionId",""))' "$out")"
  [[ -n "$session_id" ]] || fail "ops chat response missing sessionId: ${out}"
  log "ops sessionId=${session_id} (main evidence)"

  has_tool="$(python3 -c '
import json,sys
d=json.loads(sys.argv[1])
ev=(d.get("evidence") or {})
print("1" if ev.get("hasBashOrDockerEvidence") or ev.get("hasDockerStartOrComposeEvidence") else "0")
' "$out")"
  if [[ "$REQUIRE_OPS_TOOL_EVIDENCE" == "1" && "$has_tool" != "1" ]]; then
    fail "ops session lacks Bash/docker tool evidence (REQUIRE_OPS_TOOL_EVIDENCE=1). session=${session_id} evidence=${OPS_EVIDENCE_FILE}"
  fi
  log "ops session tool/command evidence OK"

  # Auxiliary: wait for monitor to observe recovery (expert should have started Doris)
  wait_for_doris_health "UP" "$OPS_RECOVERY_WAIT_SEC" "ops-recovery-auxiliary"
  NEED_DORIS_CLEANUP=0
  pid_after="$(web_pid)"
  [[ "$pid_after" == "$pid_before" ]] \
    || fail "ops recovery: web PID changed (${pid_before} → ${pid_after}); expected no web restart"
  log "auxiliary: web PID unchanged (${pid_before})"
  assert_health "UP" "UP" "ops-recovery-auxiliary"
  assert_web_logs_contain "ops-recovery-auxiliary" "$since" "Doris recovered"
  assert_web_logs_contain "ops-recovery-auxiliary" "$since" "Persistence recovery hydrate scheduled"
  assert_basic_all_services "ops-recovery-auxiliary" "$(login_token)" "1"

  log "ops expert recovery PASS (main=session ${session_id}; auxiliary=/health+logs)"
  maybe_stop ops
}

step_startup_probe_optional() {
  if [[ "$SKIP_STARTUP_PROBE" == "1" ]]; then
    log "skip startup probe (SKIP_STARTUP_PROBE=1; not part of ops main path)"
    return 0
  fi
  # Optional diagnostics only — still must not claim script docker start as ops recovery.
  log "=== optional S4 startup probe (diagnostic) ==="
  warn "S4 restarts web with Doris down; not a substitute for ops-expert recovery"
  stop_doris_inject
  sleep 2
  local since
  since="$(utc_now)"
  docker restart "$WEB_CONTAINER" >/dev/null
  wait_for_doris_health "UNAVAILABLE" 120 "startup"
  assert_health "UP" "UNAVAILABLE" "startup"
  assert_web_logs_contain "startup" "$since" "startup probe failed"
  assert_ai_login_sla "startup" "0"
  log "startup probe diagnostic PASS (Doris still down; cleanup trap may restore)"
}

main() {
  require_cmds
  log "repo=${REPO_ROOT}"
  log "WEB_BASE_URL=${WEB_BASE_URL} STOP_AFTER=${STOP_AFTER}"
  log "main path=运维专家(${OPS_EXPERT_ID}) chat recovery; /health is auxiliary"
  log "SKIP_OPS_EXPERT=${SKIP_OPS_EXPERT} REQUIRE_OPS_TOOL_EVIDENCE=${REQUIRE_OPS_TOOL_EVIDENCE}"

  step_baseline
  step_outage
  step_ops_recovery
  step_startup_probe_optional

  if [[ "$GATE_MODE" == "smoke_invalid" ]]; then
    warn "FINISHED as 非发版有效门禁 (SKIP_OPS_EXPERT=1). Release gate B requires ops expert recovery."
    exit 0
  fi
  log "gate B PASS: runtime outage → ops expert recovery (+ auxiliary /health+logs)"
}

main "$@"
