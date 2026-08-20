#!/usr/bin/env bash
# 验证集成测试环境已正常启动。失败则禁止跑案例。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=scripts/compose-env.sh
. "${ROOT}/scripts/compose-env.sh"
# shellcheck source=../docker/scripts/runtime.sh
. "${ROOT}/../docker/scripts/runtime.sh"

BASE="http://127.0.0.1:${WEB_HTTP_PORT:-27403}"
USER="${APM_SECURITY_SEED_USERNAME:-admin}"
PASS="${APM_SECURITY_SEED_PASSWORD:-}"
if [ -z "$PASS" ]; then
  PASS="$(apm_seed_password)"
fi

fail=0
say() { printf '[env-check] %s\n' "$*"; }
bad() { say "FAIL: $*"; fail=1; }

say "1/4 containers"
for svc in "${WEB_SERVICE:-ai-apm-web}" "${INGEST_SERVICE:-ai-apm-ingest}" "${DEMO_SERVICE:-ai-apm-demo}"; do
  if docker inspect -f '{{.State.Running}}' "$svc" 2>/dev/null | grep -q true; then
    say "$svc running"
  else
    bad "$svc not running"
  fi
done

say "2/4 health ${BASE}"
health="$(curl -sS --connect-timeout 3 --max-time 8 "${BASE}/health" 2>/dev/null || true)"
if printf '%s' "$health" | grep -q '"status":"UP"'; then
  say "web UP"
else
  bad "web health not UP: ${health:-empty}"
fi

say "3/4 login"
login_json="$(curl -sS --connect-timeout 3 --max-time 8 \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"${USER}\",\"password\":\"${PASS}\"}" \
  "${BASE}/webapi/api/v1/auth/login" 2>/dev/null || true)"
token="$(printf '%s' "$login_json" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("token",""))' 2>/dev/null || true)"
if [ -n "$token" ]; then
  say "login ok"
else
  bad "login failed"
fi

say "4/4 demo service-a"
now_ms="$(($(date +%s) * 1000))"
from_ms="$((now_ms - 3600 * 1000))"
list_json=""
if [ -n "$token" ]; then
  list_json="$(curl -sS --connect-timeout 3 --max-time 12 \
    -H "Authorization: Bearer ${token}" \
    -H 'Content-Type: application/json' \
    -d "{\"from\":${from_ms},\"to\":${now_ms},\"start\":${from_ms},\"end\":${now_ms}}" \
    "${BASE}/webapi/service/list" 2>/dev/null || true)"
fi
if printf '%s' "$list_json" | grep -q 'service-a'; then
  say "service-a visible"
else
  bad "service-a not in service/list"
fi

if [ "${fail}" -ne 0 ]; then
  say "environment has problems. do not run cases."
  exit 1
fi
say "environment ok. cases may run."
exit 0
