#!/usr/bin/env bash
# AI 集成测试入口 — 四套件「分别并行」（禁止默认串行叠跑）。
#
# 套件：
#   chat      工具参数校验
#   formats   OpenAI / Anthropic 接入格式
#   memory    会话记忆
#   brain     大脑异步路由
#
# 需要环境变量：
#   DEEPSEEK_API_KEY=sk-...   OpenAI Completions；未设置则跳过 memory/brain 与 OpenAI formats
#   MINIMAX_API_KEY=...       Anthropic Messages；未设置则跳过 Anthropic formats
#
# Usage:
#   export DEEPSEEK_API_KEY=sk-...
#   export MINIMAX_API_KEY=...
#   ./ai-tests.sh                          # 默认：四套件分进程并行
#   ./ai-tests.sh --suite memory           # 只跑会话记忆
#   ./ai-tests.sh --suite brain
#   AI_TESTS_PARALLEL=0 ./ai-tests.sh      # 调试：单进程串行（发布门禁禁止）
#   TEST_SKIP_AI_CHAT=1 ./ai-tests.sh
#   TEST_SKIP_AI_PROVIDER_FORMATS=1 ./ai-tests.sh
#   TEST_SKIP_AI_MEMORY=1 ./ai-tests.sh
#   TEST_SKIP_AI_BRAIN_ASYNC=1 ./ai-tests.sh
set -euo pipefail

TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
export TEST_BASE_URL="${TEST_BASE_URL:-http://127.0.0.1:${WEB_HTTP_PORT:-27403}}"
export AI_TESTS_PARALLEL="${AI_TESTS_PARALLEL:-1}"

SUITE="all"
SERIAL=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --suite)
      SUITE="${2:?--suite needs value}"
      shift 2
      ;;
    --serial)
      SERIAL=1
      shift
      ;;
    *)
      echo "[ai-tests] unknown arg: $1" >&2
      exit 2
      ;;
  esac
done

PARALLEL_PROC=1
if [[ "${SERIAL}" == "1" || "${AI_TESTS_PARALLEL}" == "0" ]]; then
  PARALLEL_PROC=0
fi
echo "[ai-tests] base=${TEST_BASE_URL} suite=${SUITE} parallel_process=${PARALLEL_PROC}"

# Single suite → one process
if [[ "${SUITE}" != "all" ]]; then
  if [[ "${SERIAL}" == "1" ]]; then
    exec python3 "${TEST_DIR}/lib/run_ai_tests.py" --suite "${SUITE}" --serial
  fi
  exec python3 "${TEST_DIR}/lib/run_ai_tests.py" --suite "${SUITE}"
fi

# all + serial (debug)
if [[ "${PARALLEL_PROC}" == "0" ]]; then
  echo "[ai-tests] WARNING: serial mode (debug only; release gate must use parallel)" >&2
  exec python3 "${TEST_DIR}/lib/run_ai_tests.py" --suite all --serial
fi

# Default: four separate processes in parallel (true isolation)
LOG_DIR="${TMPDIR:-/tmp}/ai-tests-$$"
mkdir -p "${LOG_DIR}"

for s in chat formats memory brain; do
  (
    set +e
    python3 "${TEST_DIR}/lib/run_ai_tests.py" --suite "${s}" \
      >"${LOG_DIR}/${s}.log" 2>&1
    echo $? >"${LOG_DIR}/${s}.exit"
  ) &
  echo $! >"${LOG_DIR}/${s}.pid"
  echo "[ai-tests] started suite=${s} pid=$(cat "${LOG_DIR}/${s}.pid") log=${LOG_DIR}/${s}.log"
done

fail=0
for s in chat formats memory brain; do
  pid="$(cat "${LOG_DIR}/${s}.pid")"
  wait "${pid}" || true
  ec="$(cat "${LOG_DIR}/${s}.exit" 2>/dev/null || echo 1)"
  echo "---------- suite=${s} exit=${ec} ----------"
  cat "${LOG_DIR}/${s}.log" || true
  if [[ "${ec}" != "0" ]]; then
    fail=1
  fi
done

echo "[ai-tests] parallel summary log_dir=${LOG_DIR} overall_exit=${fail}"
exit "${fail}"
