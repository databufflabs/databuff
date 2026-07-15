#!/usr/bin/env bash
# 应用性能集成测试 — open-source release gate C (API regression).
#
# Release gates A/B/C are complementary; none substitutes for another:
#   A: deploy/test/doris-failover-e2e.sh          — install-time Doris failure
#   B: deploy/test/doris-runtime-failover-e2e.sh — runtime outage → ops expert
#   C (this script): API regression
# Ops docs: docs/运维参考/Docker运维.md 「发布验收 / Release gate」
#
# Usage:
#   ./run-tests.sh                  # 跑接口 + AI chat（已配置 API Key 时）
#   TEST_SKIP_AI_CHAT=1 ./run-tests.sh
set -euo pipefail

TEST_DIR="$(cd "$(dirname "$0")" && pwd)"

export TEST_BASE_URL="${TEST_BASE_URL:-http://127.0.0.1:${WEB_HTTP_PORT:-27403}}"
export TEST_QUERY_WINDOW_MS="${TEST_QUERY_WINDOW_MS:-300000}"
export TEST_DEMO_SERVICE="${TEST_DEMO_SERVICE:-service-a}"

TEST_LIB_DIR="${TEST_DIR}/lib"
REPORT_DIR="${TEST_LIB_DIR}/reports"

if [[ "${1:-}" == "--snapshot" || "${1:-}" == "snapshot" ]]; then
  echo "[run-tests] ERROR: --snapshot is disabled; expected/*.json must not be auto-overwritten." >&2
  echo "[run-tests] Edit expected files manually (use json_assert matchers like \$range)." >&2
  exit 1
fi

export TEST_WARMUP_SECONDS="${TEST_WARMUP_SECONDS:-240}"

echo "[run-tests] base=${TEST_BASE_URL}"
python3 "${TEST_LIB_DIR}/run_tests.py" --report-dir "${REPORT_DIR}" "$@"
status=$?

if [ -f "${REPORT_DIR}/report-latest.html" ]; then
  echo "[run-tests] report: ${REPORT_DIR}/report-latest.html"
fi

exit "${status}"
