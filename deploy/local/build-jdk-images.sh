#!/usr/bin/env bash
# 构建本地 JDK 开发镜像（仅需执行一次；ingest/web/demo 共用，JAR 仍通过目录挂载）
#
# Usage:
#   ./build-jdk-images.sh

set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

chmod +x "${ROOT}/scripts/"*.sh 2>/dev/null || true

# shellcheck source=scripts/lib.sh
. "${ROOT}/scripts/lib.sh"
load_local_env
ensure_jdk_image
build_local_jdk_image

echo "[build-jdk-images] ready: ${LOCAL_JDK_IMAGE}"
