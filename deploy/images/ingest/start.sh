#!/bin/sh
cd "$(dirname "$0")"

# K8s 集群模式：StatefulSet 按 Pod 序号解析 ingest-N；Deployment 使用 Pod 名作为唯一 node-id。
# 成员 endpoint 由 Java 进程根据 POD_IP + gRPC 端口自动推导并注册到 ZooKeeper。
if [ "${INGEST_CLUSTER_ENABLED:-false}" = "true" ]; then
  host="${HOSTNAME:-$(hostname)}"
  ord="${host##*-}"
  case "$ord" in
    ''|*[!0-9]*)
      resolved_node_id="$host"
      ;;
    *)
      resolved_node_id="ingest-$((ord + 1))"
      ;;
  esac
  if [ "${INGEST_NODE_ID:-auto}" = "auto" ] || [ -z "${INGEST_NODE_ID:-}" ]; then
    export INGEST_NODE_ID="$resolved_node_id"
  fi
  if [ -z "${POD_IP:-}" ]; then
    echo "WARN: INGEST cluster enabled but POD_IP is empty; endpoint falls back to local host address" >&2
  fi
fi

exec java ${JAVA_TOOL_OPTIONS} -XX:+ExitOnOutOfMemoryError -jar ./*.jar --spring.config.additional-location=file:./application.yml
