# DataBuff 文档

国产开源 AI 原生 OpenTelemetry APM。

先做标准、可靠、易部署的 APM 后端，再把 AI 放进真实排障路径。

## 快速开始

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-install.sh | bash
```

安装平台，再安装 Demo，就能看到 Trace、指标、拓扑和 AI 诊断。

## 文档目录

### 了解产品

- [产品介绍](产品介绍.md)
- [Roadmap](Roadmap.md)

### 快速入门

- [Docker 安装部署](快速入门/docker安装部署.md)
- [K8s 安装部署](快速入门/k8s安装部署.md)

### 使用手册

- [AI 平台](使用手册/AI平台.md)
- [自定义数字专家](使用手册/自定义数字专家.md)
- [外部 MCP 集成](使用手册/外部MCP集成.md)
- [应用性能](使用手册/应用性能.md)
- [告警](使用手册/告警.md)

### 架构设计

- [AI 平台](架构设计/AI平台.md)
- [应用性能](架构设计/应用性能.md)
- [告警](架构设计/告警.md)

## 核心链路

```mermaid
flowchart LR
  OTel["OpenTelemetry"] --> Ingest["Ingest 接入"]
  Ingest --> Doris["Doris 存储"]
  Doris --> Web["Web 平台"]
  Web --> AI["AI 诊断"]
```
