#!/usr/bin/env bash
# AI 会话记忆集成测试（需 DEEPSEEK_API_KEY；未设置则跳过退出 0）
#
# Usage:
#   export DEEPSEEK_API_KEY=sk-...
#   ./ai-session-memory.sh
#   TEST_BASE_URL=http://127.0.0.1:27403 ./ai-session-memory.sh
set -euo pipefail

TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
export TEST_BASE_URL="${TEST_BASE_URL:-http://127.0.0.1:${WEB_HTTP_PORT:-27403}}"

if [[ -z "${DEEPSEEK_API_KEY:-}" ]]; then
  echo "[ai-session-memory] skip: set DEEPSEEK_API_KEY to enable"
  exit 0
fi

echo "[ai-session-memory] base=${TEST_BASE_URL}"
exec python3 "${TEST_DIR}/lib/run_ai_session_memory.py"
