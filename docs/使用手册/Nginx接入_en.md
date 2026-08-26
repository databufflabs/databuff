<p align="center">
  <a href="Nginx接入.md">中文</a>
  &nbsp;|&nbsp;
  <a href="Nginx接入_en.md">English</a>
</p>

# User Guide · Nginx Ingestion

DataBuff **Ingest** accepts entry traces from the official Nginx module **`ngx_otel_module`** over **OTLP gRPC**. Pair it with a language agent on the upstream app (for example the OpenTelemetry Java Agent) and a single request shows the full waterfall: **Nginx → app → downstream**.

The module requires Nginx **1.21+**. For older in-place Nginx, use method 1 (the official OTel image); it does not touch the host process.

## Supported signals

| Signal | Protocol | Ingest port |
|--------|----------|-------------|
| Traces | OTLP gRPC (`ngx_otel_module`) | `4317` |

The module uses **gRPC 4317**. Do not point it at HTTP `4318`, and do not prefix `endpoint` with `http://`.

## Prerequisites

DataBuff is deployed and Ingest OTLP gRPC is reachable:

- [Docker installation](../快速入门/docker安装部署_en.md)
- [Kubernetes installation](../快速入门/k8s安装部署_en.md)

| Scenario | OTLP gRPC endpoint |
|----------|-------------------|
| Same-host Docker | `<ingest-host>:4317` (from a container: `host.docker.internal:4317`) |
| In-cluster | `ai-apm-ingest.databuff.svc:4317` |
| K8s NodePort | `<node-ip>:30417` |

## Two ways to install

| Method | When | What to do |
|--------|------|------------|
| **Method 1** | New / container | `nginx:1.27-alpine-otel` — module built in; mount the config |
| **Method 2** | Existing Nginx | Install `nginx-module-otel` → edit conf → `nginx -t` → `nginx -s reload` |

Both use the same OTel directives. Prove method 1 in a test environment, then add the module to production Nginx with method 2.

## Shared: full nginx.conf

Replace `<ingest-host>` with DataBuff Ingest and `<backend-host>` with the upstream app. Same host can use `127.0.0.1`.

```nginx
load_module /usr/lib/nginx/modules/ngx_otel_module.so;

worker_processes auto;
error_log /var/log/nginx/error.log notice;
pid /var/run/nginx.pid;

events {
    worker_connections 1024;
}

http {
    include /etc/nginx/mime.types;
    default_type application/octet-stream;

    otel_exporter {
        endpoint <ingest-host>:4317;
        interval 5s;
    }
    otel_service_name nginx-otel-demo;
    # Required: inject traceparent into upstream requests, or Nginx and the app stay on separate traces
    otel_trace_context propagate;

    server {
        listen 80;
        otel_trace on;

        location / {
            proxy_pass http://<backend-host>:18091;
        }
    }
}
```

`otel_trace_context propagate;` **must be set explicitly**. The default is extract-only and will not inject `traceparent` into upstream requests.

## Method 1 · Official OTel image

The image already includes the module. Mount the conf and start:

```bash
docker run -d --name nginx-otel \
  -p 8090:80 \
  --add-host host.docker.internal:host-gateway \
  -v /path/to/nginx.conf:/etc/nginx/nginx.conf:ro \
  nginx:1.27-alpine-otel
```

If the container reports to / proxies the host, use `host.docker.internal` in the conf (the `--add-host` flag maps that name to the host).

After editing the conf:

```bash
docker cp /path/to/nginx.conf nginx-otel:/etc/nginx/nginx.conf
docker exec nginx-otel nginx -t
docker exec nginx-otel nginx -s reload
```

## Method 2 · Add the module to existing Nginx

Check the version first (**1.21+**):

```bash
nginx -v
```

Install the official module (confirm with `ls /usr/lib/nginx/modules/ngx_otel_module.so`):

```bash
# Alpine
apk add --repository https://nginx.org/packages/mainline/alpine/v3.21/main nginx-module-otel

# CentOS / RHEL (configure the nginx.org yum repo first)
yum install nginx-module-otel

# Debian / Ubuntu (configure the nginx.org apt repo first)
apt install nginx-module-otel
```

Write the full conf above to `/etc/nginx/nginx.conf` (or add `load_module` at the top, `otel_*` in `http`, and `otel_trace on` in `server`). **Test, then reload** (in-flight requests keep going):

```bash
nginx -t
nginx -s reload
```

## Optional: stitch the backend

Nginx only records the entry span. To join Java (or another service) on the same trace, the downstream app must export OTLP and honor the `traceparent` Nginx injects.

Java example (no code change; Java uses **HTTP 4318**, Nginx uses **gRPC 4317**, both into the same DataBuff):

```bash
java -javaagent:opentelemetry-javaagent.jar \
  -Dotel.service.name=java-app \
  -Dotel.exporter.otlp.endpoint=http://<ingest-host>:4318 \
  -jar your-app.jar
```

## Send traffic and open a waterfall

```bash
curl http://YOUR_HOST:8090/
```

Hit it a few times, wait about 30 seconds (batch export), then open DataBuff **Application Performance → Traces**, select `nginx-otel-demo`, and open any trace. With a backend agent, the waterfall continues past Nginx.

## Troubleshooting

| Symptom | Cause and fix |
|---------|----------------|
| Nginx and the backend are two traces | Missing `otel_trace_context propagate;`. Add it, then `nginx -t && nginx -s reload` |
| Log `OTel export failure ... Socket closed` | `endpoint` used 4318 or `http://`. Use `host:4317` |
| `otel_service_name` is not allowed here | That directive belongs in `http`, not `server` |
| `otel_propagators w3c` is unknown | Module 0.1.x has no such directive; remove it (W3C is the default) |
| Host Nginx too old (&lt; 1.21) | Use method 1; it does not affect the host process |

## Related docs

- [OpenTelemetry OTLP Ingestion](../opentelemetry-otlp-ingestion_en.md)
- [eBPF Ingestion](eBPF接入_en.md)
- [Spring Boot OTLP Integration](../快速入门/spring-boot-otlp-integration_en.md)
- [Application Performance](应用性能_en.md)
