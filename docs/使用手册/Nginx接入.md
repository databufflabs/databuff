<p align="center">
  <a href="Nginx接入.md">中文</a>
  &nbsp;|&nbsp;
  <a href="Nginx接入_en.md">English</a>
</p>

# 使用手册 · Nginx 接入

DataBuff **Ingest** 通过 **OTLP gRPC** 接收 Nginx 官方模块 **`ngx_otel_module`** 上报的入口 Trace。后端再挂语言探针（例如 OpenTelemetry Java Agent），同一条请求就能看到 **Nginx → 应用 → 下游** 的完整瀑布。

模块要求 Nginx **1.21+**。更旧的存量 Nginx 用方式一（官方 OTel 镜像），和宿主机旧进程互不影响。

## 支持的信号

| 信号 | 协议 | Ingest 端口 |
|------|------|-------------|
| Trace | OTLP gRPC（`ngx_otel_module`） | `4317` |

注意：模块走 **gRPC 4317**，不要写成 HTTP `4318`，也不要在 `endpoint` 前加 `http://`。

## 前置条件

DataBuff 已部署，Ingest 的 OTLP gRPC 口可达：

- [Docker 安装](../快速入门/docker安装部署.md)
- [Kubernetes 安装](../快速入门/k8s安装部署.md)

| 场景 | OTLP gRPC 地址 |
|------|----------------|
| 同机 Docker | `<ingest-host>:4317`（容器内可用 `host.docker.internal:4317`） |
| 集群内 | `ai-apm-ingest.databuff.svc:4317` |
| K8s NodePort | `<节点IP>:30417` |

## 两种接入方式

| 方式 | 场景 | 怎么做 |
|------|------|--------|
| **方式一** | 全新 / 容器 | 用 `nginx:1.27-alpine-otel`，模块内置，挂配置启动 |
| **方式二** | 存量 Nginx | 装 `nginx-module-otel` → 写 conf → `nginx -t` → `nginx -s reload` |

两条路的 OTel 指令一样。建议测试环境先走方式一，存量再方式二加装。

## 共用：完整 nginx.conf

把 `<ingest-host>` 换成 DataBuff Ingest 地址，把 `<backend-host>` 换成上游应用。同机可写 `127.0.0.1`。

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
    # 关键：把 traceparent 透传给后端，否则 Nginx 和下游会变成两条互不相干的 Trace
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

`otel_trace_context propagate;` **必须显式写**。模块默认只 extract，不会向上游注入 `traceparent`。

## 方式一 · 官方 OTel 镜像

镜像里已有模块，挂上刚写的 conf 启动即可：

```bash
docker run -d --name nginx-otel \
  -p 8090:80 \
  --add-host host.docker.internal:host-gateway \
  -v /path/to/nginx.conf:/etc/nginx/nginx.conf:ro \
  nginx:1.27-alpine-otel
```

容器里上报 / 反代到宿主机时，conf 里可写 `host.docker.internal`（上面 `--add-host` 是为了让这个名字解析到宿主）。

改配置后：

```bash
docker cp /path/to/nginx.conf nginx-otel:/etc/nginx/nginx.conf
docker exec nginx-otel nginx -t
docker exec nginx-otel nginx -s reload
```

## 方式二 · 存量 Nginx 加装模块

先看版本（要 **1.21+**）：

```bash
nginx -v
```

按系统装官方模块（装完可用 `ls /usr/lib/nginx/modules/ngx_otel_module.so` 确认）：

```bash
# Alpine
apk add --repository https://nginx.org/packages/mainline/alpine/v3.21/main nginx-module-otel

# CentOS / RHEL（先配 nginx.org 官方 yum 源）
yum install nginx-module-otel

# Debian / Ubuntu（先配 nginx.org 官方 apt 源）
apt install nginx-module-otel
```

把上面那份完整 conf 写到 `/etc/nginx/nginx.conf`（或在原 conf 顶部加 `load_module`，`http` 块加 `otel_*`，`server` 块加 `otel_trace on`）。然后**先测配置、再热加载**（不中断现有请求）：

```bash
nginx -t
nginx -s reload
```

## 串到后端（可选）

Nginx 只负责入口 Span。要和 Java / 其他服务拼成一条链，下游也要报 OTLP，并吃到 Nginx 注入的 `traceparent`。

Java 示例（业务代码不用改；Java 走 **HTTP 4318**，Nginx 走 **gRPC 4317**，进同一个 DataBuff）：

```bash
java -javaagent:opentelemetry-javaagent.jar \
  -Dotel.service.name=java-app \
  -Dotel.exporter.otlp.endpoint=http://<ingest-host>:4318 \
  -jar your-app.jar
```

## 打流量，看瀑布

```bash
curl http://你的机器IP:8090/
```

打几次，等约 30 秒（批量导出有延迟），打开 DataBuff **应用性能 → 链路追踪**，勾选 `nginx-otel-demo`，点开任意一条。后端也挂了探针时，瀑布里应能看到 Nginx 再往下的调用。

## 常见问题

| 现象 | 原因与处理 |
|------|------------|
| Nginx 和后端是两条独立 Trace | 漏写 `otel_trace_context propagate;`。加上后 `nginx -t && nginx -s reload` |
| 日志 `OTel export failure ... Socket closed` | `endpoint` 用了 4318 或带了 `http://`。改成 `host:4317` |
| `otel_service_name` 报 not allowed here | 该指令只支持 `http` 上下文，不要放进 `server` |
| `otel_propagators w3c` 报 unknown directive | 模块 0.1.x 无此指令，删掉即可（默认就是 W3C tracecontext） |
| 宿主机 Nginx 太旧（&lt; 1.21） | 走方式一官方镜像，和宿主机进程互不影响 |

## 相关文档

- [OpenTelemetry OTLP 接入](../opentelemetry-otlp-ingestion.md)
- [eBPF 接入](eBPF接入.md)
- [Spring Boot OTLP 接入](../快速入门/spring-boot-otlp-integration.md)
- [应用性能](应用性能.md)
