#!/usr/bin/env bash
# AI 集成测试入口 — 核心套件「分别并行」（禁止默认串行叠跑）。
#
# 套件：
#   chat       工具参数校验
#   formats    OpenAI / Anthropic 接入格式
#   memory     会话记忆
#   brain      大脑异步路由
#   modelfail  模型失败可见性（单专家/多专家；终态必须含 error；all 时在核心套件后串行）
#
# 需要环境变量：
#   DEEPSEEK_API_KEY=sk-...   OpenAI Completions；默认 provider 门控 memory/brain/modelfail
#   MINIMAX_API_KEY=...       Anthropic Messages；AI_TEST_PROVIDER=minimax 时门控
#   AI_TEST_PROVIDER=deepseek|minimax   chat/memory/brain/modelfail 实际调用的模型（默认 deepseek）
#   AI_TEST_MODEL=...                   可选覆盖模型名
#
# Usage:
#   export DEEPSEEK_API_KEY=sk-...
#   export MINIMAX_API_KEY=...
#   ./ai-tests.sh                          # 默认：核心套件分进程并行，再串行 modelfail
#   AI_TEST_PROVIDER=minimax ./ai-tests.sh # 走 MiniMax
#   ./ai-tests.sh --suite memory           # 只跑会话记忆
#   ./ai-tests.sh --suite brain
#   ./ai-tests.sh --suite modelfail        # 只跑模型失败可见性
#   AI_TESTS_PARALLEL=0 ./ai-tests.sh      # 调试：单进程串行（发布门禁禁止）
#   TEST_SKIP_AI_CHAT=1 ./ai-tests.sh
#   TEST_SKIP_AI_PROVIDER_FORMATS=1 ./ai-tests.sh
#   TEST_SKIP_AI_MEMORY=1 ./ai-tests.sh
#   TEST_SKIP_AI_BRAIN_ASYNC=1 ./ai-tests.sh
#   TEST_SKIP_AI_MODEL_FAILURE=1 ./ai-tests.sh
set -euo pipefail

TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
export TEST_BASE_URL="${TEST_BASE_URL:-http://127.0.0.1:${WEB_HTTP_PORT:-27403}}"
export AI_TESTS_PARALLEL="${AI_TESTS_PARALLEL:-1}"
export AI_TEST_PROVIDER="${AI_TEST_PROVIDER:-deepseek}"

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
echo "[ai-tests] base=${TEST_BASE_URL} suite=${SUITE} parallel_process=${PARALLEL_PROC} provider=${AI_TEST_PROVIDER}"

# Single suite → one process
if [[ "${SUITE}" != "all" ]]; then
  exec python3 "${TEST_DIR}/lib/run_ai_tests.py" --suite "${SUITE}"
fi

# all + serial (debug)
if [[ "${PARALLEL_PROC}" == "0" ]]; then
  echo "[ai-tests] WARNING: serial mode (debug only; release gate must use parallel)" >&2
  exec python3 "${TEST_DIR}/lib/run_ai_tests.py" --suite all
fi

# Default: core suites in separate processes, then modelfail serially
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

echo "---------- suite=modelfail (serial after core) ----------"
set +e
python3 "${TEST_DIR}/lib/run_ai_tests.py" --suite modelfail \
  >"${LOG_DIR}/modelfail.log" 2>&1
ec=$?
set -e
echo "${ec}" >"${LOG_DIR}/modelfail.exit"
cat "${LOG_DIR}/modelfail.log" || true
echo "---------- suite=modelfail exit=${ec} ----------"
if [[ "${ec}" != "0" ]]; then
  fail=1
fi

echo "[ai-tests] parallel summary log_dir=${LOG_DIR} overall_exit=${fail}"
exit "${fail}"
