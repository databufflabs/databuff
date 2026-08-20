#!/usr/bin/env bash
# 拉起集成测试整套（产品栈 + demo）。不清库。
# 干净重装用 install.sh。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
echo "[env-up] starting stack + demo"
"${ROOT}/start.sh"
"${ROOT}/env-check.sh"
