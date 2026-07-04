<p align="center">
  <a href="README.md">中文</a>
  &nbsp;|&nbsp;
  <a href="README_en.md">English</a>
</p>

# DataBuff Documentation

Open-source AI-native OpenTelemetry APM.

Build a standard, reliable, easy-to-deploy APM backend first, then put AI into real troubleshooting workflows.

## Quick Start

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-install.sh | bash
```

Install the platform, then install the Demo app to see traces, metrics, topology, and AI diagnostics.

Online docs: [databuff.ai/docs](https://databuff.ai/docs/en/)

## Documentation Index

### Product Overview

- [Product Overview](产品介绍_en.md)
- [Roadmap](Roadmap_en.md)

### Getting Started

- [OpenTelemetry OTLP Ingestion](opentelemetry-otlp-ingestion_en.md)
- [Docker Installation](快速入门/docker安装部署_en.md)
- [Kubernetes Installation](快速入门/k8s安装部署_en.md)

### User Guide

- [AI Platform](使用手册/AI平台_en.md)
- [Custom Digital Experts](使用手册/自定义数字专家_en.md)
- [External MCP Integration](使用手册/外部MCP集成_en.md)
- [Application Performance](使用手册/应用性能_en.md)
- [Alerting](使用手册/告警_en.md)
- [Agent Integration](使用手册/Agent集成_en.md)

### Operations

- [Docker Operations](运维参考/Docker运维_en.md)
- [Kubernetes Operations](运维参考/K8s运维_en.md)
- [Upgrade and Uninstall](运维参考/升级与卸载_en.md)
- [Offline Installation](运维参考/离线安装_en.md)

### Architecture

- [Telemetry Pipeline and Storage](架构设计/遥测数据流_en.md)
- [AI Platform](架构设计/AI平台_en.md)
- [Application Performance](架构设计/应用性能_en.md)
- [Alerting](架构设计/告警_en.md)
- [Log Analytics](架构设计/日志分析_en.md)

## Core Pipeline

```mermaid
flowchart LR
  OTel["OpenTelemetry"] --> Ingest["Ingest"]
  Ingest --> Doris["Doris Storage"]
  Doris --> Web["Web Platform"]
  Web --> AI["AI Diagnostics"]
```
