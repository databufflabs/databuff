#!/usr/bin/env bash
# 编译 jar → 构建 databuffhub/* 短名镜像 → 导出 amd/arm 离线包并上传。
#
# Usage:
#   ./deploy/images/build-images.sh
#
# OpenJDK 基础镜像：openjdk:17.0.2-jdk（无 databuff/ 前缀）。
# 构建时 OPENJDK_REGISTRY（如 test.xxx.com/databuff）写入 shell profile，
# Dockerfile FROM 从该仓库拉取；需先 docker login ${OPENJDK_REGISTRY%%/*}。
#
# Optional:
#   SKIP_BUILD=1        复用已有 Maven 产物
#   SKIP_PKG_UPLOAD=1   跳过上传，仅保留本机离线包
#   IMAGE_PLATFORMS=linux/amd64,linux/arm64
#   BUILDX_PROGRESS=plain
#   APM_BUILD_DIST      本机保留 stack 包目录（默认 deploy/images/dist）

source "$(cd "$(dirname "$0")" && pwd)/scripts/lib.sh"

RELEASE_VERSION="$(resolve_release_version)"
INGEST_JAR="$(ingest_jar_path)"
WEB_JAR="$(web_jar_path)"
DEMO_JAR="$(demo_jar_path)"

ensure_command mvn

mvn_package_modules
mvn_package_demo

for f in "$INGEST_JAR" "$WEB_JAR" "$DEMO_JAR"; do
  if [[ ! -f "$f" ]]; then
    echo "[build-images] missing artifact: $f" >&2
    exit 1
  fi
done

publish_apm_images "$RELEASE_VERSION" "$INGEST_JAR" "$WEB_JAR" "$DEMO_JAR"
publish_version_manifest "$RELEASE_VERSION"

cat <<EOF

[build-images] done
  Version  : ${RELEASE_VERSION}
  Images   : $(ingest_image_ref "$RELEASE_VERSION")
             $(web_image_ref "$RELEASE_VERSION")
             $(demo_image_ref "$RELEASE_VERSION")
  Arch     : $(image_platforms)
  StackPkg : $(version_images_pkg_base_url "$RELEASE_VERSION")/ai-apm-stack-${RELEASE_VERSION}-<arch>.tar.gz
  LocalPkg : $(local_images_pkg_dir "$RELEASE_VERSION")/ai-apm-stack-${RELEASE_VERSION}-<arch>.tar.gz

EOF
