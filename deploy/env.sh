#!/usr/bin/env bash
# 部署配置：应用镜像统一 databuffhub/* 短名，通过离线包 docker load 使用。
# K8s 集群若需从镜像仓库拉取，由用户自行将 databuffhub/* 推到其仓库并改 manifest。

export RUNTIME_IMAGE_NAMESPACE="${RUNTIME_IMAGE_NAMESPACE:-databuffhub}"

# APM 应用版本（发版时只改这里）
export APM_VERSION=0.1.4

apm_refresh_image_refs() {
  export APM_INGEST_IMAGE="${RUNTIME_IMAGE_NAMESPACE}/ai-apm-ingest:${APM_VERSION}"
  export APM_WEB_IMAGE="${RUNTIME_IMAGE_NAMESPACE}/ai-apm-web:${APM_VERSION}"
  export APM_DEMO_IMAGE="${RUNTIME_IMAGE_NAMESPACE}/ai-apm-demo:${APM_VERSION}"
}
apm_refresh_image_pkg_bases() {
  export APM_IMAGES_PKG_BASE="${APM_IMAGES_PKG_BASE:-${APM_PKG_BASE%/}/${APM_VERSION}/images}"
  export APM_INFRA_IMAGES_PKG_BASE="${APM_INFRA_IMAGES_PKG_BASE:-${APM_PKG_BASE%/}/infra/images}"
}
apm_refresh_image_refs

export JDK_IMAGE="eclipse-temurin:17-jdk-jammy"
export LOCAL_JDK_IMAGE="${LOCAL_JDK_IMAGE:-databuff-local/jdk-dev:17-jammy}"
# 构建 / local 拉取 JDK 基础镜像的 registry 前缀（如 test.xxx.com/databuff，建议写入 shell profile）。
# 设置后从 ${JDK_REGISTRY}/eclipse-temurin:17-jdk-jammy 拉取并 tag 为 eclipse-temurin:17-jdk-jammy；未设置则直接 pull 短名。
export JDK_REGISTRY="${JDK_REGISTRY:-}"

export DORIS_FE_IMAGE=apache/doris:fe-4.1.1
export DORIS_BE_IMAGE=apache/doris:be-4.1.1
export ZOOKEEPER_IMAGE=bitnamilegacy/zookeeper:3.9

# 部署包下载地址（databuff-site nginx 对外提供，如 ai-apm-install.sh）
export APM_PKG_BASE="${APM_PKG_BASE:-http://192.168.50.140/databuff}"
# 安装完成摘要等面向用户的脚本下载地址（不暴露内部 CDN / 构建机地址）
export APM_PUBLIC_PKG_BASE="${APM_PUBLIC_PKG_BASE:-http://192.168.50.140/databuff}"
# 运行时按版本解析：${APM_PKG_BASE}/${APM_VERSION}/images；infra 固定 ${APM_PKG_BASE}/infra/images
export APM_INFRA_IMAGES_PKG_BASE="${APM_INFRA_IMAGES_PKG_BASE:-${APM_PKG_BASE%/}/infra/images}"

# 构建机 SCP 上传目标（databuff-site 静态目录，由 nginx 映射为 APM_PKG_BASE）
export APM_PKG_UPLOAD_HOST="${APM_PKG_UPLOAD_HOST:-192.168.50.140}"
export APM_PKG_UPLOAD_USER="${APM_PKG_UPLOAD_USER:-root}"
export APM_PKG_UPLOAD_PASS="${APM_PKG_UPLOAD_PASS:-Databuff@123}"
export APM_PKG_REMOTE_DIR="${APM_PKG_REMOTE_DIR:-/opt/databuff-site/databuff}"
