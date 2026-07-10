<p align="center">
  <a href="spring-boot-otlp-integration.md">中文</a>
  &nbsp;|&nbsp;
  <a href="spring-boot-otlp-integration_en.md">English</a>
</p>

# Spring Boot OTLP Integration

Instrument a Spring Boot app with the **OpenTelemetry Java Agent** for zero-code tracing and metrics, exported to DataBuff over **OTLP**.

Best for existing Spring Boot projects that need minimal code changes. For general OTLP details, see [OpenTelemetry OTLP Ingestion](../opentelemetry-otlp-ingestion_en.md).

## Prerequisites

- A Spring Boot app on JDK 8+ (runnable JAR or local `spring-boot:run`)
- DataBuff deployed and reachable from the app process on the OTLP ports

## 1. Start DataBuff

Follow [Docker Installation](docker安装部署_en.md). After install, the terminal prints endpoints. Defaults:

| Purpose | Address |
|---------|---------|
| Web UI | `http://<host-ip>:27403` |
| Default login | `admin` / `Databuff@123` |
| OTLP gRPC | `<host-ip>:4317` |
| OTLP HTTP | `http://<host-ip>:4318` |

Replace `<ingest-host>` below with the Ingest hostname or IP (usually `localhost` or `127.0.0.1` for local Docker).

## 2. Download the OpenTelemetry Java Agent

Download `opentelemetry-javaagent.jar` from [OpenTelemetry Java Instrumentation Releases](https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest) and place it where your app can load it, e.g. the project root:

```bash
curl -L -o opentelemetry-javaagent.jar \
  https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar
```

The agent auto-instruments Spring MVC, WebFlux, JDBC, Kafka, and other common libraries without code changes.

## 3. Configure the Spring Boot App

### `application.yml` (recommended)

The agent uses `spring.application.name` as the default service name when `OTEL_SERVICE_NAME` is unset:

```yaml
spring:
  application:
    name: my-spring-service
```

Equivalent `application.properties`:

```properties
spring.application.name=my-spring-service
```

### OTLP export (environment variables)

Set these before starting the app; replace `<ingest-host>` with your Ingest address:

```bash
export OTEL_SERVICE_NAME=my-spring-service
export OTEL_TRACES_EXPORTER=otlp
export OTEL_METRICS_EXPORTER=otlp
export OTEL_EXPORTER_OTLP_ENDPOINT="http://<ingest-host>:4318"
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
```

For gRPC:

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT="http://<ingest-host>:4317"
export OTEL_EXPORTER_OTLP_PROTOCOL=grpc
```

> **Tip**: Use `localhost` when Spring Boot and DataBuff run on the same host. When the app runs in a container and DataBuff on the host, use the host IP or `host.docker.internal` (macOS / Windows Docker Desktop).

## 4. Start the App with the Java Agent

### Run a JAR

```bash
java -javaagent:./opentelemetry-javaagent.jar \
  -jar target/my-spring-service.jar
```

### Maven local development

```bash
./mvnw spring-boot:run \
  -Dspring-boot.run.jvmArguments="-javaagent:./opentelemetry-javaagent.jar"
```

### JVM system properties instead of env vars

```bash
java -javaagent:./opentelemetry-javaagent.jar \
  -Dotel.service.name=my-spring-service \
  -Dotel.exporter.otlp.endpoint=http://<ingest-host>:4318 \
  -Dotel.exporter.otlp.protocol=http/protobuf \
  -Dotel.traces.exporter=otlp \
  -Dotel.metrics.exporter=otlp \
  -jar target/my-spring-service.jar
```

Hit a few HTTP endpoints (e.g. `/actuator/health` or your APIs) to generate traces and metrics.

## 5. Verify in the DataBuff UI

1. Open the Web UI at `http://<ingest-host>:27403` and sign in with the default account
2. Go to **Application Performance → Services** and confirm `my-spring-service` (or your `OTEL_SERVICE_NAME`) appears
3. Open **Application Performance → Traces** and inspect traces from your requests
4. On the service detail page, review JVM / HTTP metric charts

If nothing shows up, check Ingest connectivity, service name, and app logs for OTLP export errors. See [OpenTelemetry OTLP Ingestion](../opentelemetry-otlp-ingestion_en.md) for more.

## Optional: sampling and export interval

For production, tune sampling and metric export via environment variables (see [Performance Tuning](../运维参考/性能优化_en.md)):

```bash
export OTEL_TRACES_SAMPLER=parentbased_traceidratio
export OTEL_TRACES_SAMPLER_ARG=0.1          # ~10% of traces
export OTEL_METRIC_EXPORT_INTERVAL=60000    # export metrics every 60s
```

## Related docs

- [Docker Installation](docker安装部署_en.md)
- [OpenTelemetry OTLP Ingestion](../opentelemetry-otlp-ingestion_en.md)
- [Application Performance](../使用手册/应用性能_en.md)
