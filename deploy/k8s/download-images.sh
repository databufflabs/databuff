#!/usr/bin/env bash
# 下载 K8s 部署所需离线镜像包并导入本地（ingest / web / demo / Doris / ZooKeeper，镜像名 databuffhub/*）。
#
# 用法:
#   curl -fsSL https://databuff.ai/databuff/ai-apm-k8s-download-images.sh | bash
#   export IMAGE_LOAD_CMD=ctr; curl -fsSL https://databuff.ai/databuff/ai-apm-k8s-download-images.sh | bash
#   ./deploy/k8s/download-images.sh
#   APM_VERSION=0.1.4 IMAGE_LOAD_CMD=ctr ./deploy/k8s/download-images.sh   # k3s/containerd
#
# 环境变量:
#   APM_PKG_BASE         部署包下载地址
#   APM_IMAGES_PKG_BASE  版本镜像包目录 (默认 ${APM_PKG_BASE}/${APM_VERSION}/images)
#   APM_INFRA_IMAGES_PKG_BASE  infra 镜像包目录 (默认 ${APM_PKG_BASE}/infra/images)
#   APM_VERSION          APM 版本（默认读 VERSION 或 0.1.4）
#   IMAGE_LOAD_CMD       docker（默认）或 ctr（k3s containerd）

set -euo pipefail

cd /opt 2>/dev/null || cd "${TMPDIR:-/tmp}" 2>/dev/null || true

ROOT="$(cd "$(dirname "$0")" && pwd)"

if [[ -f "${ROOT}/env.sh" ]]; then
  # shellcheck disable=SC1091
  source "${ROOT}/env.sh"
elif [[ -f "${ROOT}/../env.sh" ]]; then
  # shellcheck disable=SC1091
  source "${ROOT}/../env.sh"
fi

if [[ -f "${ROOT}/VERSION" ]]; then
  APM_VERSION="$(tr -d '[:space:]' <"${ROOT}/VERSION")"
  export APM_VERSION
fi
if declare -F apm_refresh_image_refs >/dev/null 2>&1; then
  apm_refresh_image_refs
fi
if declare -F apm_refresh_image_pkg_bases >/dev/null 2>&1; then
  apm_refresh_image_pkg_bases
fi

if [[ -f "${ROOT}/scripts/image-pkg.sh" ]]; then
  # shellcheck source=scripts/image-pkg.sh
  source "${ROOT}/scripts/image-pkg.sh"
else
  # shellcheck source=../docker/scripts/image-pkg.sh
  source "${ROOT}/../docker/scripts/image-pkg.sh"
fi

echo "[download-images] 加载离线镜像 (k8s)"
load_k8s_stack_images
echo "[download-images] 全部完成"
