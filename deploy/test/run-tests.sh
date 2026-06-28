#!/usr/bin/env bash
# 应用性能集成测试（默认）或录制 expected 基线（--snapshot，维护者用）
#
# Usage:
#   ./run-tests.sh                  # 跑接口 + AI chat（已配置 API Key 时）
#   ./run-tests.sh --snapshot       # 将当前 API 响应写入 expected/*.json
#   TEST_SKIP_AI_CHAT=1 ./run-tests.sh
set -euo pipefail

TEST_DIR="$(cd "$(dirname "$0")" && pwd)"

export TEST_BASE_URL="${TEST_BASE_URL:-http://127.0.0.1:${WEB_HTTP_PORT:-27403}}"
export TEST_QUERY_WINDOW_MS="${TEST_QUERY_WINDOW_MS:-300000}"
export TEST_DEMO_SERVICE="${TEST_DEMO_SERVICE:-service-a}"

TEST_LIB_DIR="${TEST_DIR}/lib"
REPORT_DIR="${TEST_LIB_DIR}/reports"

if [[ "${1:-}" == "--snapshot" || "${1:-}" == "snapshot" ]]; then
  shift || true
  export TEST_WARMUP_SECONDS="${TEST_WARMUP_SECONDS:-0}"
  echo "[run-tests] snapshot mode base=${TEST_BASE_URL}"
  python3 "${TEST_LIB_DIR}/run_tests.py" --snapshot "$@"
  exit $?
fi

export TEST_WARMUP_SECONDS="${TEST_WARMUP_SECONDS:-240}"

echo "[run-tests] base=${TEST_BASE_URL}"
python3 "${TEST_LIB_DIR}/run_tests.py" --report-dir "${REPORT_DIR}" "$@"
status=$?

if [ -f "${REPORT_DIR}/report-latest.html" ]; then
  echo "[run-tests] report: ${REPORT_DIR}/report-latest.html"
fi

exit "${status}"
