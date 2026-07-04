<p align="center">
  <a href="k8s安装部署.md">中文</a>
  &nbsp;|&nbsp;
  <a href="k8s安装部署_en.md">English</a>
</p>

# K8s 安装部署

在 Kubernetes 集群里跑起 DataBuff：Doris、Ingest、Web 按顺序自动部署。

## 1. 准备环境

- Kubernetes 集群
- kubectl 可访问集群

## 2. 安装平台

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-k8s-install.sh | bash
```

安装完成后，终端会输出 Web UI、命名空间和访问方式。

指定版本安装：

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-k8s-install.sh | bash -s -- --version 0.1.1
# 或
APM_VERSION=0.1.1 curl -fsSL https://databuff.ai/databuff/ai-apm-k8s-install.sh | bash
```

> **运维详情**：[Kubernetes 运维参考](../运维参考/K8s运维.md) — 启停、NodePort 访问、离线镜像与扩缩容。

## 3. 安装 Demo（可选）

让 Demo 应用向平台上报 Trace，快速看到链路和拓扑。

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-demo-k8s-install.sh | bash
```

## 4. 启用 AI

配置管理 → 模型配置，输入 API Key 即可：

![配置 API Key](../images/set-api-key.png)

现在可以直接问：

> 哪些服务错误率最高？
