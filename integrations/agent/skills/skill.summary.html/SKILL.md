---
name: skill.summary.html
description: 总结与报告 HTML 产出规范（共享）；何时写文件、如何参考风格模版
---
# 总结产出

你可以使用会话工作区工具写出可下载、可预览的交付物。本 Skill 适用于**所有数字专家**。

## 何时写文件

| 场景 | 行为 |
|------|------|
| 用户明确说「生成报告」「导出」「写成总结/HTML」等 | **必须**用 `writeWorkspaceFile` 写入 `outputs/` |
| **服务巡检**且结论较完整 | **必须**用 `writeWorkspaceFile` 写出 HTML 巡检报告到 `outputs/`，**不要**先征求同意 |
| 长分析、多指标汇总、根因定位、故障诊断等（**非巡检**） | **不要**主动建议或生成 HTML，直接在对话中回答 |
| 短问答、单点查询、澄清问题 | **不要**写文件，直接在对话中回答 |

## 模版一览（`templates/`）

多数 HTML **只作色系与观感参考**；`inspection-report.html` 是巡检报告的**结构模版**（无数据章节可删，勿编造）。

| 文件 | 适用场景 |
|------|----------|
| `inspection-report.html` | **服务巡检 / 健康检查**报告 |
| `report-analysis.html` | 长分析、根因报告、多指标汇总 |
| `summary-brief.html` | 短总结、一两屏结论 |
| `trend-chart-snippet.html` | **含趋势图的 HTML** 必读：ECharts 嵌入结构与 `renderTrendChart` 示例 |

读取路径：`resources/skill.summary.html/templates/{文件名}`

### 选模版与读取

- **按文件名直接选定**：对照上表场景即可，**不要** `listWorkspaceFiles` 浏览、不要逐个试读或对比多个模版。
- 一般 **只选一个** 模版。首次调用 `readWorkspaceFile` 不传 `lineRange`，默认读取 `1-9999` 行；若文件仍有内容，再按后续 `lineRange` 继续读取，直至读完。
- **同一任务/同一轮对话内**：禁止对同一文件的同一段 `lineRange` 重复读取；允许读取该文件尚未读取的后续范围。
- 巡检场景固定用 `inspection-report.html`，不必再看其它模版。

## 怎么写

1. 主交付物优先 **自包含 HTML**（`.html`），写入 `outputs/{slug}.html`。
2. 写 HTML 前，按上表选定模版并读完，作为色系/排版或结构参考。
3. **巡检报告**：按 `inspection-report.html` 章节结构产出；用 `inspectService` 与补充查询的真实数据填充，**禁止编造**。
4. 其它模版不是填空卷：结构、章节、组件可自定。
5. 证据表、CSV、中间数据可用 `.csv` / `.md` / `.json`，但面向阅读的主结论用 HTML。
6. 写完后用一句话告知用户文件名，便于其在对话中点击预览。

## 趋势图嵌入 HTML

`drawTrendCharts` **只渲染对话区**，不会自动写入 `outputs/` 的 HTML。报告里若有趋势章节，**必须**在 HTML 内嵌可执行图表，禁止留空 `<div>` 占位。

### 何时需要读 `trend-chart-snippet.html`

- 用户要求「趋势报告」「带图的 HTML」「请求量/错误率趋势」等，且会写出 `.html` 文件时。
- 巡检报告需要展示指标/日志趋势时。

### 写作步骤

1. 用 `queryMetricData` / `queryLogTrend` 等拿到真实时序数据（`labels` + `values`）。
2. 对话区可照常调用 `drawTrendCharts` 供用户即时看图。
3. 写 HTML 前 **必读** `resources/skill.summary.html/templates/trend-chart-snippet.html`（与其它模版一样一次读完）。
4. 在最终 HTML 中：
   - `<head>` 引入 ECharts：`<script src="https://cdn.jsdelivr.net/npm/echarts@5/dist/echarts.min.js"></script>`
   - 每张图：唯一 `id` 的 `.chart-box`（固定高度，如 `260px`）+ 标题/单位
   - `</body>` 前内嵌 `<script>`，复制 snippet 中的 `renderTrendChart`，用**真实** `title`、`labels`、`values`、`seriesName`、`unit` 调用
5. `labels` 与 `values` 长度必须一致；数据来自查询结果，**禁止编造**。
6. 多图时每个容器 `id` 不同，可多次调用 `renderTrendChart`；多系列对比可在同一张图里放多条 `series`（仍须基于真实数据）。

### 禁止

- 只写「趋势总览」标题 + 空容器，指望预览时自动出图。
- 在 HTML 里写 `![...](chart)` 或外链图片代替趋势图（除非用户明确要求静态截图）。
- 依赖对话里的 `drawTrendCharts` 结果——预览 iframe **读不到**工具返回值。

## 工具

- `listWorkspaceFiles`：列出 `uploads` / `outputs` / `resources/...`
- `readWorkspaceFile`：读附件与风格/结构模版
- `writeWorkspaceFile`：写入 `outputs/`（通用写文件，任意文本后缀）

不要用 shell 去找 Skill 包路径；模版统一从 `resources/skill.summary.html/` 读取。
