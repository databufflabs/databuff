<p align="center">
  <a href="eBPF接入.md">中文</a>
  &nbsp;|&nbsp;
  <a href="eBPF接入_en.md">English</a>
</p>

# 使用手册 · eBPF 接入

DataBuff **Ingest** 通过 **OTLP** 接收 [OpenTelemetry eBPF Instrumentation（OBI）](https://github.com/open-telemetry/opentelemetry-ebpf-instrumentation) 上报的 Trace。在 Kubernetes 里用 **DaemonSet** 在每个节点旁路采集 HTTP / gRPC 调用，**业务 Pod 不用改代码、不用注入探针**。

```text
业务 Pod（不改） ──► 同节点 OBI（eBPF） ──► OTLP HTTP :4318 ──► DataBuff Ingest
```

镜像一般是 `otel/ebpf-instrument`。链路上下文靠 HTTP 头里的 `traceparent` 串起来（下文 YAML 已开 `context_propagation: headers`）。

## 支持的信号

| 信号 | 协议 | Ingest 端口 |
|------|------|-------------|
| Trace | OTLP HTTP（`http/protobuf`） | `4318` |

OBI 主要吃 **HTTP / gRPC** 这类协议边界。**Dubbo RPC 目前采不到**；方法栈、业务自定义 Span 也看不到——这些仍要用语言 Agent。内核不够或没有 BTF 时，同样改用 Agent。

## 前置条件

1. DataBuff 已部署，Ingest 的 OTLP HTTP 口可达。
   - [Docker 安装](../快速入门/docker安装部署.md)
   - [Kubernetes 安装](../快速入门/k8s安装部署.md)
2. 业务跑在 Kubernetes 里，节点内核建议 **5.8+**，且有 BTF。
3. 采集 Pod 需要 `hostPID: true` 和 `privileged: true`（看见宿主机进程并挂 eBPF）。

### 接入地址

OBI 与 DataBuff 在同一集群时（默认命名空间 `databuff`）：

| 场景 | OTLP HTTP 地址 |
|------|----------------|
| 集群内（推荐） | `http://ai-apm-ingest.databuff.svc:4318` |
| 集群外 / DataBuff 在别的机器 | `http://<ingest-host>:4318`（K8s NodePort 默认 `30418`） |

把下面 YAML 里的 `YOUR_DATABUFF_HOST` 换成 **不含** `http://` 的主机名，例如 `ai-apm-ingest.databuff.svc`。

## 1. 确认节点内核能跑 eBPF

在**各业务节点**上执行：

```bash
uname -r
ls /sys/kernel/btf/vmlinux
```

内核建议 5.8+，且第二条能列出文件。缺 BTF 的节点上，采集 Pod 会起不来或采不到。相关节点都通过后再继续。

## 2. 镜像（能出网可跳过）

镜像用 `otel/ebpf-instrument:latest`（实测 latest 可用；**生产请钉版本**）。

集群能出网的话，这步可跳过，apply 时再拉。离线则先 pull，再传到各节点 load。

## 3. apply DaemonSet

下面 YAML 一次 apply，改两处占位符：

- `YOUR_APP_NAMESPACE` → 业务命名空间
- `YOUR_DATABUFF_HOST` → Ingest 主机名（同集群一般为 `ai-apm-ingest.databuff.svc`）

存成 `obi.yaml`：

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: obi
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: obi
  namespace: obi
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: obi
rules:
  - apiGroups: ["apps"]
    resources: ["replicasets", "deployments", "daemonsets", "statefulsets"]
    verbs: ["list", "watch", "get"]
  - apiGroups: [""]
    resources: ["pods", "services", "nodes", "namespaces"]
    verbs: ["list", "watch", "get"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: obi
subjects:
  - kind: ServiceAccount
    name: obi
    namespace: obi
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: obi
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: obi-config
  namespace: obi
data:
  obi-config.yml: |
    discovery:
      instrument:
        - k8s_namespace: YOUR_APP_NAMESPACE
    attributes:
      kubernetes:
        enable: true
    network:
      enable: false
    ebpf:
      context_propagation: headers
    otel_traces_export:
      endpoint: http://YOUR_DATABUFF_HOST:4318
---
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: obi
  namespace: obi
  labels:
    app: obi
spec:
  selector:
    matchLabels:
      app: obi
  template:
    metadata:
      labels:
        app: obi
    spec:
      serviceAccountName: obi
      hostPID: true
      containers:
        - name: obi
          image: otel/ebpf-instrument:latest
          imagePullPolicy: IfNotPresent
          securityContext:
            privileged: true
          env:
            - name: OTEL_EBPF_CONFIG_PATH
              value: /etc/obi/obi-config.yml
            - name: OTEL_EXPORTER_OTLP_PROTOCOL
              value: http/protobuf
            - name: OTEL_EBPF_KUBE_METADATA_ENABLE
              value: "true"
          volumeMounts:
            - name: config
              mountPath: /etc/obi
            - name: sys
              mountPath: /sys
              readOnly: true
          resources:
            requests:
              cpu: 100m
              memory: 256Mi
            limits:
              memory: 1Gi
      volumes:
        - name: config
          configMap:
            name: obi-config
        - name: sys
          hostPath:
            path: /sys
            type: Directory
      tolerations:
        - operator: Exists
```

```bash
kubectl apply -f obi.yaml
kubectl -n obi get ds,pods -o wide
```

看输出：`DESIRED` / `READY` 等于节点数，每个 `pod/obi-*` 为 Running。

## 4. 日志确认盯上了业务进程

```bash
kubectl -n obi logs -l app=obi --tail=80 | grep -iE "instrumenting|process|error" | head -30
```

有 `instrumenting process` 即可。没有就查命名空间占位符和业务是否在跑。

## 5. 打流量并核对

```bash
# 换成你的业务 URL
for i in $(seq 1 80); do
  curl -sS -m 2 "http://业务地址/" >/dev/null || true
  sleep 0.2
done
```

打开 DataBuff **应用性能 → 服务**，搜应用名；再点开拓扑和链路详情。服务找得到、链路点得开即可。

## eBPF 和语言 Agent 怎么选

| 方案 | 更适合 |
|------|--------|
| eBPF + DaemonSet（本文） | 不想注入、不想重启；先看 HTTP 调用；多语言混部先铺一层 |
| 语言 Agent（如 javaagent） | Dubbo、慢 SQL、方法栈；内核不够 5.8+ / 没有 BTF |

短板：

- **不支持 Dubbo**。OBI 主要吃 HTTP / gRPC；Dubbo RPC 要看调用还得上语言 Agent。
- 看不到方法栈、业务自定义 Span——它在节点外侧旁路观测。
- 采集 Pod 必须 privileged；节点要有 BTF（第 1 步那两条命令）。

## 相关文档

- [OpenTelemetry OTLP 接入](../opentelemetry-otlp-ingestion.md)
- [Nginx 接入](Nginx接入.md)
- [SkyWalking 接入](SkyWalking接入.md)
- [应用性能](应用性能.md)
- [Kubernetes 安装](../快速入门/k8s安装部署.md)
