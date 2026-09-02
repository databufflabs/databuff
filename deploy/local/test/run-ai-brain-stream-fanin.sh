#!/usr/bin/env bash
# Real-model regression for brain stream-only reply and child-agent fan-in semantics.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO_ROOT="$(cd "${ROOT}/../.." && pwd)"

export TEST_BASE_URL="${TEST_BASE_URL:-http://127.0.0.1:27403}"
export AI_TEST_PROVIDER="${AI_TEST_PROVIDER:-deepseek}"

"${ROOT}/env-check.sh"
exec python3 "${REPO_ROOT}/deploy/local/test/ai_brain_stream_fanin_test.py"
