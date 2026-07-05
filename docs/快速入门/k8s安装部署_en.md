<p align="center">
  <a href="k8s安装部署.md">中文</a>
  &nbsp;|&nbsp;
  <a href="k8s安装部署_en.md">English</a>
</p>

# Kubernetes Installation

Run DataBuff on a Kubernetes cluster — Doris, Ingest, and Web are deployed automatically in order.

## 1. Prerequisites

- A Kubernetes cluster
- kubectl with cluster access

## 2. Install the Platform

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-k8s-install.sh | bash
```

After installation, the terminal prints the Web UI URL, namespace, and access details. Default namespace: `databuff`.

| Purpose | Address |
|---------|---------|
| Web UI | `http://<node-ip>:32703` |
| Default login | `admin` / `Databuff@123` |
| OTLP gRPC | `<node-ip>:30417` |
| OTLP HTTP | `http://<node-ip>:30418` |

In-cluster agents: `http://ai-apm-ingest:4318` (gRPC `ai-apm-ingest:4317`). NodePorts are for external access. To connect your own apps, see [OpenTelemetry OTLP Ingestion](../opentelemetry-otlp-ingestion_en.md).

### Offline Images (Optional)

When nodes cannot pull images:

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-k8s-download-images.sh | bash
```

See [Kubernetes Operations](../运维参考/K8s运维_en.md) — Offline Images.

Install a specific version:

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-k8s-install.sh | bash -s -- --version 0.1.1
# or
APM_VERSION=0.1.1 curl -fsSL https://databuff.ai/databuff/ai-apm-k8s-install.sh | bash
```

> **Operations**: [Kubernetes Operations Reference](../运维参考/K8s运维_en.md) — start/stop, NodePort access, offline images, scaling.

## 3. Install the Demo (Optional)

Let the demo app report traces to the platform and quickly see call chains and topology.

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-demo-k8s-install.sh | bash
```

## 4. Post-Install Verification

1. Open `http://<node-ip>:32703` and sign in with the default account
2. **APM → Service List** — confirm demo services have data
3. (Optional) **Configuration → Model Settings** — enter your API key to enable AI:

![Configure API key](../images/set-api-key.png)

Example AI query: "Query service list for the last hour"
