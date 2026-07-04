package com.databuff.apm.demo.support;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

public final class OtlpTraceFixture {

    public static final String SERVICE_A = "service-a";
    public static final String SERVICE_B = "service-b";
    public static final String DEMO_SERVICE = SERVICE_A;
    public static final String DEMO_SPAN_NAME = "GET /demo/checkout";

    private OtlpTraceFixture() {
    }

    public static byte[] sampleTraceExport() {
        return nextTraceBatch().traceBytes();
    }

    public static DemoTraceBatch nextTraceBatch() {
        long traceEnd = Instant.now().toEpochMilli() * 1_000_000L;
        long traceStart = traceEnd - 240_000_000L;
        ByteString traceId = randomId(16);
        ByteString root = randomId(8);
        ByteString httpClient = randomId(8);
        ByteString httpServer = randomId(8);
        ByteString httpMysql = randomId(8);
        ByteString dubboClient = randomId(8);
        ByteString dubboServer = randomId(8);
        ByteString dubboMysql = randomId(8);
        ByteString auditMysql = randomId(8);
        ByteString redisClient = randomId(8);
        ByteString kafkaClient = randomId(8);
        ByteString esClient = randomId(8);
        ByteString redisServerB = randomId(8);
        ByteString remoteHttpClient = randomId(8);
        byte[] traceBytes = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .setResource(serviceResource(SERVICE_A, "service-a-1", "demo-host-a"))
                        .addScopeSpans(ScopeSpans.newBuilder()
                                .addSpans(span(traceId, root, ByteString.EMPTY,
                                        Span.SpanKind.SPAN_KIND_SERVER, "GET /demo/checkout",
                                        traceStart, traceEnd,
                                        kv("http.method", "GET"),
                                        kv("http.status_code", "200"),
                                        kv("url.full", "/demo/checkout")))
                                .addSpans(span(traceId, redisClient, root,
                                        Span.SpanKind.SPAN_KIND_CLIENT,
                                        "GET cart",
                                        traceStart + 5_000_000L, traceStart + 18_000_000L,
                                        kv("db.system", "redis"),
                                        kv("db.statement", "GET cart:10001"),
                                        kv("server.address", "redis"),
                                        kv("server.port", "6379")))
                                .addSpans(span(traceId, remoteHttpClient, root,
                                        Span.SpanKind.SPAN_KIND_CLIENT,
                                        "HTTP GET payments.example.com /api/risk/check",
                                        traceStart + 12_000_000L, traceStart + 19_000_000L,
                                        kv("http.method", "GET"),
                                        kv("http.status_code", "200"),
                                        kv("url.full", "https://payments.example.com/api/risk/check"),
                                        kv("server.address", "payments.example.com"),
                                        kv("server.port", "443")))
                                .addSpans(span(traceId, httpClient, root,
                                        Span.SpanKind.SPAN_KIND_CLIENT, "HTTP GET service-b /api/orders",
                                        traceStart + 20_000_000L, traceStart + 120_000_000L,
                                        kv("http.method", "GET"),
                                        kv("http.status_code", "200"),
                                        kv("url.full", "http://service-b:8080/api/orders/10001"),
                                        kv("server.address", "service-b"),
                                        kv("server.port", "8080")))
                                .addSpans(span(traceId, esClient, root,
                                        Span.SpanKind.SPAN_KIND_CLIENT,
                                        "orders/_search",
                                        traceStart + 100_000_000L, traceStart + 118_000_000L,
                                        kv("db.system", "elasticsearch"),
                                        kv("db.elasticsearch.index", "orders"),
                                        kv("http.method", "GET"),
                                        kv("url.full", "http://es:9200/orders/_search"),
                                        kv("server.address", "es"),
                                        kv("server.port", "9200")))
                                .addSpans(span(traceId, dubboClient, root,
                                        Span.SpanKind.SPAN_KIND_CLIENT,
                                        "Dubbo DemoOrderService.findInventory",
                                        traceStart + 125_000_000L, traceStart + 205_000_000L,
                                        kv("rpc.system", "dubbo"),
                                        kv("rpc.service", "com.databuff.demo.OrderService"),
                                        kv("rpc.method", "findInventory"),
                                        kv("net.peer.name", "service-b"),
                                        kv("net.peer.port", "20880")))
                                .addSpans(span(traceId, auditMysql, root,
                                        Span.SpanKind.SPAN_KIND_CLIENT,
                                        "INSERT demo_order_audit",
                                        traceStart + 208_000_000L, traceStart + 228_000_000L,
                                        kv("db.system", "mysql"),
                                        kv("db.name", "demo_apm"),
                                        kv("db.statement", "INSERT INTO demo_order_audit(order_id, channel) VALUES (?, ?)"),
                                        kv("server.address", "mysql"),
                                        kv("server.port", "3306")))
                                .addSpans(span(traceId, kafkaClient, root,
                                        Span.SpanKind.SPAN_KIND_CLIENT,
                                        "order-events publish",
                                        traceStart + 230_000_000L, traceStart + 238_000_000L,
                                        kv("messaging.system", "kafka"),
                                        kv("messaging.destination.name", "order-events"),
                                        kv("messaging.operation", "publish"),
                                        kv("messaging.kafka.partition", "0"),
                                        kv("net.peer.name", "kafka"),
                                        kv("server.port", "9092")))))
                .addResourceSpans(ResourceSpans.newBuilder()
                        .setResource(serviceResource(SERVICE_B, "service-b-1", "demo-host-b"))
                        .addScopeSpans(ScopeSpans.newBuilder()
                                .addSpans(span(traceId, httpServer, httpClient,
                                        Span.SpanKind.SPAN_KIND_SERVER, "GET /api/orders/{orderId}",
                                        traceStart + 30_000_000L, traceStart + 110_000_000L,
                                        kv("http.method", "GET"),
                                        kv("http.status_code", "200"),
                                        kv("url.full", "/api/orders/10001")))
                                .addSpans(span(traceId, httpMysql, httpServer,
                                        Span.SpanKind.SPAN_KIND_CLIENT,
                                        "SELECT demo_order",
                                        traceStart + 45_000_000L, traceStart + 90_000_000L,
                                        kv("db.system", "mysql"),
                                        kv("db.name", "demo_apm"),
                                        kv("db.statement", "SELECT id, amount, status FROM demo_order WHERE id = ?"),
                                        kv("server.address", "mysql"),
                                        kv("server.port", "3306")))
                                .addSpans(span(traceId, dubboServer, dubboClient,
                                        Span.SpanKind.SPAN_KIND_SERVER,
                                        "Dubbo DemoOrderService.findInventory",
                                        traceStart + 135_000_000L, traceStart + 195_000_000L,
                                        kv("rpc.system", "dubbo"),
                                        kv("rpc.service", "com.databuff.demo.OrderService"),
                                        kv("rpc.method", "findInventory")))
                                .addSpans(errorSpan(traceId, dubboMysql, dubboServer,
                                        Span.SpanKind.SPAN_KIND_CLIENT,
                                        "SELECT demo_inventory",
                                        traceStart + 150_000_000L, traceStart + 180_000_000L,
                                        "InsufficientStockException", "inventory unavailable for sku DEMO-10001",
                                        kv("db.system", "mysql"),
                                        kv("db.name", "demo_apm"),
                                        kv("db.statement", "SELECT sku, available FROM demo_inventory WHERE sku = ?"),
                                        kv("server.address", "mysql"),
                                        kv("server.port", "3306")))
                                .addSpans(span(traceId, redisServerB, httpServer,
                                        Span.SpanKind.SPAN_KIND_CLIENT,
                                        "SET order:10001",
                                        traceStart + 95_000_000L, traceStart + 108_000_000L,
                                        kv("db.system", "redis"),
                                        kv("db.statement", "SET order:10001"),
                                        kv("server.address", "redis"),
                                        kv("server.port", "6379")))))
                .build()
                .toByteArray();
        return new DemoTraceBatch(
                traceBytes,
                traceId,
                traceStart,
                traceEnd,
                spanRef(SERVICE_A, "service-a-1", "demo-host-a", root, traceStart + 10_000_000L),
                spanRef(SERVICE_A, "service-a-1", "demo-host-a", httpClient, traceStart + 25_000_000L),
                spanRef(SERVICE_B, "service-b-1", "demo-host-b", httpServer, traceStart + 55_000_000L),
                spanRef(SERVICE_B, "service-b-1", "demo-host-b", dubboMysql, traceStart + 165_000_000L));
    }

    private static DemoTraceBatch.SpanRef spanRef(
            String service, String instanceId, String hostName, ByteString spanId, long timeNanos) {
        return new DemoTraceBatch.SpanRef(spanId, service, instanceId, hostName, timeNanos);
    }

    public static byte[] sampleErrorTraceExport() {
        long end = Instant.now().toEpochMilli() * 1_000_000L;
        long start = end - 50_000_000L;
        byte[] traceId = new byte[16];
        byte[] spanId = new byte[8];
        ThreadLocalRandom.current().nextBytes(traceId);
        ThreadLocalRandom.current().nextBytes(spanId);
        return ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .setResource(serviceResource(DEMO_SERVICE, "service-a-1", "demo-host-a"))
                        .addScopeSpans(ScopeSpans.newBuilder()
                                .addSpans(Span.newBuilder()
                                        .setTraceId(ByteString.copyFrom(traceId))
                                        .setSpanId(ByteString.copyFrom(spanId))
                                        .setName(DEMO_SPAN_NAME)
                                        .addAttributes(kv("http.method", "GET"))
                                        .addAttributes(kv("http.status_code", "500"))
                                        .addAttributes(kv("error.type", "InternalServerError"))
                                        .addAttributes(kv("url.full", "/demo/checkout"))
                                        .setStatus(Status.newBuilder()
                                                .setCode(Status.StatusCode.STATUS_CODE_ERROR)
                                                .setMessage("Internal Server Error"))
                                        .setStartTimeUnixNano(start)
                                        .setEndTimeUnixNano(end))))
                .build()
                .toByteArray();
    }

    public static int postErrorTraces(String ingestBaseUrl) throws Exception {
        return OtlpHttpExporter.postProtobuf(ingestBaseUrl, "/v1/traces", sampleErrorTraceExport());
    }

    public static int postTraces(String ingestBaseUrl) throws Exception {
        return postTraceBatch(ingestBaseUrl, nextTraceBatch());
    }

    public static int postTraceBatch(String ingestBaseUrl, DemoTraceBatch batch) throws Exception {
        return OtlpHttpExporter.postProtobuf(ingestBaseUrl, "/v1/traces", batch.traceBytes());
    }

    public static byte[] sampleJvmGcOnlyExport() {
        return JvmMetricSimulator.gcOnlyExport();
    }

    public static byte[] sampleJvmPoolMetricsExport() {
        return JvmMetricSimulator.nextDemoExport();
    }

    public static int postMetrics(String ingestBaseUrl) throws Exception {
        return OtlpHttpExporter.postProtobuf(ingestBaseUrl, "/v1/metrics", sampleJvmPoolMetricsExport());
    }

    /** CLI: post one trace batch, or {@code --dump <file>} to write fixture bytes. */
    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && "--dump".equals(args[0])) {
            java.nio.file.Files.write(java.nio.file.Path.of(args[1]), sampleTraceExport());
            System.out.println("wrote " + args[1]);
            return;
        }
        if (args.length >= 2 && "--dump-error".equals(args[0])) {
            java.nio.file.Files.write(java.nio.file.Path.of(args[1]), sampleErrorTraceExport());
            System.out.println("wrote " + args[1]);
            return;
        }
        if (args.length >= 2 && "--dump-metrics".equals(args[0])) {
            java.nio.file.Files.write(java.nio.file.Path.of(args[1]), sampleJvmPoolMetricsExport());
            System.out.println("wrote " + args[1]);
            return;
        }
        if (args.length >= 2 && "--dump-logs".equals(args[0])) {
            java.nio.file.Files.write(java.nio.file.Path.of(args[1]), OtlpLogFixture.logExport(nextTraceBatch()));
            System.out.println("wrote " + args[1]);
            return;
        }
        String base = args.length > 0 ? args[0] : "http://127.0.0.1:4318";
        DemoTraceBatch batch = nextTraceBatch();
        int status = postTraceBatch(base, batch);
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("OTLP trace export failed with HTTP " + status);
        }
        int logStatus = OtlpLogFixture.postLogs(base, batch);
        if (logStatus < 200 || logStatus >= 300) {
            throw new IllegalStateException("OTLP log export failed with HTTP " + logStatus);
        }
        int metricStatus = postMetrics(base);
        if (metricStatus < 200 || metricStatus >= 300) {
            throw new IllegalStateException("OTLP metric export failed with HTTP " + metricStatus);
        }
        System.out.println("seeded trace + logs + metrics to " + base + " (HTTP " + status + "/" + logStatus
                + "/" + metricStatus + ")");
    }

    private static Resource.Builder serviceResource(String serviceName, String instanceId, String hostName) {
        return Resource.newBuilder()
                .addAttributes(kv("service.name", serviceName))
                .addAttributes(kv("host.name", hostName))
                .addAttributes(kv("service.instance.id", instanceId))
                .addAttributes(kv("k8s.namespace.name", "demo"));
    }

    private static Span.Builder span(
            ByteString traceId,
            ByteString spanId,
            ByteString parentSpanId,
            Span.SpanKind kind,
            String name,
            long start,
            long end,
            KeyValue... attributes) {
        return spanBuilder(traceId, spanId, parentSpanId, kind, name, start, end, attributes);
    }

    private static Span.Builder errorSpan(
            ByteString traceId,
            ByteString spanId,
            ByteString parentSpanId,
            Span.SpanKind kind,
            String name,
            long start,
            long end,
            String errorType,
            String errorMessage,
            KeyValue... attributes) {
        Span.Builder builder = spanBuilder(traceId, spanId, parentSpanId, kind, name, start, end, attributes);
        builder.addAttributes(kv("error.type", errorType));
        builder.setStatus(Status.newBuilder()
                .setCode(Status.StatusCode.STATUS_CODE_ERROR)
                .setMessage(errorMessage));
        return builder;
    }

    private static Span.Builder spanBuilder(
            ByteString traceId,
            ByteString spanId,
            ByteString parentSpanId,
            Span.SpanKind kind,
            String name,
            long start,
            long end,
            KeyValue... attributes) {
        Span.Builder builder = Span.newBuilder()
                .setTraceId(traceId)
                .setSpanId(spanId)
                .setName(name)
                .setKind(kind)
                .setStartTimeUnixNano(start)
                .setEndTimeUnixNano(end);
        if (!parentSpanId.isEmpty()) {
            builder.setParentSpanId(parentSpanId);
        }
        for (KeyValue attribute : attributes) {
            builder.addAttributes(attribute);
        }
        return builder;
    }

    private static ByteString randomId(int bytes) {
        byte[] id = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(id);
        return ByteString.copyFrom(id);
    }

    private static KeyValue kv(String key, String value) {
        return KeyValue.newBuilder()
                .setKey(key)
                .setValue(AnyValue.newBuilder().setStringValue(value))
                .build();
    }
}
