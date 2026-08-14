#!/usr/bin/env bash
# AI 集成测试入口 — 核心套件「分别并行」（禁止默认串行叠跑）。
#
# 目标环境：仅本地 deploy/local（127.0.0.1:${WEB_HTTP_PORT:-27403}）。
# 禁止指向 113/110 等外部机；未启动时请先：
#   cd deploy/local && ./install.sh          # 干净重装
#   # 或：SKIP_BUILD=1 ./start.sh            # 已有 jar 时快速拉起
#
# 套件：
#   chat       工具参数校验
#   formats    OpenAI / Anthropic 接入格式
#   memory     会话记忆
#   brain      大脑异步路由
#   mcp        远程 SSE / Streamable HTTP MCP + header 认证（走 LLM 对话，需要 API Key）
#   modelfail  模型失败可见性（单专家/多专家；终态必须含 error；all 时在核心套件后串行）
#
# 需要环境变量（可写在 ~/.zshrc）：
#   DEEPSEEK_API_KEY=sk-...   OpenAI Completions
#   MINIMAX_API_KEY=...       Anthropic Messages
#   OPENCODE_API_KEY=...      OpenCode Go（可选：未设置时自动读 opencode CLI 的 auth.json）
#   AI_TEST_PROVIDER=deepseek|minimax|opencode
#       选择本轮实际调用的模型（chat/formats/memory/brain/modelfail 均按此切换；默认 deepseek）
#   AI_TEST_MODEL=...         可选覆盖模型名
#
# Usage:
#   # ~/.zshrc 已 export AI_TEST_PROVIDER / 对应 *_API_KEY 后直接：
#   ./ai-tests.sh                          # 核心套件分进程并行，再串行 modelfail
#   AI_TEST_PROVIDER=minimax ./ai-tests.sh # 临时覆盖走 MiniMax
#   AI_TEST_PROVIDER=deepseek ./ai-tests.sh
#   AI_TEST_PROVIDER=opencode ./ai-tests.sh # 走 OpenCode Go 套餐（无需 OPENCODE_API_KEY 也可，自动读 auth.json）
#   ./ai-tests.sh --suite memory           # 只跑会话记忆
#   ./ai-tests.sh --suite brain
#   ./ai-tests.sh --suite mcp
#   ./ai-tests.sh --suite modelfail        # 只跑模型失败可见性
#   AI_TESTS_PARALLEL=0 ./ai-tests.sh      # 调试：单进程串行（发布门禁禁止）
#   AI_TESTS_SUITE_CONCURRENCY=1           # 核心套件同时跑几个进程（默认 1，降速限）
#   TEST_AI_CHAT_MAX_WORKERS=1             # chat 题内并发（默认 1）
#   TEST_AI_BRAIN_MAX_WORKERS=1            # brain 用例内并发（默认 1）
#   TEST_SKIP_AI_CHAT=1 ./ai-tests.sh
#   TEST_SKIP_AI_PROVIDER_FORMATS=1 ./ai-tests.sh
#   TEST_SKIP_AI_MEMORY=1 ./ai-tests.sh
#   TEST_SKIP_AI_BRAIN_ASYNC=1 ./ai-tests.sh
#   TEST_SKIP_AI_MCP=1 ./ai-tests.sh
#   TEST_SKIP_AI_MODEL_FAILURE=1 ./ai-tests.sh
set -euo pipefail

TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${TEST_DIR}/../.." && pwd)"
LOCAL_WEB_PORT="${WEB_HTTP_PORT:-27403}"
LOCAL_BASE_URL="http://127.0.0.1:${LOCAL_WEB_PORT}"

# 强制本地：忽略指向外部机的 TEST_BASE_URL
if [[ -n "${TEST_BASE_URL:-}" ]]; then
  case "${TEST_BASE_URL}" in
    http://127.0.0.1:*|http://localhost:*|http://[::1]:*)
      ;;
    *)
      echo "[ai-tests] refusing non-local TEST_BASE_URL=${TEST_BASE_URL}" >&2
      echo "[ai-tests] AI tests only target deploy/local (${LOCAL_BASE_URL})" >&2
      echo "[ai-tests] start local: cd ${REPO_ROOT}/deploy/local && ./install.sh" >&2
      exit 2
      ;;
  esac
fi
export TEST_BASE_URL="${LOCAL_BASE_URL}"
export AI_TESTS_PARALLEL="${AI_TESTS_PARALLEL:-1}"
# 核心套件进程并发上限（默认 1，避免 MiniMax/DeepSeek 429）
export AI_TESTS_SUITE_CONCURRENCY="${AI_TESTS_SUITE_CONCURRENCY:-1}"
# 套件内 LLM 并发默认压低
export TEST_AI_CHAT_MAX_WORKERS="${TEST_AI_CHAT_MAX_WORKERS:-1}"
export TEST_AI_BRAIN_MAX_WORKERS="${TEST_AI_BRAIN_MAX_WORKERS:-1}"
export AI_TEST_PROVIDER="${AI_TEST_PROVIDER:-deepseek}"
case "${AI_TEST_PROVIDER}" in
  deepseek|minimax|opencode) ;;
  *)
    echo "[ai-tests] unsupported AI_TEST_PROVIDER=${AI_TEST_PROVIDER}; expected deepseek|minimax|opencode" >&2
    exit 2
    ;;
esac

# 本地未就绪则直接失败并提示 install/start（任意 HTTP 响应码均算可达）
_http_code="$(curl -s -o /dev/null -w '%{http_code}' --connect-timeout 2 --max-time 5 "${TEST_BASE_URL}/" || true)"
if [[ "${_http_code}" == "000" || -z "${_http_code}" ]]; then
  echo "[ai-tests] local databuff not reachable at ${TEST_BASE_URL}" >&2
  echo "[ai-tests] start it first:" >&2
  echo "  cd ${REPO_ROOT}/deploy/local && ./install.sh" >&2
  echo "  # or: SKIP_BUILD=1 ./start.sh" >&2
  exit 2
fi

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
SUITE_CONCURRENCY="${AI_TESTS_SUITE_CONCURRENCY:-1}"
if ! [[ "${SUITE_CONCURRENCY}" =~ ^[1-9][0-9]*$ ]]; then
  echo "[ai-tests] AI_TESTS_SUITE_CONCURRENCY must be positive int, got ${SUITE_CONCURRENCY}" >&2
  exit 2
fi
echo "[ai-tests] base=${TEST_BASE_URL} suite=${SUITE} parallel_process=${PARALLEL_PROC} suite_concurrency=${SUITE_CONCURRENCY} chat_workers=${TEST_AI_CHAT_MAX_WORKERS} brain_workers=${TEST_AI_BRAIN_MAX_WORKERS} provider=${AI_TEST_PROVIDER}"

# Single suite → one process
if [[ "${SUITE}" != "all" ]]; then
  exec python3 "${TEST_DIR}/lib/run_ai_tests.py" --suite "${SUITE}"
fi

# all + serial (debug)
if [[ "${PARALLEL_PROC}" == "0" ]]; then
  echo "[ai-tests] WARNING: serial mode (debug only; release gate must use parallel)" >&2
  exec python3 "${TEST_DIR}/lib/run_ai_tests.py" --suite all
fi

# Default: core suites in separate processes (bounded concurrency), then modelfail serially
LOG_DIR="${TMPDIR:-/tmp}/ai-tests-$$"
mkdir -p "${LOG_DIR}"

CORE_SUITES=(chat formats memory brain mcp)
active_pids=()
for s in "${CORE_SUITES[@]}"; do
  # bash 3.2（macOS）无 wait -n；轮询已结束进程再开新套件
  while (( ${#active_pids[@]} >= SUITE_CONCURRENCY )); do
    still=()
    for pid in "${active_pids[@]}"; do
      if kill -0 "${pid}" 2>/dev/null; then
        still+=("${pid}")
      fi
    done
    active_pids=("${still[@]+"${still[@]}"}")
    if (( ${#active_pids[@]} >= SUITE_CONCURRENCY )); then
      sleep 1
    fi
  done
  (
    set +e
    python3 "${TEST_DIR}/lib/run_ai_tests.py" --suite "${s}" \
      >"${LOG_DIR}/${s}.log" 2>&1
    echo $? >"${LOG_DIR}/${s}.exit"
  ) &
  echo $! >"${LOG_DIR}/${s}.pid"
  active_pids+=("$(cat "${LOG_DIR}/${s}.pid")")
  echo "[ai-tests] started suite=${s} pid=$(cat "${LOG_DIR}/${s}.pid") log=${LOG_DIR}/${s}.log"
done

fail=0
for s in "${CORE_SUITES[@]}"; do
  pid="$(cat "${LOG_DIR}/${s}.pid")"
  wait "${pid}" 2>/dev/null || true
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
