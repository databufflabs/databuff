# 数据接入模块

> 模块入口: `src/views/deployInstall/index.vue`
> 路由: `/deploy/access`

## 模块概述

`deployInstall` 是「安装部署 → 数据接入」入口，提供 OpenTelemetry APM 接入、OTel Collector 与日志采集指引。

当前开放的 tab：

- `apm` — 各语言 OpenTelemetry Agent 接入说明
- `otelCollector` — OpenTelemetry Collector 配置
- `log` — 日志采集说明
- `oneAgent` — 待开放（Coming Soon）

## 页面矩阵

| 类型 | 路由 | 文件 | 说明 |
|------|------|------|------|
| 页面 | `/deploy/access?type=apm` | `deployInstall/apm/index.vue` | APM / OTLP 接入 |
| 页面 | `/deploy/access?type=otelCollector` | `deployInstall/otelCollector/index.vue` | Collector 配置 |
| 页面 | `/deploy/access?type=log` | `deployInstall/log/index.vue` | 日志采集配置 |
| 页面 | `/deploy/access?type=oneAgent` | `deployInstall/oneAgent/index.vue` | OneAgent（待开放） |

## 已补文档

- [APM 配置](apm.md)
- [日志采集](log.md)
