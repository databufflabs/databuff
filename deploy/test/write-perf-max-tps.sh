#!/usr/bin/env bash
# 写入链路最高稳定 TPS — otelgen 阶梯加压 + Doris 入库金标准
#
#   ./write-perf-max-tps.sh find-max    # 找最高稳定 TPS（默认每档 60s）
#   ./write-perf-max-tps.sh hold        # 在 find-max 结果上长稳态（默认 10m）
#
# 快速 smoke（迭代）:
#   STEP_SECONDS=30 RATE_START=10 RATE_MAX=200 ./write-perf-max-tps.sh find-max
#
# 发版签字（10m 稳态）:
#   ./write-perf-max-tps.sh find-max && PERF_WRITE_DURATION=10m ./write-perf-max-tps.sh hold
#
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PYTHON="${PYTHON:-python3.9}"
export OUTPUT_DIR="${OUTPUT_DIR:-/tmp/write-perf}"
mkdir -p "$OUTPUT_DIR"
exec "$PYTHON" "${SCRIPT_DIR}/lib/write_perf_max_tps.py" "$@"
