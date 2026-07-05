<div align="center">

<p align="center">
  <img src="ai-apm-frontend/public/img/logo_login.png" alt="" height="56" align="middle" />
  &nbsp;&nbsp;
  <img src="ai-apm-frontend/public/img/logo_wordmark.svg" alt="Databuff" height="32" align="middle" />
</p>

<h3>AI Native OpenTelemetry APM</h3>

<p align="center">
  <a href="https://demo.databuff.ai">在线演示</a>
  &nbsp;|&nbsp;
  <a href="docs/README.md">文档</a>
  &nbsp;|&nbsp;
  <a href="README_en.md">English</a>
  &nbsp;|&nbsp;
  <a href="#交流群">交流群</a>
</p>
<p align="center">
  在线演示 Demo，需要加入下方交流群获取账号密码
</p>

</div>

<br/>

<p align="center">
  <img src="docs/images/feature-pillars.png" alt="OpenTelemetry APM 与 AI Native 能力概览" width="880" />
</p>

<br/>

---
DataBuff 是一款面向AI智能体、微服务、云原生场景的** AI原生开源 APM 软件**，以 Opentelemetry 标准接入，提供**全链路监控**、服务拓扑、RED 指标、智能体监控 与 AI 工作台。

## 功能特性

- 🤖 **AI 原生，不是外挂聊天框** — LLM 直接查询 Trace、指标、拓扑、告警，回答基于真实数据
- 🧠 **多智能体协同** — AI 大脑统一编排，智能问数 / 巡检专家各司其职，复杂问题并行协作
- 🎯 **AI 应用监控** — LLM 调用链 · Token 分析 · Agent 拓扑 · 技能/工具/模型调用追踪 —— 智能体时代独有观测层（Roadmap）
- ⚡ **eBPF APM** — 采用eBPF 实现内核级无侵入采集，零修改代码获取服务调用链与性能数据，降低 Agent 部署阻力，覆盖更多运行环境（Roadmap）
- 📊 **OpenTelemetry APM 底座** — OTLP 标准接入，覆盖故障排查、链路追踪、服务指标、服务拓扑
- 🔧 **Skill + Tool 可扩展** — 内置 Skill 可覆盖，支持自定义数字专家，无需改核心代码
- 🔌 **MCP 双向开放** — 平台暴露 MCP 供 Cursor / Claude 等调用；也可接入外部 MCP（Prometheus、SkyWalking 等）
- 🐳 **极简三组件架构** — Ingest + Doris + Web，Docker / K8s 一条命令跑起来
---

<h2 align="center" id="效果展示">效果展示</h2>

<p align="center"><strong>AI 分析</strong></p>

<table border="0" cellspacing="12" cellpadding="0" align="center">
<tr>
<td align="center" width="450">
  <img src="docs/images/screenshots/ai-interaction-1.jpg" alt="AI 智能问数" width="450" />
  <br/><sub>智能问数 · 自然语言查指标与 Trace</sub>
</td>
<td align="center" width="450">
  <img src="docs/images/screenshots/ai-interaction-2.jpg" alt="AI 多 Agent 协同" width="450" />
  <br/><sub>多 Agent 协同 · 汇总证据给出结论</sub>
</td>
</tr>
</table>

<p align="center"><strong>APM 可观测</strong></p>

<table border="0" cellspacing="12" cellpadding="0" align="center">
<tr>
<td align="center" width="450">
  <img src="docs/images/screenshots/service-list.jpg" alt="服务列表" width="450" />
  <br/><sub>服务列表 · 红绿灯锁定异常</sub>
</td>
<td align="center" width="450">
  <img src="docs/images/screenshots/global-topology.jpg" alt="全局拓扑" width="450" />
  <br/><sub>全局拓扑 · 自动绘制调用关系</sub>
</td>
</tr>
<tr>
<td align="center" width="450">
  <img src="docs/images/screenshots/service-detail.jpg" alt="服务详情" width="450" />
  <br/><sub>服务详情 · 指标趋势与实例</sub>
</td>
<td align="center" width="450">
  <img src="docs/images/screenshots/service-flow.jpg" alt="服务流" width="450" />
  <br/><sub>服务流 · 上下游依赖</sub>
</td>
</tr>
</table>

---

<h2 align="center">极简架构</h2>

<p align="center">
  <img src="docs/images/screenshots/simple-architecture.jpg" alt="极简架构" width="920" />
</p>

---

<h2 align="center" id="安装">快速安装</h2>

> ⚡ 从执行安装命令到 Demo 应用上报数据、看到链路追踪与拓扑，约 **5 分钟** 即可出效果。

<p align="center">
  <img src="https://img.shields.io/badge/Docker-docker_+_compose-2496ED?style=for-the-badge&logo=docker&logoColor=white" height="28" />
</p>

依赖 **docker**、**docker-compose**；安装脚本自动识别 amd64/arm64，下载对应镜像包。

**1. 安装平台**

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-install.sh | bash
```

**2. 安装 Demo 应用**（可选）

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-demo-install.sh | bash
```

<details>
<summary><b>Kubernetes 安装</b></summary>

依赖 **kubectl** 与可用 K8s 集群；脚本通过 K8s manifest 直装平台。

**1. 安装平台**

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-k8s-install.sh | bash
```

**2. 安装 Demo 应用**（可选）

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-demo-k8s-install.sh | bash
```

**离线镜像下载**

若上方安装命令因网络问题无法拉取镜像，可执行以下命令下载离线镜像包，并自动 load 到节点。

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-k8s-download-images.sh | bash
```

</details>

<p align="center">
  访问 <code>http://YOUR_HOST:27403</code> · 模型配置填入 API Key 启用 AI
  <br/>
</p>

---

<h2 align="center" id="交流群">交流群</h2>

<p align="center">
  微信扫码加入 <strong>Databuff 开源交流群</strong>
  <br/><br/>
  <img src="docs/images/community.png" alt="微信扫码加入交流群" width="128" />
</p>

<br/>
