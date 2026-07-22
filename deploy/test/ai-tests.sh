#!/usr/bin/env bash
# AI 集成测试统一入口 — 跑全部 AI 相关用例
# （工具参数校验 / 接入格式 / 会话记忆 / 大脑异步路由）。
#
# 需要环境变量：
#   DEEPSEEK_API_KEY=sk-...   OpenAI Completions 格式；未设置则跳过会话记忆、异步路由，以及 OpenAI 接入格式用例
#   MINIMAX_API_KEY=...       Anthropic Messages 格式；未设置则跳过 Anthropic 接入格式用例
#
# Usage:
#   export DEEPSEEK_API_KEY=sk-...
#   export MINIMAX_API_KEY=...
#   ./ai-tests.sh
#   TEST_BASE_URL=http://127.0.0.1:27403 ./ai-tests.sh
#   TEST_BASE_URL=https://demo.databuff.ai ./ai-tests.sh
#   TEST_SKIP_AI_CHAT=1 ./ai-tests.sh              # 跳过工具参数校验
#   TEST_SKIP_AI_PROVIDER_FORMATS=1 ./ai-tests.sh  # 跳过接入格式
#   TEST_SKIP_AI_MEMORY=1 ./ai-tests.sh            # 跳过会话记忆
#   TEST_SKIP_AI_BRAIN_ASYNC=1 ./ai-tests.sh       # 跳过大脑异步路由
set -euo pipefail

TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
export TEST_BASE_URL="${TEST_BASE_URL:-http://127.0.0.1:${WEB_HTTP_PORT:-27403}}"

echo "[ai-tests] base=${TEST_BASE_URL}"
exec python3 "${TEST_DIR}/lib/run_ai_tests.py" "$@"
