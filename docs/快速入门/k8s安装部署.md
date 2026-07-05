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

安装完成后，终端会输出 Web UI、命名空间和访问方式。默认命名空间 `databuff`：

| 用途 | 地址 |
|------|------|
| Web UI | `http://<节点IP>:32703` |
| 默认账号 | `admin` / `Databuff@123` |
| OTLP gRPC | `<节点IP>:30417` |
| OTLP HTTP | `http://<节点IP>:30418` |

集群内 Agent 上报：`http://ai-apm-ingest:4318`（gRPC `ai-apm-ingest:4317`）。NodePort 供集群外使用。自有应用接入见 [OpenTelemetry OTLP 接入](../opentelemetry-otlp-ingestion.md)。

### 离线镜像（可选）

节点无法拉取镜像时：

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-k8s-download-images.sh | bash
```

详见 [K8s 运维参考](../运维参考/K8s运维.md) 离线镜像一节。

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

## 4. 安装后验证

1. 打开 `http://<节点IP>:32703`，用默认账号登录
2. **应用性能 → 服务列表**，确认 Demo 服务有数据
3. （可选）**配置管理 → 模型配置**，输入 API Key 启用 AI：

![配置 API Key](../images/set-api-key.png)

AI 示例问句：「查询最近1小时的服务列表」
