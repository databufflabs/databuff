<div align="center">

<img src="ai-apm-frontend/public/img/logo_login.png" alt="DataBuff" height="56" />
&nbsp;&nbsp;
<img src="ai-apm-frontend/public/img/logo_wordmark.svg" alt="Databuff" height="32" />

<h3>DataBuff — AI-Native APM Built On Opentelemetry</h3>

<p><strong>Mission: build the strongest OpenTelemetry APM backend</strong></p>
<p>AI-native · OTLP-native · multi-agent out of the box · self-hosted traces, metrics, topology</p>

<p align="center">
  <a href="https://demo.databuff.ai">Live Demo</a>
  &nbsp;·&nbsp;
  <a href="docs/README_en.md">Documentation</a>
  &nbsp;·&nbsp;
  <a href="https://databuff.ai/en/opensource-apm/">Open Source APM</a>
  &nbsp;·&nbsp;
  <a href="README.cn.md">简体中文</a>
  &nbsp;·&nbsp;
  <a href="#community">Community</a>
</p>

<p align="center">
  <img src="https://img.shields.io/github/stars/databufflabs/databuff?style=social" />
  <img src="https://img.shields.io/badge/License-Apache--2.0-blue.svg" />
  <img src="https://img.shields.io/badge/AI--Native-APM-7C3AED?style=flat-square" />
  <img src="https://img.shields.io/badge/OpenTelemetry-Native_OTLP-000000?style=flat-square&logo=opentelemetry&logoColor=white" />
  <img src="https://img.shields.io/badge/Multi--Agent-Out_of_the_box-2563EB?style=flat-square" />
  <img src="https://img.shields.io/badge/Docker-One_command-2496ED?style=flat-square&logo=docker&logoColor=white" />
</p>

<p align="center">Demo login: <code>admin</code> / <code>Databuff@123</code></p>

</div>

<p align="center">
  <img src="docs/images/databuff-demo-en.gif" alt="DataBuff demo: AI chat, services, topology" width="880" />
</p>
<p align="center"><sub>AI multi-agent troubleshooting · Service health · Call graph topology</sub></p>

**Keywords**: `AI-Native APM` · `OpenTelemetry APM` · `OTLP Backend` · `Multi-Agent` · `Distributed Tracing` · `AIOps` · `Open Source APM` · `MCP` · `Self-Hosted Observability`

---

## What is DataBuff

**DataBuff is an AI-native APM backend built on Opentelemetry** — ingest traces, metrics, and logs via OTLP, with full-stack monitoring, service topology, RED metrics, and **out-of-the-box multi-agent AI troubleshooting**.

> The goal is not "yet another observability UI," but **to build the strongest OpenTelemetry APM backend** — letting LLMs query live telemetry directly, from natural-language query → multi-agent inspection → root-cause analysis → ops remediation.

Listed on [OpenTelemetry official Vendors](https://opentelemetry.io/ecosystem/vendors/) (Native OTLP) and [CNCF Landscape](https://landscape.cncf.io/?item=observability-and-analysis--observability--databuff).

[⭐ Star the project](https://github.com/databufflabs/databuff) · [Live Demo](https://demo.databuff.ai) · [Documentation](docs/README_en.md)

---

## Why DataBuff

If you are evaluating an OpenTelemetry APM backend, or want AI that actually closes the troubleshooting loop, DataBuff is built for you:

- **AI-native, not bolted on** — LLMs query traces, metrics, topology, and alerts directly; add an API key after install, no separate AI platform needed
- **Multi-agent out of the box** — AI Brain orchestrates query, inspection, ops, and Q&A experts in parallel
- **Fully embrace OpenTelemetry** — OTLP-native ingestion with existing instrumentation as-is; SkyWalking compatible for smooth migration
- **Clear mission** — build the strongest OpenTelemetry APM backend: self-hosted, production-grade, extensible
- **Minimal deployment** — Ingest + Doris + Web, one Docker command to run

---

## Key Features

### 🤖 AI-Native
- **Not a bolt-on chat box** — LLM answers from real telemetry, not hallucination
- **Multi-agent collaboration out of the box** — AI Brain orchestrates query, inspection, ops, and Q&A experts
- **AI application observability** (Roadmap) — LLM call chains · token analytics · agent topology · skill/tool/model tracing
- **MCP both ways** — expose capabilities to Cursor / Claude; ingest external MCPs like Prometheus
- **Bring your own model** — Kimi, DeepSeek, GLM, Ollama, and other OpenAI-compatible APIs

### 📊 OpenTelemetry-Native
- **OTLP-native ingestion** — gRPC `4317` / HTTP `4318` for Traces + Metrics + Logs
- **eBPF APM** — kernel-level, non-intrusive collection; call chains and performance data without code changes
- **Dual-protocol support** — OTLP native + SkyWalking native gRPC (`11800`); switch by changing exporter address
- **Alerting loop** — threshold detection, scheduled evaluation, alert event history

### 🐳 Engineering Foundation
- **3-component stack** — Ingest + Doris + Web, no middleware sprawl
- **Skill extensibility** — custom digital experts without touching core code

---

## Feature Gallery

Out-of-the-box multi-agent AI on top of a full OpenTelemetry APM.

<img src="docs/images/screenshots/aiops-arc-en.svg" alt="AIOps roadmap: Visible → Legion → Inspect → Diagnose → Repair → Predict → Answer" width="900" />

#### Natural Language Query

Ask "which service was slowest" in plain language — AI ranks results, no query language required.

<img src="docs/images/screenshots/nl-slowest.png" alt="Natural language query" width="720" />

#### Multi-Agent Collaboration

Complex tasks dispatched to multiple experts in parallel, synthesized into a forwardable incident report.

<img src="docs/images/screenshots/multi-agent-process.png" alt="Multi-agent collaboration" width="720" />

#### Root Cause Analysis

Pull topology, rank metrics, attribute bottlenecks by share — conclusions ready for your incident report.

<img src="docs/images/screenshots/rca.png" alt="Root cause analysis" width="720" />

#### APM UI

Global topology, service list, and trace drill-down — AI reads the data, the UI confirms it.

<img src="docs/images/screenshots/global-topology.jpg" alt="Global topology" width="720" />

More capabilities in [Documentation](docs/README_en.md)

---

## Architecture & Ingestion

<img src="docs/images/screenshots/simple-architecture.jpg" alt="Minimal architecture: Ingest + Doris + Web" width="900" />

| Protocol | Port / Endpoint | Signals |
| :-- | :-- | :-- |
| **OTLP** (OpenTelemetry native) | gRPC `4317` · HTTP `4318` | Traces + Metrics + Logs |
| **SkyWalking** native gRPC | gRPC `11800` | Trace + JVM metrics + Logs |

---

## Quick Start in 5 Minutes

1. **Install platform** — one command to launch Ingest + Doris + Web

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-install.sh | bash
```

2. **Install demo** (optional) — auto-report traces and see topology quickly

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-demo-install.sh | bash
```

3. **Connect a model → start troubleshooting** — open `http://YOUR_HOST:27403`, login `admin` / `Databuff@123`, add API key to enable AI

<details>
<summary><b>Offline install / Kubernetes install</b></summary>

<br/>

**Offline install** — download the bundle from the [install page](https://databuff.ai/#install):

```bash
tar -zxvf databuff-ai-apm-offline-<version>-<arch>.tar.gz
cd databuff-ai-apm-offline-<version>-<arch> && sudo ./install.sh
```

**Kubernetes install**

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-k8s-install.sh | bash
curl -fsSL https://databuff.ai/databuff/ai-apm-demo-k8s-install.sh | bash  # demo optional
```

</details>

---

## Documentation

| Doc | Description |
| :-- | :-- |
| [Documentation](docs/README_en.md) | Product overview, user guides, ops reference |
| [OTLP ingestion guide](docs/opentelemetry-otlp-ingestion_en.md) | OpenTelemetry SDK / Collector setup |
| [Competitive comparison](docs/业界对比/总览_en.md) | vs Jaeger, SigNoz, SkyWalking, and more |
| [Migration guide](docs/迁移指南/总览_en.md) | Migrate from other APM tools |

---

## Contributing

If you are building **AI-native APM / OpenTelemetry observability**, help make the strongest OpenTelemetry APM backend a real, usable open-source foundation:

- ⭐ [Star this repo](https://github.com/databufflabs/databuff/stargazers) and Watch for updates
- 🐛 [Open an Issue](https://github.com/databufflabs/databuff/issues) to report bugs or request features
- 🤝 Read [CONTRIBUTING.md](CONTRIBUTING.md) and submit a PR
- 💬 Scan the QR code to join our WeChat community

<p align="center" id="community">
  <img src="docs/images/community.png" alt="Scan to join the DataBuff community on WeChat" width="128" />
  <br/>
  <sub>Scan to join the DataBuff community</sub>
</p>

---

## License

This repository is licensed under **Apache-2.0**.
