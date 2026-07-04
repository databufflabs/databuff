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

After installation, the terminal prints the Web UI URL, namespace, and access details.

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

## 4. Enable AI

Go to **Configuration → Model Settings** and enter your API key:

![Configure API key](../images/set-api-key.png)

You can now ask questions like:

> Which services have the highest error rates?
