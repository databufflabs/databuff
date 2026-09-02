<p align="center">
  <a href="python-otlp-integration.md">中文</a>
  &nbsp;|&nbsp;
  <a href="python-otlp-integration_en.md">English</a>
</p>

# Python OTLP 接入

用 **OpenTelemetry Python** 给 Flask 应用做埋点，把 Trace 和 Metrics 经 **OTLP** 上报到 DataBuff。

适合已有 Python Web 项目、希望用环境变量完成接入的场景。通用 OTLP 说明见
[OpenTelemetry OTLP 接入](../opentelemetry-otlp-ingestion.md)。

## 前置条件

- Python 3.9+
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

## 2. 安装 OpenTelemetry Python SDK

```bash
pip install flask \
  opentelemetry-distro \
  opentelemetry-exporter-otlp
opentelemetry-bootstrap -a install
```

`opentelemetry-distro` 提供 `opentelemetry-instrument` 启动器；
`opentelemetry-bootstrap -a install` 会按当前环境安装 Flask 等自动埋点包。

## 3. 最小 Flask 应用

在工作目录创建 `app.py`：

```python
from flask import Flask

app = Flask(__name__)


@app.route("/")
def index():
    return "ok\n"


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000)
```

业务代码无需引入 OpenTelemetry。埋点由启动器完成，和 Spring Boot 的 Java Agent 类似。

## 4. 配置 OTLP 导出

启动前设置环境变量，将 `<ingest-host>` 换成实际地址：

```bash
export OTEL_SERVICE_NAME=my-python-service
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

> **提示**：Docker 与本机 Python 同机运行时，`<ingest-host>` 用 `localhost`；
> 应用在容器内、DataBuff 在宿主机时，用宿主机 IP 或 `host.docker.internal`
> （macOS / Windows Docker Desktop）。

## 5. 启动应用并产生流量

```bash
opentelemetry-instrument python app.py
```

另开一个终端发送几次请求：

```bash
curl http://localhost:5000/
```

FastAPI 同样可用启动器：安装 `fastapi uvicorn` 后执行 `opentelemetry-bootstrap -a install`，
再用 `opentelemetry-instrument uvicorn main:app --port 5000`。

### 可选：在代码里初始化 SDK

不使用启动器时，可在进程入口配置 Tracer / Meter，并手动对 Flask 埋点：

```python
from flask import Flask
from opentelemetry import metrics, trace
from opentelemetry.exporter.otlp.proto.http.metric_exporter import (
    OTLPMetricExporter,
)
from opentelemetry.exporter.otlp.proto.http.trace_exporter import (
    OTLPSpanExporter,
)
from opentelemetry.instrumentation.flask import FlaskInstrumentor
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor

resource = Resource.create({"service.name": "my-python-service"})
endpoint = "http://<ingest-host>:4318"

tracer_provider = TracerProvider(resource=resource)
tracer_provider.add_span_processor(
    BatchSpanProcessor(OTLPSpanExporter(endpoint=f"{endpoint}/v1/traces"))
)
trace.set_tracer_provider(tracer_provider)

metrics.set_meter_provider(
    MeterProvider(
        resource=resource,
        metric_readers=[
            PeriodicExportingMetricReader(
                OTLPMetricExporter(endpoint=f"{endpoint}/v1/metrics")
            )
        ],
    )
)

app = Flask(__name__)
FlaskInstrumentor().instrument_app(app)


@app.route("/")
def index():
    return "ok\n"


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000)
```

对应依赖：

```bash
pip install flask \
  opentelemetry-sdk \
  opentelemetry-exporter-otlp \
  opentelemetry-instrumentation-flask
```

然后直接 `python app.py`（不要再包一层 `opentelemetry-instrument`）。

## 6. 在 DataBuff 中验证

1. 打开 Web UI：`http://<ingest-host>:27403`，使用默认账号登录
2. 进入 **应用性能 → 服务列表**，确认出现 `my-python-service`（或你配置的 `OTEL_SERVICE_NAME`）
3. 进入 **应用性能 → 链路追踪**，查看刚才请求产生的 Trace
4. 在服务详情中查看 HTTP 等指标曲线

若无数据，请检查：Ingest 端口是否可达、服务名是否与列表一致、应用日志中是否有 OTLP 导出错误。
更多接入细节见 [OpenTelemetry OTLP 接入](../opentelemetry-otlp-ingestion.md)。

## 可选：采样与导出频率

生产环境可通过环境变量控制采样与指标导出间隔
（详见 [性能优化](../运维参考/性能优化.md#如何配置-otel-采样-应用侧)）：

```bash
export OTEL_TRACES_SAMPLER=parentbased_traceidratio
export OTEL_TRACES_SAMPLER_ARG=0.1          # 约 10% Trace
export OTEL_METRIC_EXPORT_INTERVAL=60000    # 指标 60s 导出一次
```

## 相关文档

- [Docker 安装部署](docker安装部署.md)
- [OpenTelemetry OTLP 接入](../opentelemetry-otlp-ingestion.md)
- [Spring Boot OTLP 接入](spring-boot-otlp-integration.md)
- [应用性能](../使用手册/应用性能.md)
