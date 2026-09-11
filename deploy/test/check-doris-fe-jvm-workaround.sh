#!/usr/bin/env bash
# Keep every Doris FE deployment entrypoint protected from the JDK cgroup v2 crash.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
FLAG='-XX:-UseContainerSupport'
INSERT='JAVA_OPTS_FOR_JDK_17="-XX:-UseContainerSupport '
FILES=(
  "deploy/docker/docker-compose.yml"
  "deploy/docker/docker-compose.legacy.yml"
  "deploy/local/docker-compose.yml"
  "deploy/k8s/manifests/doris.yaml"
)

for relative_path in "${FILES[@]}"; do
  file="${ROOT}/${relative_path}"
  count="$(grep -F -c -- "$INSERT" "$file" || true)"
  if [[ "$count" != "1" ]]; then
    echo "FAIL: ${relative_path} must insert ${FLAG} exactly once (found ${count})" >&2
    exit 1
  fi
  if ! grep -F -q -- "grep -q -- '$FLAG'" "$file"; then
    echo "FAIL: ${relative_path} does not guard the JVM option patch" >&2
    exit 1
  fi
done

sample_conf="$(mktemp)"
trap 'rm -f "$sample_conf"' EXIT
printf '%s\n' 'JAVA_OPTS_FOR_JDK_17="-Xms8192m -Xmx8192m"' >"$sample_conf"

for _ in 1 2; do
  grep -q -- "$FLAG" "$sample_conf" \
    || sed -i.bak 's/^JAVA_OPTS_FOR_JDK_17="/JAVA_OPTS_FOR_JDK_17="-XX:-UseContainerSupport /' "$sample_conf"
  rm -f "${sample_conf}.bak"
done

count="$(grep -o -- "$FLAG" "$sample_conf" | wc -l | tr -d ' ')"
if [[ "$count" != "1" ]]; then
  echo "FAIL: FE JVM workaround is not idempotent" >&2
  exit 1
fi

echo "PASS: Doris FE JVM workaround is present and idempotent"
