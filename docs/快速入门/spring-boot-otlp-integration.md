<p align="center">
  <a href="spring-boot-otlp-integration.md">中文</a>
  &nbsp;|&nbsp;
  <a href="spring-boot-otlp-integration_en.md">English</a>
</p>

# Spring Boot OTLP 接入

用 **OpenTelemetry Java Agent** 给 Spring Boot 应用做零代码埋点，把 Trace 和 Metrics 经 **OTLP** 上报到 DataBuff。

适合已有 Spring Boot 项目、希望最少改代码即可接入 APM 的场景。通用 OTLP 说明见 [OpenTelemetry OTLP 接入](../opentelemetry-otlp-ingestion.md)。

## 前置条件

- JDK 8+ 的 Spring Boot 应用（可运行 JAR 或本地 `spring-boot:run`）
- 已部署 DataBuff，且应用进程能访问 Ingest 的 OTLP 端口

## 1. 启动 DataBuff

按 [Docker 安装部署](docker安装部署.md) 一键安装平台。安装完成后终端会输出接入地址，默认如下：

| 用途 | 地址 |
|------|------|
| Web UI | `http://<本机IP>:27403` |
| 默认账号 | `admin` / `Databuff@123` |
| OTLP gRPC | `<本机IP>:4317` |
| OTLP HTTP | `http://<本机IP>:4318` |

下文将 `<ingest-host>` 替换为 Ingest 所在主机名或 IP（本机 Docker 安装时通常为 `localhost` 或 `127.0.0.1`）。

## 2. 下载 OpenTelemetry Java Agent

从 [OpenTelemetry Java Instrumentation Releases](https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest) 下载 `opentelemetry-javaagent.jar`，放到应用可访问的路径，例如项目根目录：

```bash
curl -L -o opentelemetry-javaagent.jar \
  https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar
```

Agent 会自动识别 Spring MVC、WebFlux、Jdbc、Kafka 等常见框架，无需修改业务代码。

## 3. 配置 Spring Boot 应用

### `application.yml`（推荐）

Agent 会读取 `spring.application.name` 作为默认服务名（未设置 `OTEL_SERVICE_NAME` 时）：

```yaml
spring:
  application:
    name: my-spring-service
```

等价的 `application.properties`：

```properties
spring.application.name=my-spring-service
```

### OTLP 导出（环境变量）

在启动应用前设置以下变量，将 `<ingest-host>` 换成实际地址：

```bash
export OTEL_SERVICE_NAME=my-spring-service
export OTEL_TRACES_EXPORTER=otlp
export OTEL_METRICS_EXPORTER=otlp
export OTEL_EXPORTER_OTLP_ENDPOINT="http://<ingest-host>:4318"
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
```

使用 gRPC 时：

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT="http://<ingest-host>:4317"
export OTEL_EXPORTER_OTLP_PROTOCOL=grpc
```

> **提示**：Docker 与本机 Spring Boot 同机运行时，`<ingest-host>` 用 `localhost`；应用在容器内、DataBuff 在宿主机时，用宿主机 IP 或 `host.docker.internal`（macOS / Windows Docker Desktop）。

## 4. 启动应用（带 Java Agent）

### 运行 JAR

```bash
java -javaagent:./opentelemetry-javaagent.jar \
  -jar target/my-spring-service.jar
```

### Maven 本地开发

```bash
./mvnw spring-boot:run \
  -Dspring-boot.run.jvmArguments="-javaagent:./opentelemetry-javaagent.jar"
```

### 用 JVM 系统属性代替部分环境变量

```bash
java -javaagent:./opentelemetry-javaagent.jar \
  -Dotel.service.name=my-spring-service \
  -Dotel.exporter.otlp.endpoint=http://<ingest-host>:4318 \
  -Dotel.exporter.otlp.protocol=http/protobuf \
  -Dotel.traces.exporter=otlp \
  -Dotel.metrics.exporter=otlp \
  -jar target/my-spring-service.jar
```

启动后访问几个 HTTP 接口（如 `/actuator/health` 或业务 API），以产生 Trace 和 Metrics。

## 5. 在 DataBuff 中验证

1. 打开 Web UI：`http://<ingest-host>:27403`，使用默认账号登录
2. 进入 **应用性能 → 服务列表**，确认出现 `my-spring-service`（或你配置的 `OTEL_SERVICE_NAME`）
3. 进入 **应用性能 → 链路追踪**，查看刚才请求产生的 Trace
4. 在服务详情中查看 JVM / HTTP 等指标曲线

若无数据，请检查：Ingest 端口是否可达、服务名是否与列表一致、应用日志中是否有 OTLP 导出错误。更多接入细节见 [OpenTelemetry OTLP 接入](../opentelemetry-otlp-ingestion.md)。

## 可选：采样与导出频率

生产环境可通过环境变量控制采样与指标导出间隔（详见 [性能优化](../运维参考/性能优化.md#如何配置-otel-采样应用侧)）：

```bash
export OTEL_TRACES_SAMPLER=parentbased_traceidratio
export OTEL_TRACES_SAMPLER_ARG=0.1          # 约 10% Trace
export OTEL_METRIC_EXPORT_INTERVAL=60000    # 指标 60s 导出一次
```

## 相关文档

- [Docker 安装部署](docker安装部署.md)
- [OpenTelemetry OTLP 接入](../opentelemetry-otlp-ingestion.md)
- [应用性能](../使用手册/应用性能.md)
