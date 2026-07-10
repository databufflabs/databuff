<p align="center">
  <a href="Agent集成.md">中文</a>
  &nbsp;|&nbsp;
  <a href="Agent集成_en.md">English</a>
</p>

# 使用手册 · Agent 集成

把 **Cursor、Claude Code、OpenClaw/AMC** 等外部 AI Agent 接到 DataBuff，通过标准 **MCP 协议**调用真实 APM 工具（查服务、Trace、指标、告警、巡检），并安装 **Agent Skills** 获得问数/巡检行为规则。

**三步完成：** 确认前提 → 配置 MCP → 安装 Skills

> 本文描述 **DataBuff 作 MCP Server**，供外部 Agent 调用平台能力。若要在 **平台 UI 内**把 SkyWalking、Prometheus 等外部 MCP 挂到数字专家上，见 [外部 MCP 集成](外部MCP集成.md)。平台内置 AI 对话（AI 大脑、问数、巡检专家）仍走 AgentScope **JAVA_BEAN** 工具链，与外部 MCP 通路并行、互不影响。

---

## 前提

1. **DataBuff 已部署**且 Agent 所在环境能访问 Web 端口（默认见安装文档）。
2. **MCP 端点已实现**：`GET/POST http://<host>:<port>/mcp`（Streamable HTTP，MCP 规范标准路径）。
3. **网络安全**：MVP **无独立 MCP API Token**，与现有 AI API 一致，依赖内网 / VPN / 反向代理隔离；公网暴露请自行加网关或防火墙。
4. **仓库资源**：集成包位于 `integrations/agent/`（Skills + MCP 配置示例）。

---

## ① 配置 MCP

复制示例配置，将 `YOUR_DATABUFF_HOST:PORT` 换成实际地址即可，**无需** `Authorization` 头或 API Token。

### Cursor

将 `integrations/agent/mcp/cursor-mcp-example.json`（推荐，含默认端口与工具说明）或 `cursor-mcp.json.example`（精简版）复制为项目 `.cursor/mcp.json` 或用户级 MCP 配置：

```bash
mkdir -p .cursor
cp integrations/agent/mcp/cursor-mcp-example.json .cursor/mcp.json
```

默认本地地址（Docker 安装）：

```
http://localhost:27403/mcp
```

远程或非默认端口时，将 `url` 改为 `http://<your-databuff-host>:<port>/mcp`。

**Cursor → Settings → MCP** 中应出现 `databuff-apm` 服务且状态为已连接；确认 `url` 为 `http://<host>:<port>/mcp`（无 `Authorization` 头）。

### Claude Code / Claude Desktop

将 `integrations/agent/mcp/claude-desktop-config.example.json` 合并进 `claude_desktop_config.json` 的 `mcpServers` 段，同样使用 `/mcp` URL。在 Claude Desktop **Settings → Developer** 中确认 `databuff-apm` 出现在 MCP 服务列表。

### 暴露的工具（15 个）

| 类别 | MCP 工具名 |
|------|------------|
| 通用 | `getCurrentTimeRange`、`getTimeRangeAroundTime`、`drawTrendCharts` |
| 问数 | `queryServicesAll`、`queryServicesByServiceType`、`queryServiceTopology`、`queryTraceListByCondition`、`queryTraceDetail`、`queryServiceAlarms`、`queryMetricData` |
| 日志 | `queryLogTrend`、`queryLogDetail`、`queryLogsByTraceId`、`queryLogsBySpanId` |
| 巡检 | `inspectService` |

旧版仅支持 HTTP+SSE 握手的客户端可尝试 `http://<host>:<port>/sse`（可选实现，非文档主推路径）。

---

## ② 安装 Skills

将 `integrations/agent/skills/` 下目录复制到客户端 Skills 路径：

| 客户端 | 目标路径 |
|--------|----------|
| **Cursor** | `~/.cursor/skills/` 或项目 `.cursor/skills/` |
| **Claude Code** | `~/.claude/skills/` |

```bash
cp -r integrations/agent/skills/* ~/.cursor/skills/
```

| skillId | 用途 |
|---------|------|
| `skill.data.metrics` | APM 指标、Trace、告警查询口径 |
| `skill.inspection.health` | 服务健康巡检与异常诊断流程 |

> Skills 内容与 `deploy/common/skills/` 保持一致；更新内置 Skill 后请重新 copy。

重启客户端后，可在新对话中提问「列出最近 1 小时的服务」验证 Skill 是否生效（Agent 应按 `skill.data.metrics` 口径解析时间并调用 MCP 工具）。

---

## ③ 示例对话

配置完成并重启客户端后，在 Agent 中直接提问：

| 你说 | Agent 预期行为 |
|------|----------------|
| 「列出最近 1 小时的服务」 | 先解析时间范围，调用 `queryServicesAll` |
| 「order-service 最近有没有告警？」 | 调用 `queryServiceAlarms`，按 Skill 口径填时间 |
| 「巡检 order-service 的健康状况」 | 加载巡检 Skill，调用 `inspectService` |
| 「画一下过去 1 小时 order-service 的错误率趋势」 | `queryMetricData` + `drawTrendCharts` |

验证成功时，工具调用面板应显示 `queryServicesAll` 等 MCP 工具名，返回 JSON 服务列表（非编造文本）。

**与平台 UI 的区别**：Web 端 AI 对话由 AgentScope 注册 JAVA_BEAN 工具；外部 Agent 通过 MCP `tools/list` / `tools/call` 调用**同一套** Spring Bean 实现，无需在「数字专家 → Tools」里勾选 MCP。

---

## OpenClaw / AMC 说明

[OpenClaw](https://github.com/openclaw/openclaw) / OpenOcta **AMC 市场**包通常包含：

- `config.json`：MCP 服务地址（SSE 或 Streamable HTTP）
- `skills/`：与 `integrations/agent/skills/` 同结构的 Skill 目录

本仓库提供参考配置 `integrations/agent/mcp/openclaw-amc-config.example.json`，**不自动打包** zip 上传市场。自行打包时：

1. 将 `config.json` 中 `mcp.url` 设为 `http://<host>:<port>/mcp`
2. 将 `integrations/agent/skills/` 拷入包内 `skills/`
3. 按 AMC 文档压缩并上传（具体格式以 OpenOcta 市场规范为准）

---

## 常见问题

| 现象 | 处理 |
|------|------|
| MCP 连接失败 | 核对 `http://host:port/mcp` 可达；确认 ai-apm-web 已启动且实现新标准端点 |
| 工具列表为空 | 检查 `tools/list` 响应；勿使用已废弃的 `/api/v1/mcp/*` 桩端点 |
| Agent 不调工具 | 确认 MCP 已连接；安装对应 Skill；新开一条对话 |
| 问数口径不对 | 确认已安装 `skill.data.metrics`；重启客户端加载 Skills |
| 与平台 UI 专家行为不一致 | 外部走 MCP；平台内走 JAVA_BEAN + 专家绑定，两套通路设计如此 |
| 公网安全风险 | MVP 无 Token，仅建议内网使用；认证能力规划在后续版本 |

传输协议：**Streamable HTTP** 使用 `/mcp`；旧版 **HTTP+SSE** 使用 `/sse`（可选）。

---

## 相关文档

- [integrations/agent/README.md](../../integrations/agent/README.md) — 集成包与快速开始
- [外部 MCP 集成](外部MCP集成.md) — 平台作 MCP **客户端**
- [AI 平台架构](../架构设计/AI平台.md) — 开放生态与双通路设计
