#!/usr/bin/env bash
# 升级前后历史数据可查 — 快照 / 校验 wrapper
#
#   ./verify-upgrade-data-retention.sh snapshot   # 升级前（demo 预热后）
#   ./verify-upgrade-data-retention.sh verify     # 升级后
#
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PYTHON="${PYTHON:-python3.9}"
exec "$PYTHON" "${SCRIPT_DIR}/lib/verify_upgrade_data_retention.py" "$@"
