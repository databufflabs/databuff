# shellcheck shell=bash
# DataBuff AI APM K8s 公共函数（install / start / stop 共用）

_K8S_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -f "${_K8S_LIB_DIR}/../env.sh" ]]; then
  # shellcheck disable=SC1091
  source "${_K8S_LIB_DIR}/../env.sh"
elif [[ -f "${_K8S_LIB_DIR}/../../env.sh" ]]; then
  # shellcheck disable=SC1091
  source "${_K8S_LIB_DIR}/../../env.sh"
fi

: "${APM_NAMESPACE:=databuff}"
: "${DORIS_FE_IMAGE:=apache/doris:fe-4.1.1}"
: "${DORIS_BE_IMAGE:=apache/doris:be-4.1.1}"
: "${ZOOKEEPER_IMAGE:=bitnamilegacy/zookeeper:3.9}"
: "${WAIT_TIMEOUT:=25m}"
: "${UNINSTALL_TIMEOUT:=10m}"
: "${STOP_TIMEOUT:=10m}"
: "${DORIS_INIT_MAX:=120}"
: "${WEB_NODE_PORT:=32703}"
: "${INGEST_HTTP_NODE_PORT:=30418}"
: "${MANIFEST_DIR:=${ROOT}/manifests}"
: "${SQL_FILE:=${ROOT}/sql/databuff.sql}"

CYN='\033[36m'
GRN='\033[32m'
YLW='\033[33m'
RED='\033[31m'
DIM='\033[2m'
BLD='\033[1m'
RST='\033[0m'

log() {
  echo -e "${CYN}[${APM_LOG_PREFIX:-install}]${RST} $*"
}

log_sub() {
  echo -e "${CYN}[${APM_LOG_PREFIX:-install}]${RST} ${DIM}       $*${RST}"
}

warn() {
  echo -e "${CYN}[${APM_LOG_PREFIX:-install}]${RST} ${YLW}$*${RST}"
}

error() {
  echo -e "${RED}[${APM_LOG_PREFIX:-install}] ERROR:${RST} $*" >&2
}

ensure_kube_access() {
  command -v kubectl >/dev/null 2>&1 || {
    error "缺少命令: kubectl"
    exit 1
  }

  if [[ -z "${KUBECONFIG:-}" ]]; then
    if [[ -f /etc/rancher/k3s/k3s.yaml ]]; then
      export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
    elif [[ -f "${HOME}/.kube/config" ]]; then
      export KUBECONFIG="${HOME}/.kube/config"
    elif [[ -f /etc/kubernetes/admin.conf ]]; then
      export KUBECONFIG=/etc/kubernetes/admin.conf
    fi
  fi

  kubectl cluster-info >/dev/null 2>&1 || {
    error "无法连接 Kubernetes API，请先配置 kubeconfig"
    exit 1
  }
}

resolve_release_tag() {
  local tag="${APM_VERSION:-}"
  if [[ -f "${ROOT}/VERSION" ]]; then
    tag="$(tr -d '[:space:]' <"${ROOT}/VERSION")"
  fi
  if [[ -z "$tag" ]]; then
    error "missing APM_VERSION (set in env.sh)"
    exit 1
  fi
  echo "$tag"
}

apply_manifest() {
  local file="$1"
  sed \
    -e "s|__INGEST_IMAGE__|${INGEST_IMAGE}|g" \
    -e "s|__WEB_IMAGE__|${WEB_IMAGE}|g" \
    -e "s|__DORIS_FE_IMAGE__|${DORIS_FE_IMAGE}|g" \
    -e "s|__DORIS_BE_IMAGE__|${DORIS_BE_IMAGE}|g" \
    -e "s|__ZOOKEEPER_IMAGE__|${ZOOKEEPER_IMAGE}|g" \
    "$file" | kubectl apply -f - >/dev/null
}

wait_pod_ready() {
  local component="$1"
  log_sub "等待 ${component} Pod Ready (timeout=${WAIT_TIMEOUT}) ..."
  kubectl wait --for=condition=ready pod \
    -l "app.kubernetes.io/name=ai-apm,app.kubernetes.io/component=${component}" \
    -n "$APM_NAMESPACE" \
    --timeout="$WAIT_TIMEOUT"
}

wait_ready() {
  local pids=() component
  for component in "$@"; do
    wait_pod_ready "$component" &
    pids+=("$!")
  done
  local pid
  for pid in "${pids[@]}"; do
    wait "$pid"
  done
}

wait_pods_gone() {
  local timeout="${1:-$UNINSTALL_TIMEOUT}"
  kubectl wait --for=delete pod \
    -l app.kubernetes.io/name=ai-apm \
    -n "$APM_NAMESPACE" \
    --timeout="$timeout" 2>/dev/null || true
}

remove_existing() {
  log "卸载已有 DataBuff 资源 (timeout=${UNINSTALL_TIMEOUT}) ..."
  kubectl delete deploy,sts,svc,configmap \
    -l app.kubernetes.io/name=ai-apm \
    -n "$APM_NAMESPACE" \
    --ignore-not-found --wait=true --timeout="$UNINSTALL_TIMEOUT"
  wait_pods_gone "$UNINSTALL_TIMEOUT"
  log_sub "卸载完成"
}

stop_services() {
  log "停止 DataBuff 服务 (timeout=${STOP_TIMEOUT}) ..."
  kubectl scale deploy/ai-apm-ingest sts/ai-apm-ingest deploy/ai-apm-web \
    sts/ai-apm-doris sts/ai-apm-zookeeper \
    -n "$APM_NAMESPACE" --replicas=0 >/dev/null 2>&1 || true
  wait_pods_gone "$STOP_TIMEOUT"
  log_sub "Pod 已退出"
}

doris_pod_name() {
  kubectl get pod -n "$APM_NAMESPACE" \
    -l app.kubernetes.io/name=ai-apm,app.kubernetes.io/component=doris \
    -o jsonpath='{.items[0].metadata.name}'
}

doris_mysql() {
  kubectl exec -i -n "$APM_NAMESPACE" "$(doris_pod_name)" -c ai-apm-doris-fe -- \
    mysql -h127.0.0.1 -P9030 -uroot "$@"
}

if [[ -f "${_K8S_LIB_DIR}/doris-be-wait.sh" ]]; then
  # shellcheck disable=SC1091
  source "${_K8S_LIB_DIR}/doris-be-wait.sh"
elif [[ -f "${_K8S_LIB_DIR}/../../common/scripts/doris-be-wait.sh" ]]; then
  # shellcheck disable=SC1091
  source "${_K8S_LIB_DIR}/../../common/scripts/doris-be-wait.sh"
else
  error "缺少 doris-be-wait.sh"
  exit 1
fi

doris_schema_ready() {
  doris_mysql -e "SELECT 1" >/dev/null 2>&1 \
    && doris_mysql -N -e "SHOW DATABASES LIKE 'databuff'" 2>/dev/null | grep -qx databuff
}

init_doris_schema() {
  [[ -f "$SQL_FILE" ]] || {
    error "缺少 SQL 文件: ${SQL_FILE}"
    exit 1
  }

  local i=1
  log_sub "[init-doris] 等待 Doris FE ..."
  while [[ "$i" -le "$DORIS_INIT_MAX" ]]; do
    doris_mysql -e "SELECT 1" >/dev/null 2>&1 && break
    [[ "$i" -eq "$DORIS_INIT_MAX" ]] && {
      error "[init-doris] 等待 Doris FE 超时"
      exit 1
    }
    sleep 2
    i=$((i + 1))
  done

  log_sub "[init-doris] 等待 Doris BE alive ..."
  wait_for_be_alive "[init-doris]" "$DORIS_INIT_MAX" 3 || exit 1

  wait_for_be_avail_stable "[init-doris]" 120 || exit 1

  log_sub "[init-doris] 执行 $(basename "$SQL_FILE") ..."
  apply_doris_sql_file "$SQL_FILE" || exit 1
  log_sub "[init-doris] ok"
}

deploy_stack() {
  local tag
  tag="$(resolve_release_tag)"
  INGEST_IMAGE="${APM_INGEST_IMAGE:-databuffhub/ai-apm-ingest:${tag}}"
  WEB_IMAGE="${APM_WEB_IMAGE:-databuffhub/ai-apm-web:${tag}}"

  [[ -d "$MANIFEST_DIR" ]] || {
    error "缺少 manifests 目录: ${MANIFEST_DIR}"
    exit 1
  }

  ensure_kube_access
  [[ "${INSTALL_SKIP_UNINSTALL:-0}" != "1" ]] && remove_existing
  kubectl create namespace "$APM_NAMESPACE" --dry-run=client -o yaml | kubectl apply -f - >/dev/null

  log "[1/3] 启动基础设施（ZooKeeper + Doris 并行）..."
  apply_manifest "${MANIFEST_DIR}/configmap.yaml"
  apply_manifest "${MANIFEST_DIR}/zookeeper.yaml"
  apply_manifest "${MANIFEST_DIR}/doris.yaml"
  wait_ready zookeeper doris

  if doris_schema_ready; then
    log "[2/3] Doris 库表已存在，跳过初始化"
  else
    log "[2/3] 初始化 Doris SQL ..."
    init_doris_schema
  fi

  log "[3/3] 启动应用（ingest + web 并行）..."
  apply_manifest "${MANIFEST_DIR}/ingest.yaml"
  apply_manifest "${MANIFEST_DIR}/web.yaml"
  wait_ready ingest web
}

detect_local_ip() {
  local ip=""
  command -v ip >/dev/null 2>&1 \
    && ip="$(ip route get 1.1.1.1 2>/dev/null | awk '{for (i=1;i<=NF;i++) if ($i=="src") {print $(i+1); exit}}')"
  [[ -z "$ip" ]] && command -v hostname >/dev/null 2>&1 \
    && ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
  [[ -n "$ip" ]] && echo "$ip" || echo "127.0.0.1"
}

apm_seed_username() {
  local val=""
  if command -v kubectl >/dev/null 2>&1; then
    val="$(kubectl -n "${APM_NAMESPACE}" exec deploy/ai-apm-web -- printenv APM_SECURITY_SEED_USERNAME 2>/dev/null || true)"
  fi
  if [[ -z "$val" ]]; then
    val="${APM_SECURITY_SEED_USERNAME:-}"
  fi
  printf '%s\n' "${val:-admin}"
}

apm_seed_password() {
  local val=""
  if command -v kubectl >/dev/null 2>&1; then
    val="$(kubectl -n "${APM_NAMESPACE}" exec deploy/ai-apm-web -- printenv APM_SECURITY_SEED_PASSWORD 2>/dev/null || true)"
  fi
  if [[ -z "$val" ]]; then
    val="${APM_SECURITY_SEED_PASSWORD:-}"
  fi
  printf '%s\n' "${val:-Databuff@123}"
}

print_apm_summary() {
  local title="$1"
  local show_demo="${2:-0}"
  local host_ip demo_pkg_base login_user login_pass
  host_ip="$(detect_local_ip)"
  demo_pkg_base="${APM_PUBLIC_PKG_BASE:-https://databuff.ai/databuff}"
  login_user="$(apm_seed_username)"
  login_pass="$(apm_seed_password)"
  echo ""
  echo -e "${CYN}========================================================${RST}"
  echo -e "${GRN}${BLD} ${title}${RST}"
  echo -e "${CYN}========================================================${RST}"
  echo ""
  echo -e "  ${CYN}Web UI${RST}"
  echo "    http://${host_ip}:${WEB_NODE_PORT}"
  echo -e "  ${CYN}账号${RST}"
  printf "    %s / ${YLW}%s${RST}\n" "$login_user" "$login_pass"
  echo -e "  ${CYN}Ingest${RST}"
  echo "    http://${host_ip}:${INGEST_HTTP_NODE_PORT}/v1/traces"
  echo ""
  echo -e "  ${DIM}安装目录${RST}"
  echo "    ${ROOT}"
  echo -e "  ${DIM}启动${RST}"
  echo "    cd ${ROOT} && ./start.sh"
  echo -e "  ${DIM}停止${RST}"
  echo "    cd ${ROOT} && ./stop.sh"
  if [[ "$show_demo" == "1" ]]; then
    echo -e "  ${DIM}Demo造数${RST}"
    echo "    curl -fsSL ${demo_pkg_base}/ai-apm-demo-k8s-install.sh | bash"
  fi
  echo ""
  echo -e "${CYN}========================================================${RST}"
  echo ""
}

print_install_summary() {
  print_apm_summary "安装完成" 1
}

print_start_summary() {
  print_apm_summary "服务已就绪"
}
