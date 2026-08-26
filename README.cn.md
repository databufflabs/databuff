<div align="center">

<img src="ai-apm-frontend/public/img/logo_login.png" alt="DataBuff" height="56" />
&nbsp;&nbsp;
<img src="ai-apm-frontend/public/img/logo_wordmark.svg" alt="Databuff" height="32" />

<h3>DataBuff — AI 原生 OpenTelemetry APM</h3>

<p><strong>项目目标：打造最强的 OpenTelemetry APM 后端</strong></p>
<p>AI 原生 · OTLP 原生 · 多智能体开箱即用 · 自托管 Trace / 指标 / 拓扑</p>

<p align="center">
  <a href="https://demo.databuff.ai">在线演示</a>
  &nbsp;·&nbsp;
  <a href="https://databuff.ai/docs/zh/">中文文档</a>
  &nbsp;·&nbsp;
  <a href="docs/README.md">文档目录</a>
  &nbsp;·&nbsp;
  <a href="README.md">English</a>
  &nbsp;·&nbsp;
  <a href="#交流群">交流群</a>
</p>

<p align="center">
  <img src="https://img.shields.io/github/stars/databufflabs/databuff?style=social" />
  <img src="https://img.shields.io/badge/License-Apache--2.0-blue.svg" />
  <img src="https://img.shields.io/badge/AI原生-APM-7C3AED?style=flat-square" />
  <img src="https://img.shields.io/badge/OpenTelemetry-原生_OTLP-000000?style=flat-square&logo=opentelemetry&logoColor=white" />
  <img src="https://img.shields.io/badge/多智能体-开箱即用-2563EB?style=flat-square" />
  <img src="https://img.shields.io/badge/Docker-一条命令-2496ED?style=flat-square&logo=docker&logoColor=white" />
</p>

<p align="center">在线演示账号：<code>admin</code> / <code>Databuff@123</code></p>

</div>

<p align="center">
  <img src="docs/images/databuff-demo-en.gif" alt="DataBuff 演示：AI 对话、服务列表、调用拓扑" width="880" />
</p>
<p align="center"><sub>AI 多智能体排障 · 服务健康 · 调用链拓扑</sub></p>

**关键词**：`AI 原生 APM` · `OpenTelemetry APM` · `OTLP 后端` · `多智能体` · `分布式追踪` · `AIOps` · `开源 APM` · `MCP` · `自托管可观测性`

---

## DataBuff 是什么

**DataBuff 是一款 AI 原生的 OpenTelemetry APM 后端** —— 以 OTLP 标准接入 Trace / 指标 / 日志，提供全链路监控、服务拓扑与 RED 指标，并内置**开箱即用的多智能体 AI 排障能力**。

> 目标不是「又一个可观测性 UI」，而是 **打造最强的 OpenTelemetry APM 后端** —— 让 LLM 直接查询实时遥测数据，完成自然语言问数 → 多智能体巡检 → 根因分析 → 运维修复的完整闭环。

已收录 [OpenTelemetry 官方 Vendors](https://opentelemetry.io/ecosystem/vendors/)（Native OTLP）与 [CNCF Landscape](https://landscape.cncf.io/?item=observability-and-analysis--observability--databuff)。

[⭐ Star 支持项目](https://github.com/databufflabs/databuff) · [在线演示](https://demo.databuff.ai) · [中文文档](https://databuff.ai/docs/zh/)

---

## 为什么选择 DataBuff

如果你正在选型 OpenTelemetry APM 后端，或希望把 AI 真正用在排障闭环里，DataBuff 是为这类场景设计的：

- **AI 原生，不是外挂** — LLM 直接查询 Trace、指标、拓扑、告警，安装填 API Key 即可开问，无需另搭 AI 平台
- **多智能体开箱即用** — AI 大脑编排问数、巡检、运维、答疑专家，复杂任务并行协作
- **完全拥抱 OpenTelemetry** — OTLP 原生接入，现有埋点原样可用；同时兼容 SkyWalking，平滑迁移
- **目标明确** — 打造最强的 OpenTelemetry APM 后端，自托管、生产级、可扩展
- **极简部署** — Ingest + Doris + Web 三组件，Docker 一条命令跑起来

---

## 关键特性

### 🤖 AI 原生
- **不是外挂聊天框** — LLM 基于真实遥测数据回答，而非幻觉
- **多智能体协同开箱即用** — AI 大脑编排问数、巡检、运维、答疑专家
- **AI 应用监控**（Roadmap）— LLM 调用链 · Token 分析 · Agent 拓扑 · 技能/工具/模型调用追踪
- **MCP 双向开放** — 对接 Cursor / Claude，也可接入 Prometheus 等外部工具
- **自带模型** — 支持 Kimi、DeepSeek、GLM、Ollama 等 OpenAI 兼容接口

### 📊 OpenTelemetry 原生
- **OTLP 原生接入** — gRPC `4317` / HTTP `4318`，Traces + Metrics + Logs 一站式
- **eBPF APM** — 内核级无侵入采集，零修改代码获取调用链与性能数据
- **双协议兼容** — 同时支持 SkyWalking 原生 gRPC（`11800`），老 Agent 改地址即切换
- **告警闭环** — 阈值检测、定时评估、告警事件记录

### 🐳 工程底座
- **三组件架构** — Ingest + Doris + Web，无中间件堆砌
- **Skill 可扩展** — 支持自定义数字专家，无需改核心代码

---

## 功能一览

开箱即用的多智能体 AI，叠加在完整的 OpenTelemetry APM 之上。

<img src="docs/images/screenshots/aiops-arc-zh.svg" alt="AIOps 路线图：看得见 → 军团协同 → 会巡检 → 会诊断 → 会修 → 会预测 → 会答疑" width="900" />

#### 自然语言问数

大白话问「哪个服务最慢」，AI 自动查排行，一行查询语言都不用写。

<img src="docs/images/screenshots/nl-slowest.png" alt="自然语言问数" width="720" />

#### 多智能体协同

复杂任务并发派发给多个专家，分头查证后汇总成可转发的故障报告。

<img src="docs/images/screenshots/multi-agent-process.png" alt="多智能体协同" width="720" />

#### 根因分析

拉拓扑、排指标，按占比归因瓶颈，结论可直接写进故障报告。

<img src="docs/images/screenshots/rca.png" alt="根因分析" width="720" />

#### APM 界面

全局拓扑、服务列表、链路下钻一应俱全，AI 读数据、界面做确认。

<img src="docs/images/screenshots/global-topology.jpg" alt="全局拓扑" width="720" />

更多能力见 [中文文档](https://databuff.ai/docs/zh/)

---

## 架构与接入

<img src="docs/images/screenshots/simple-architecture.jpg" alt="极简架构：Ingest + Doris + Web" width="900" />

| 协议 | 端口 / 端点 | 支持的信号 |
| :-- | :-- | :-- |
| **OTLP**（OpenTelemetry 原生） | gRPC `4317` · HTTP `4318` | Traces + Metrics + Logs |
| **SkyWalking** 原生 gRPC | gRPC `11800` | Trace + JVM 指标 + 日志 |

---

## 5 分钟快速安装

1. **安装平台** — 一条命令拉起 Ingest + Doris + Web

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-install.sh | bash
```

2. **安装 Demo**（可选）— 自动上报 Trace，快速看到拓扑

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-demo-install.sh | bash
```

3. **接入模型 → 开始排障** — 访问 `http://YOUR_HOST:27403`，登录 `admin` / `Databuff@123`，填入 API Key 启用 AI

<details>
<summary><b>离线安装 / Kubernetes 安装</b></summary>

<br/>

**离线安装** — 见 [官网安装页](https://databuff.ai/#install) 下载离线包：

```bash
tar -zxvf databuff-ai-apm-offline-<version>-<arch>.tar.gz
cd databuff-ai-apm-offline-<version>-<arch> && sudo ./install.sh
```

**Kubernetes 安装**

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-k8s-install.sh | bash
curl -fsSL https://databuff.ai/databuff/ai-apm-demo-k8s-install.sh | bash  # Demo 可选
```

</details>

---

## 文档

| 文档 | 说明 |
| :-- | :-- |
| [中文文档](https://databuff.ai/docs/zh/) | 产品介绍、使用手册、运维参考 |
| [OTLP 接入指南](docs/opentelemetry-otlp-ingestion.md) | OpenTelemetry SDK / Collector 接入 |
| [eBPF 接入](docs/使用手册/eBPF接入.md) | OBI DaemonSet，无侵入采集 HTTP / gRPC |
| [Nginx 接入](docs/使用手册/Nginx接入.md) | ngx_otel_module 上报入口链路 |
| [业界对比](docs/业界对比/总览.md) | 与 Jaeger、SigNoz、SkyWalking 等对比 |
| [迁移指南](docs/迁移指南/总览.md) | 从其他 APM 迁移到 DataBuff |

---

## 参与共建

如果你也在做 **AI 原生 APM / OpenTelemetry 可观测性**，欢迎一起把「最强的 OpenTelemetry APM 后端」做成真实可用的开源底座：

- ⭐ [Star 本仓库](https://github.com/databufflabs/databuff/stargazers) 并 Watch 更新
- 🐛 [提交 Issue](https://github.com/databufflabs/databuff/issues) 反馈 Bug 或功能需求
- 🤝 阅读 [CONTRIBUTING.md](CONTRIBUTING.md) 提交 PR
- 💬 扫码加入微信交流群，获取实时帮助

<p align="center" id="交流群">
  <img src="docs/images/community.png" alt="微信扫码加入 Databuff 开源交流群" width="128" />
  <br/>
  <sub>扫码加入 Databuff 开源交流群</sub>
</p>

---

## 版权与许可

本仓库遵循 **Apache-2.0** 开源协议。
