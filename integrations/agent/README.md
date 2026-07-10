# DataBuff Agent 集成

将 DataBuff APM 能力接入外部 AI Agent：**MCP 工具**（查指标、Trace、拓扑、告警、巡检）+ **Agent Skills**（问数口径、巡检流程）。

适用客户端：Cursor、Claude Code、OpenClaw / OpenOcta AMC 等。

## 前提

1. DataBuff 已部署且可访问（默认 Web 端口见安装文档）
2. MCP 端点与现有 AI API 一样**无独立 Token**；请在内网或受控网络中使用（公网暴露请自行加网关/防火墙）

## 快速开始

### 1. 安装 Skills

将本目录下 `skills/` 中的包复制到客户端 Skills 目录：

| 客户端 | 目标路径 |
|--------|----------|
| **Cursor** | `~/.cursor/skills/` 或项目 `.cursor/skills/` |
| **Claude Code** | `~/.claude/skills/` |

```bash
# 示例：安装到 Cursor 用户级 skills
cp -r integrations/agent/skills/* ~/.cursor/skills/
```

内置 Skills：

| skillId | 用途 |
|---------|------|
| `skill.data.metrics` | APM 指标、Trace、告警查询规则 |
| `skill.inspection.health` | 服务健康巡检与异常诊断流程 |

> **同步说明**：Skills 内容与 `deploy/common/skills/` 保持一致。更新内置 Skill 后请重新 copy，或运行：
>
> ```bash
> cp -r deploy/common/skills/skill.data.metrics integrations/agent/skills/
> cp -r deploy/common/skills/skill.inspection.health integrations/agent/skills/
> ```

### 2. 配置 MCP

复制 `mcp/` 下的示例配置，填入你的 DataBuff 地址即可（无需 Authorization 头）：

- **Cursor（推荐）**：`mcp/cursor-mcp-example.json` → `.cursor/mcp.json`（含默认 `localhost:27403`、工具列表与注释说明）
- Cursor（精简版）：`mcp/cursor-mcp.json.example` → `.cursor/mcp.json`
- Claude Desktop：`mcp/claude-desktop-config.example.json`

```bash
# 示例：项目级 MCP 配置（本地 Docker 默认端口）
mkdir -p .cursor
cp integrations/agent/mcp/cursor-mcp-example.json .cursor/mcp.json
```

标准 MCP 端点（Streamable HTTP，实现后）：

```
http://<your-databuff-host>:<port>/mcp
```

旧版仅支持 HTTP+SSE 的客户端可尝试 `http://<host>:<port>/sse`（可选实现，见方案设计）。

### 3. 验证

在 Agent 对话中提问，例如：

- 「列出最近 1 小时的服务」
- 「巡检 order-service 的健康状况」

Agent 应调用 `queryServicesAll`、`inspectService` 等 MCP 工具，并遵循已安装 Skills 中的口径规则。

## 目录结构

```
integrations/agent/
├── README.md                 # 本文件
├── skills/                   # 可安装的 Agent Skills 包
│   ├── skill.data.metrics/
│   └── skill.inspection.health/
└── mcp/                      # MCP 客户端配置示例
    ├── cursor-mcp-example.json          # Cursor 完整示例（推荐）
    ├── cursor-mcp.json.example          # Cursor 精简示例
    ├── claude-desktop-config.example.json
    └── openclaw-amc-config.example.json
```

## 相关文档

- [Agent 集成使用手册](../../docs/使用手册/Agent集成.md)
- [外部 MCP 集成](../../docs/使用手册/外部MCP集成.md)（平台作 MCP **客户端**）
- [AI 平台架构](../../docs/架构设计/AI平台.md)

## OpenClaw / AMC

OpenOcta AMC 市场包格式可参考历史任务：zip 内含 `config.json`（SSE url）+ skills 目录。本仓库暂不自动打包；见使用手册中的 AMC 说明。
