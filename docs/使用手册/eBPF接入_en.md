<p align="center">
  <a href="eBPF接入.md">中文</a>
  &nbsp;|&nbsp;
  <a href="eBPF接入_en.md">English</a>
</p>

# User Guide · eBPF Ingestion

DataBuff **Ingest** accepts traces from [OpenTelemetry eBPF Instrumentation (OBI)](https://github.com/open-telemetry/opentelemetry-ebpf-instrumentation) over **OTLP**. Deploy a **DaemonSet** on Kubernetes so each node observes HTTP / gRPC calls **without changing application pods or injecting language agents**.

```text
App Pod (unchanged) ──► OBI on the same node (eBPF) ──► OTLP HTTP :4318 ──► DataBuff Ingest
```

The usual image is `otel/ebpf-instrument`. Trace context is stitched via the HTTP `traceparent` header (the YAML below sets `context_propagation: headers`).

## Supported signals

| Signal | Protocol | Ingest port |
|--------|----------|-------------|
| Traces | OTLP HTTP (`http/protobuf`) | `4318` |

OBI covers **HTTP / gRPC** protocol boundaries. **Dubbo RPC is not collected**. Method stacks and custom business spans are also out of scope — use a language agent for those. If the kernel is too old or BTF is missing, use a language agent as well.

## Prerequisites

1. DataBuff is deployed and Ingest OTLP HTTP is reachable.
   - [Docker installation](../快速入门/docker安装部署_en.md)
   - [Kubernetes installation](../快速入门/k8s安装部署_en.md)
2. Workloads run on Kubernetes. Kernel **5.8+** with BTF is recommended.
3. The collector Pod needs `hostPID: true` and `privileged: true` (see host processes and attach eBPF).

### Endpoints

When OBI and DataBuff share a cluster (default namespace `databuff`):

| Scenario | OTLP HTTP endpoint |
|----------|-------------------|
| In-cluster (recommended) | `http://ai-apm-ingest.databuff.svc:4318` |
| Outside the cluster / DataBuff on another host | `http://<ingest-host>:4318` (K8s NodePort default `30418`) |

Replace `YOUR_DATABUFF_HOST` in the YAML with the hostname **without** `http://`, for example `ai-apm-ingest.databuff.svc`.

## 1. Confirm nodes can run eBPF

On **each application node**:

```bash
uname -r
ls /sys/kernel/btf/vmlinux
```

Kernel 5.8+ is recommended, and the second command must list a file. Nodes without BTF will fail to start the collector or collect nothing. Continue only after every relevant node passes.

## 2. Image (skip if the cluster can pull)

Use `otel/ebpf-instrument:latest` (latest has been verified; **pin a version in production**).

If the cluster can reach the registry, skip this step and pull on apply. For air-gapped nodes, pull first and load the image locally.

## 3. Apply the DaemonSet

Apply the YAML once after replacing two placeholders:

- `YOUR_APP_NAMESPACE` → application namespace
- `YOUR_DATABUFF_HOST` → Ingest hostname (same cluster: `ai-apm-ingest.databuff.svc`)

Save as `obi.yaml`:

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

`DESIRED` / `READY` should match the node count, and every `pod/obi-*` should be Running.

## 4. Confirm the collector attached to app processes

```bash
kubectl -n obi logs -l app=obi --tail=80 | grep -iE "instrumenting|process|error" | head -30
```

Look for `instrumenting process`. If missing, check the namespace placeholder and that the app is running.

## 5. Send traffic and verify in DataBuff

```bash
# replace with your app URL
for i in $(seq 1 80); do
  curl -sS -m 2 "http://APP_URL/" >/dev/null || true
  sleep 0.2
done
```

Open DataBuff **Application Performance → Services**, search for the app name, then open topology and a trace. Success means the service appears and traces open.

## eBPF vs language agents

| Approach | Better when |
|----------|-------------|
| eBPF + DaemonSet (this page) | No inject / no restart; HTTP first; mixed languages |
| Language agent (e.g. javaagent) | Dubbo, slow SQL, method stacks; kernel &lt; 5.8 or no BTF |

Limits:

- **No Dubbo.** OBI sees HTTP / gRPC; Dubbo still needs a language agent.
- No method stacks or custom spans — collection is outside the process.
- The collector Pod must be privileged; nodes need BTF (the two commands in step 1).

## Related docs

- [OpenTelemetry OTLP Ingestion](../opentelemetry-otlp-ingestion_en.md)
- [Nginx Ingestion](Nginx接入_en.md)
- [SkyWalking Ingestion](SkyWalking接入_en.md)
- [Application Performance](应用性能_en.md)
- [Kubernetes Installation](../快速入门/k8s安装部署_en.md)
