#!/usr/bin/env bash
# 兼容入口：仅跑 AI 会话记忆套件（发布门禁请用 ai-tests.sh 四分套件并行）。
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
exec "${TEST_DIR}/ai-tests.sh" --suite memory "$@"
