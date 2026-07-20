# APM 配置

> 页面: `/deploy/access?type=apm`
> 文件: `src/views/deployInstall/apm/index.vue`

## 页面职责

APM 页用于按部署环境和语言给出 **OpenTelemetry** 应用接入指引，既包含主机环境，也包含容器环境。

## 页面结构

- 环境选择: `host` / `container`
- 语言/容器选择: Java、Python、Go、Node.js、.NET 等；容器环境为 `kubernetes`
- Java 场景额外支持: `auto`（OpenTelemetry Injector）/ `manual`（`-javaagent`）

## 典型流程

1. 在页面选择环境与语言
2. 按说明配置 OpenTelemetry Java Agent 或对应语言 SDK
3. 将 OTLP 数据指向 DataBuff Ingest（默认 gRPC `4317`、HTTP `4318`）

## 相关文档

- 仓库根目录 `docs/opentelemetry-otlp-ingestion.md`
- 仓库根目录 `docs/快速入门/spring-boot-otlp-integration.md`
