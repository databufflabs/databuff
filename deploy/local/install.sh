#!/usr/bin/env bash
# 本地 Doris 重装：停止服务 → 删除历史数据 → 重新初始化并启动全栈
# 首次使用前需执行 ./build-jdk-images.sh 构建 JDK 开发镜像
#
# Usage:
#   ./install.sh
#
# Optional (透传给 start.sh):
#   SKIP_BUILD=1          复用已有 Maven 产物
#   START_SKIP_READY=1    不等待 health check

set -e

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