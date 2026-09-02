<p align="center">
  <a href="python-otlp-integration.md">中文</a>
  &nbsp;|&nbsp;
  <a href="python-otlp-integration_en.md">English</a>
</p>

# Python OTLP Integration

Instrument a Flask app with **OpenTelemetry Python** and export traces and
metrics to DataBuff over **OTLP**.

Best for existing Python web apps that can be wired up with environment
variables. For general OTLP details, see
[OpenTelemetry OTLP Ingestion](../opentelemetry-otlp-ingestion_en.md).

## Prerequisites

- Python 3.9+
- DataBuff deployed and reachable from the app process on the OTLP ports

## 1. Start DataBuff

Follow [Docker Installation](docker安装部署_en.md). After install, the terminal
prints endpoints. Defaults:

| Purpose | Address |
|---------|---------|
| Web UI | `http://<host-ip>:27403` |
| Default login | `admin` / `Databuff@123` |
| OTLP gRPC | `<host-ip>:4317` |
| OTLP HTTP | `http://<host-ip>:4318` |

Replace `<ingest-host>` below with the Ingest hostname or IP (usually
`localhost` or `127.0.0.1` for local Docker).

## 2. Install the OpenTelemetry Python SDK

```bash
pip install flask \
  opentelemetry-distro \
  opentelemetry-exporter-otlp
opentelemetry-bootstrap -a install
```

`opentelemetry-distro` provides the `opentelemetry-instrument` launcher.
`opentelemetry-bootstrap -a install` installs auto-instrumentation packages
for libraries in the current environment, including Flask.

## 3. Minimal Flask app

Create `app.py` in your working directory:

```python
from flask import Flask

app = Flask(__name__)


@app.route("/")
def index():
    return "ok\n"


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000)
```

The app itself does not import OpenTelemetry. The launcher injects
instrumentation, similar to the Java Agent used in the Spring Boot guide.

## 4. Configure OTLP export

Set these before starting the app; replace `<ingest-host>` with your Ingest
address:

```bash
export OTEL_SERVICE_NAME=my-python-service
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

> **Note**: Use `localhost` when Docker and the Python app run on the same
> machine. If the app is in a container and DataBuff is on the host, use the
> host IP or `host.docker.internal` (macOS / Windows Docker Desktop).

## 5. Run the app and generate traffic

```bash
opentelemetry-instrument python app.py
```

In another terminal, send a few requests:

```bash
curl http://localhost:5000/
```

FastAPI works the same way: install `fastapi uvicorn`, run
`opentelemetry-bootstrap -a install`, then
`opentelemetry-instrument uvicorn main:app --port 5000`.

### Optional: initialize the SDK in code

Without the launcher, configure the tracer and meter at process start and
instrument Flask yourself:

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

Dependencies:

```bash
pip install flask \
  opentelemetry-sdk \
  opentelemetry-exporter-otlp \
  opentelemetry-instrumentation-flask
```

Then run `python app.py` directly (do not wrap it with
`opentelemetry-instrument`).

## 6. Verify in DataBuff

1. Open the Web UI: `http://<ingest-host>:27403` and sign in with the default account
2. Go to **Application Performance → Services** and confirm `my-python-service` (or your `OTEL_SERVICE_NAME`) appears
3. Go to **Application Performance → Traces** and open a trace from the requests you just sent
4. Check HTTP metric charts on the service detail page

If nothing shows up, confirm the Ingest ports are reachable, the service name
matches the list, and the app log has no OTLP export errors. More detail:
[OpenTelemetry OTLP Ingestion](../opentelemetry-otlp-ingestion_en.md).

## Optional: sampling and export interval

In production, control sampling and metric export interval with environment
variables (see [Performance Tuning](../运维参考/性能优化_en.md)):

```bash
export OTEL_TRACES_SAMPLER=parentbased_traceidratio
export OTEL_TRACES_SAMPLER_ARG=0.1          # ~10% of traces
export OTEL_METRIC_EXPORT_INTERVAL=60000    # export metrics every 60s
```

## Related docs

- [Docker Installation](docker安装部署_en.md)
- [OpenTelemetry OTLP Ingestion](../opentelemetry-otlp-ingestion_en.md)
- [Spring Boot OTLP Integration](spring-boot-otlp-integration_en.md)
- [Application Performance](../使用手册/应用性能_en.md)
