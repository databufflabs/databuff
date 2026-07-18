<div align="center">

<p align="center">
  <img src="ai-apm-frontend/public/img/logo_login.png" alt="" height="56" align="middle" />
  &nbsp;&nbsp;
  <img src="ai-apm-frontend/public/img/logo_wordmark.svg" alt="Databuff" height="32" align="middle" />
</p>

<h3>Open Source · AI-Native OpenTelemetry APM</h3>

<p><strong>Ask one question in plain English — AI agents query metrics, traces, and topology together, then tell you what broke. The self-healing ops loop observes and diagnoses first; autonomous remediation is on the Roadmap.</strong></p>

<p align="center">
  <a href="https://demo.databuff.ai">Live Demo</a>
  &nbsp;|&nbsp;
  <a href="https://github.com/databufflabs/databuff/releases">Releases</a>
  &nbsp;|&nbsp;
  <a href="docs/README_en.md">Documentation</a>
  &nbsp;|&nbsp;
  <a href="README.md">中文</a>
  &nbsp;|&nbsp;
  <a href="#community">Community</a>
</p>
<p align="center">
  Username:Admin, Password:Databuff@123
</p>
<h2>Features</h2>

- 🤖 **AI-native, not a bolt-on chat box** — LLM queries traces, metrics, topology, and alerts directly from real data
- 🧠 **Multi-agent collaboration** — AI Brain orchestrates query and inspection experts; complex tasks run in parallel
- 🎯 **AI application observability** (Roadmap) — LLM call chains · token analytics · agent topology · skill/tool/model tracing
- ⚡ **eBPF APM** (Roadmap) — kernel-level, non-intrusive collection without code changes
- 📊 **OpenTelemetry APM foundation** — OTLP ingestion with troubleshooting, tracing, service metrics, and topology
- 🔗 **SkyWalking native ingest** — gRPC :11800, keep your existing Java Agent jar, point `collector.backend_service` at DataBuff for traces / JVM metrics / topology / log correlation
- 🚨 **Alerting loop** — threshold and change detection, scheduled evaluation, alert event history
- 🛠 **Ops Expert · SSH troubleshooting** — install recovery and live runtime root-cause via read-only SSH on target hosts; built-in `expertId=ops`, guarded by a shell denylist (`rm`, `dd`, `sudo`, `iptables`, …) so the agent can only triage, not mutate
- 🔄 **Self-Healing Ops loop** — observe and diagnose first: AI inspection experts triage anomalies and suggest fixes; autonomous inspect → diagnose → remediate is the long-term vision on the Roadmap
- 🔧 **Skill + Tool extensibility** — override built-in skills, add custom digital experts without touching core code
- 🔌 **MCP both ways** — expose platform capabilities to Cursor / Claude; ingest external MCPs (Prometheus, SkyWalking, etc.)
- 🐳 **Minimal 3-component stack** — Ingest + Doris + Web; one Docker / K8s command, no middleware sprawl
- 🌐 **Bring your own model** — OpenAI-compatible + Anthropic Messages; Kimi, DeepSeek, GLM, Bailian, Qianfan, Ollama, and more
</div>

<br/>

<p align="center">
  <img src="docs/images/databuff-demo-en.gif" alt="DataBuff demo: AI chat, services, topology" width="880" />
</p>
<p align="center"><sub>AI multi-agent troubleshooting · Service health · Call graph topology — English UI</sub></p>

<br/>

<p align="center">
  <img src="docs/images/feature-pillars-en.png" alt="OpenTelemetry APM and AI Native capabilities" width="880" />
</p>

---

<h2 align="center">Why DataBuff?</h2>

| | **DataBuff** | SigNoz | Datadog |
|---|:---:|:---:|:---:|
| AI multi-agent root cause | ✅ Built-in | ❌ | Add-on |
| OpenTelemetry native | ✅ | ✅ | Partial |
| Self-host · 5 min Docker | ✅ `curl \| bash` | ✅ | ❌ SaaS |
| Distributed tracing + topology | ✅ | ✅ | ✅ |
| Open source | ✅  | ✅ | ❌ |

> **One-liner:** Open-source APM where an **AI brain** dispatches metric, trace, and inspection experts — not another chat box on top of dashboards.

---

<h2 align="center" id="screenshots">Screenshots</h2>

<p align="center"><strong>AI Analysis</strong></p>

<table border="0" cellspacing="12" cellpadding="0" align="center">
<tr>
<td align="center" width="450">
  <img src="docs/images/screenshots/ai-interaction-1.jpg" alt="AI natural language query" width="450" />
  <br/><sub>Natural language query · Metrics and traces in plain language</sub>
</td>
<td align="center" width="450">
  <img src="docs/images/screenshots/ai-interaction-2.jpg" alt="Multi-agent collaboration" width="450" />
  <br/><sub>Multi-agent collaboration · Evidence synthesis and conclusions</sub>
</td>
</tr>
</table>

<p align="center"><strong>APM Observability</strong></p>

<table border="0" cellspacing="12" cellpadding="0" align="center">
<tr>
<td align="center" width="450">
  <img src="docs/images/screenshots/service-list.jpg" alt="Service list" width="450" />
  <br/><sub>Service list · Traffic-light status for anomalies</sub>
</td>
<td align="center" width="450">
  <img src="docs/images/screenshots/global-topology.jpg" alt="Global topology" width="450" />
  <br/><sub>Global topology · Auto-generated call graph</sub>
</td>
</tr>
<tr>
<td align="center" width="450">
  <img src="docs/images/screenshots/service-detail.jpg" alt="Service detail" width="450" />
  <br/><sub>Service detail · Metric trends and instances</sub>
</td>
<td align="center" width="450">
  <img src="docs/images/screenshots/service-flow.jpg" alt="Service flow" width="450" />
  <br/><sub>Service flow · Upstream and downstream dependencies</sub>
</td>
</tr>
</table>

---

<h2 align="center">Architecture</h2>

<p align="center">
  <img src="docs/images/screenshots/simple-architecture.jpg" alt="Architecture" width="920" />
</p>

---

<h2 align="center" id="installation">Quick Start</h2>

> ⚡ From running the install command to demo apps reporting data and showing traces and topology, you can see results in about **5 minutes**.

<p align="center">
  <img src="https://img.shields.io/badge/Docker-docker_+_compose-2496ED?style=for-the-badge&logo=docker&logoColor=white" height="28" />
</p>

Requires **docker** and **docker-compose**. The install script auto-detects amd64/arm64 and downloads the matching image bundle.

**1. Install Platform**

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-install.sh | bash
```

**2. Upgrade existing Docker install** (v0.1.3+, keeps `data/`)

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-update.sh | bash
```

**3. Install Demo App** (optional)

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-demo-install.sh | bash
```

> Latest release: **[v0.1.3 — SkyWalking Native Ingest + Ops Expert SSH Troubleshooting](https://github.com/databufflabs/databuff/releases/tag/0.1.3)**

<details>
<summary><b>Offline Install</b></summary>

When the registry is unreachable, download the bundle for your architecture and install on the target machine. Pick a version on the [install page](https://databuff.ai/#install) under **Docker → Offline Install**, or use:

`https://openocta.com/pkg/databuff/<version>/offline/databuff-ai-apm-offline-<version>-<arch>.tar.gz`

```bash
tar -zxvf databuff-ai-apm-offline-<version>-<arch>.tar.gz
cd databuff-ai-apm-offline-<version>-<arch>

# Install platform
sudo ./install.sh
```

</details>

<details>
<summary><b>Kubernetes</b></summary>

Requires **kubectl** and a working Kubernetes cluster. The script installs the platform via K8s manifests.

**1. Install Platform**

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-k8s-install.sh | bash
```

**2. Install Demo App** (optional)

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-demo-k8s-install.sh | bash
```

**Offline image download**

If the install commands above cannot pull images due to network issues, run the following to download an offline image bundle and load it onto the node.

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-k8s-download-images.sh | bash
```

</details>

<p align="center">
  Open <code>http://YOUR_HOST:27403</code> · Default login <code>admin</code> / <code>Databuff@123</code> · Add your API key in model settings to enable AI
  <br/>
</p>

---

<h2 align="center" id="community">Community & Contributing</h2>

<p align="center">
  <a href="CONTRIBUTING.md">Contributing Guide</a>
  &nbsp;·&nbsp;
  <a href="https://github.com/databufflabs/databuff/issues">Open Issues</a>
  &nbsp;·&nbsp;
  <a href="https://github.com/databufflabs/databuff/discussions">Discussions</a>
  &nbsp;·&nbsp;
  <a href="https://github.com/databufflabs/databuff/labels/good%20first%20issue">Good First Issues</a>
</p>

<p align="center">
  See <a href="CONTRIBUTING.md">CONTRIBUTING.md</a> to learn how to submit PRs, report bugs, or request features.
  <br/>
  Join our WeChat community for real-time help 👇
</p>

<p align="center">
  <img src="docs/images/community.png" alt="WeChat community QR code" width="128" />
</p>

<br/>
