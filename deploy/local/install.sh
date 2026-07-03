#!/usr/bin/env bash
# 本地 Doris 重装：停止服务 → 删除历史数据 → 重新初始化并启动全栈
#
# Usage:
#   ./install.sh
#
# Optional (透传给 start.sh):
#   SKIP_BUILD=1          复用已有 Maven 产物
#   START_SKIP_READY=1    不等待 health check

set -e

# 检查 AVX2 支持
if ! cat /proc/cpuinfo | grep -q avx2; then
  echo "Doris uses AVX2 vectorization to accelerate queries. A machine that supports the AVX2 instruction set is recommended. For more information, please visit: https://doris.apache.org/docs/dev/install/preparation/env-checking"
  exit 1
fi

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

chmod +x "${ROOT}/scripts/"*.sh 2>/dev/null || true

# shellcheck source=scripts/compose-env.sh
. "${ROOT}/scripts/compose-env.sh"

echo "[install] stopping local stack"
compose_down

echo "[install] removing doris data"
rm -rf "${ROOT}/data/fe-meta" "${ROOT}/data/be-storage"

echo "[install] starting fresh install"
exec "${ROOT}/start.sh"