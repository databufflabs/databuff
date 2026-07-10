<p align="center">
  <a href="Agent集成.md">中文</a>
  &nbsp;|&nbsp;
  <a href="Agent集成_en.md">English</a>
</p>

# User Guide · Agent Integration

Connect external AI agents — **Cursor, Claude Code, OpenClaw/AMC** — to DataBuff via standard **MCP** to call real APM tools (services, traces, metrics, alerts, inspection) and install **Agent Skills** for query/inspection behavior rules.

**Three steps:** Prerequisites → Configure MCP → Install Skills

> This guide covers **DataBuff as MCP Server** for external agents. To attach external MCP services (SkyWalking, Prometheus, etc.) to digital experts **inside the platform UI**, see [External MCP Integration](外部MCP集成_en.md). Built-in AI chat (Brain, Query, Inspection experts) still uses the AgentScope **JAVA_BEAN** tool chain in parallel — unchanged by external MCP.

---

## Prerequisites

1. **DataBuff is deployed** and reachable from the agent environment (default Web port per install docs).
2. **MCP endpoint is available**: `GET/POST http://<host>:<port>/mcp` (Streamable HTTP, MCP standard path).
3. **Network security**: MVP has **no dedicated MCP API Token** — same as existing AI APIs; use intranet / VPN / reverse proxy. Add your own gateway/firewall for public exposure.
4. **Repo assets**: integration package at `integrations/agent/` (Skills + MCP config examples).

---

## ① Configure MCP

Copy an example config, replace `YOUR_DATABUFF_HOST:PORT` with your host — **no** `Authorization` header or API Token.

### Cursor

Copy `integrations/agent/mcp/cursor-mcp-example.json` (recommended — default port, tool list, inline comments) or `cursor-mcp.json.example` (minimal) to project `.cursor/mcp.json` or user-level MCP config:

```bash
mkdir -p .cursor
cp integrations/agent/mcp/cursor-mcp-example.json .cursor/mcp.json
```

Default local address (Docker install):

```
http://localhost:27403/mcp
```

For remote or non-default ports, set `url` to `http://<your-databuff-host>:<port>/mcp`.

In **Cursor → Settings → MCP**, `databuff-apm` should appear connected; confirm `url` is `http://<host>:<port>/mcp` with no `Authorization` header.

### Claude Code / Claude Desktop

Merge `integrations/agent/mcp/claude-desktop-config.example.json` into `claude_desktop_config.json` under `mcpServers`, same `/mcp` URL. In Claude Desktop **Settings → Developer**, confirm `databuff-apm` appears in the MCP server list.

### Exposed tools (15)

| Category | MCP tool names |
|----------|----------------|
| Common | `getCurrentTimeRange`, `getTimeRangeAroundTime`, `drawTrendCharts` |
| Query | `queryServicesAll`, `queryServicesByServiceType`, `queryServiceTopology`, `queryTraceListByCondition`, `queryTraceDetail`, `queryServiceAlarms`, `queryMetricData` |
| Logs | `queryLogTrend`, `queryLogDetail`, `queryLogsByTraceId`, `queryLogsBySpanId` |
| Inspection | `inspectService` |

Clients that only support legacy HTTP+SSE handshake may try `http://<host>:<port>/sse` (optional, not the primary documented path).

---

## ② Install Skills

Copy directories under `integrations/agent/skills/` to the client Skills path:

| Client | Target path |
|--------|-------------|
| **Cursor** | `~/.cursor/skills/` or project `.cursor/skills/` |
| **Claude Code** | `~/.claude/skills/` |

```bash
cp -r integrations/agent/skills/* ~/.cursor/skills/
```

| skillId | Purpose |
|---------|---------|
| `skill.data.metrics` | APM metrics, trace, and alert query semantics |
| `skill.inspection.health` | Service health inspection and diagnosis flow |

> Skills mirror `deploy/common/skills/`; re-copy after updating built-in skills.

After restarting the client, ask "List services in the last hour" in a new chat to verify skills are loaded (the agent should resolve time per `skill.data.metrics` and call MCP tools).

---

## ③ Example conversations

After config and client restart, ask directly:

| You say | Expected agent behavior |
|---------|-------------------------|
| "List services in the last hour" | Resolve time range, call `queryServicesAll` |
| "Any alerts on order-service recently?" | Call `queryServiceAlarms` per skill time rules |
| "Inspect health of order-service" | Load inspection skill, call `inspectService` |
| "Plot order-service error rate trend for the past hour" | `queryMetricData` + `drawTrendCharts` |

On success, the tool-call panel should show MCP tool names such as `queryServicesAll` with JSON service lists in the result (not fabricated prose).

**vs. platform UI**: Web AI chat registers JAVA_BEAN tools via AgentScope; external agents use MCP `tools/list` / `tools/call` against the **same** Spring Bean implementations — no need to check MCP under Digital Expert → Tools.

---

## OpenClaw / AMC

[OpenClaw](https://github.com/openclaw/openclaw) / OpenOcta **AMC marketplace** packages typically include:

- `config.json`: MCP service URL (SSE or Streamable HTTP)
- `skills/`: same layout as `integrations/agent/skills/`

This repo provides `integrations/agent/mcp/openclaw-amc-config.example.json` as reference — **no automated zip build** for marketplace upload. To package manually:

1. Set `mcp.url` in `config.json` to `http://<host>:<port>/mcp`
2. Copy `integrations/agent/skills/` into package `skills/`
3. Zip and upload per AMC docs (format per OpenOcta marketplace spec)

---

## FAQ

| Symptom | Fix |
|---------|-----|
| MCP connection failed | Verify `http://host:port/mcp` reachable; confirm ai-apm-web is up with new standard endpoint |
| Empty tool list | Check `tools/list` response; do not use deprecated `/api/v1/mcp/*` stubs |
| Agent does not call tools | Ensure MCP connected; install skills; start a new chat |
| Wrong query semantics | Install `skill.data.metrics`; restart client to load skills |
| Differs from platform UI experts | External = MCP; platform = JAVA_BEAN + expert binding — by design |
| Public exposure risk | MVP has no token — use intranet only; auth planned for later |

Transport: **Streamable HTTP** → `/mcp`; legacy **HTTP+SSE** → `/sse` (optional).

---

## Related docs

- [integrations/agent/README.md](../../integrations/agent/README.md) — package and quick start
- [External MCP Integration](外部MCP集成_en.md) — platform as MCP **client**
- [AI Platform architecture](../架构设计/AI平台_en.md) — open ecosystem and dual-path design
