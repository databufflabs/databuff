package com.databuff.apm.demo.support;

import com.databuff.apm.demo.fault.DemoConfigurationChange;
import com.databuff.apm.demo.fault.DemoFaultController;
import com.databuff.apm.demo.fault.DemoFaultSnapshot;
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

/** Synthetic service-a → service-b checkout traces with normal and cache-TTL fault profiles. */
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
        return nextTraceBatch(DemoFaultSnapshot.normal());
    }

    public static DemoTraceBatch nextTraceBatch(DemoFaultSnapshot fault) {
        long rootDurationMs = fault.active() ? 3_200L : 240L;
        long serviceBEndMs = fault.active() ? 3_130L : 110L;
        long httpClientEndMs = fault.active() ? 3_130L : 120L;
        long orderDbStartMs = fault.active() ? 70L : 45L;
        long orderDbEndMs = fault.active() ? 3_020L : 90L;
        long lateStartMs = fault.active() ? 3_135L : 125L;

        long traceEnd = Instant.now().toEpochMilli() * 1_000_000L;
        long traceStart = traceEnd - ms(rootDurationMs);
        ByteString traceId = randomId(16);
        ByteString root = randomId(8);
        ByteString cartRedis = randomId(8);
        ByteString remoteHttpClient = randomId(8);
        ByteString httpClient = randomId(8);
        ByteString esClient = randomId(8);
        ByteString dubboClient = randomId(8);
        ByteString auditMysql = randomId(8);
        ByteString kafkaClient = randomId(8);
        ByteString httpServer = randomId(8);
        ByteString orderCache = randomId(8);
        ByteString orderMysql = randomId(8);
        ByteString dubboServer = randomId(8);
        ByteString inventoryMysql = randomId(8);

        Span.Builder rootSpan = span(traceId, root, ByteString.EMPTY,
                Span.SpanKind.SPAN_KIND_SERVER, "GET /demo/checkout",
                at(traceStart, 0), at(traceStart, rootDurationMs),
                kv("http.method", "GET"),
                kv("http.status_code", "200"),
                kv("url.full", "/demo/checkout"));
        addFaultAttributes(rootSpan, fault);

        Span.Builder serviceAHttp = span(traceId, httpClient, root,
                Span.SpanKind.SPAN_KIND_CLIENT, "HTTP GET service-b /api/orders",
                at(traceStart, 20), at(traceStart, httpClientEndMs),
                kv("http.method", "GET"),
                kv("http.status_code", "200"),
                kv("url.full", "http://service-b:8080/api/orders/10001"),
                kv("server.address", "service-b"),
                kv("server.port", "8080"));
        addFaultAttributes(serviceAHttp, fault);

        long esStartMs = fault.active() ? 3_132L : 100L;
        long esEndMs = fault.active() ? 3_150L : 118L;
        long auditStartMs = fault.active() ? 3_180L : 208L;
        long auditEndMs = fault.active() ? 3_192L : 228L;
        long kafkaStartMs = fault.active() ? 3_190L : 230L;
        long kafkaEndMs = fault.active() ? 3_198L : 238L;

        ResourceSpans serviceA = ResourceSpans.newBuilder()
                .setResource(serviceResource(SERVICE_A, "service-a-1", "demo-host-a"))
                .addScopeSpans(ScopeSpans.newBuilder()
                        .addSpans(rootSpan)
                        .addSpans(span(traceId, cartRedis, root,
                                Span.SpanKind.SPAN_KIND_CLIENT, "GET cart",
                                at(traceStart, 5), at(traceStart, 18),
                                kv("db.system", "redis"),
                                kv("db.statement", "GET cart:10001"),
                                kv("server.address", "redis"),
                                kv("server.port", "6379")))
                        .addSpans(span(traceId, remoteHttpClient, root,
                                Span.SpanKind.SPAN_KIND_CLIENT,
                                "HTTP GET payments.example.com /api/risk/check",
                                at(traceStart, 12), at(traceStart, 19),
                                kv("http.method", "GET"),
                                kv("http.status_code", "200"),
                                kv("url.full", "https://payments.example.com/api/risk/check"),
                                kv("server.address", "payments.example.com"),
                                kv("server.port", "443")))
                        .addSpans(serviceAHttp)
                        .addSpans(span(traceId, esClient, root,
                                Span.SpanKind.SPAN_KIND_CLIENT, "orders/_search",
                                at(traceStart, esStartMs), at(traceStart, esEndMs),
                                kv("db.system", "elasticsearch"),
                                kv("db.elasticsearch.index", "orders"),
                                kv("server.address", "es"),
                                kv("server.port", "9200")))
                        .addSpans(span(traceId, dubboClient, root,
                                Span.SpanKind.SPAN_KIND_CLIENT,
                                "Dubbo DemoOrderService.findInventory",
                                at(traceStart, lateStartMs), at(traceStart, lateStartMs + 45),
                                kv("rpc.system", "dubbo"),
                                kv("rpc.service", "com.databuff.demo.OrderService"),
                                kv("rpc.method", "findInventory"),
                                kv("net.peer.name", "service-b"),
                                kv("net.peer.port", "20880")))
                        .addSpans(span(traceId, auditMysql, root,
                                Span.SpanKind.SPAN_KIND_CLIENT, "INSERT demo_order_audit",
                                at(traceStart, auditStartMs), at(traceStart, auditEndMs),
                                kv("db.system", "mysql"),
                                kv("db.name", "demo_apm"),
                                kv("db.statement", "INSERT INTO demo_order_audit(order_id, channel) VALUES (?, ?)"),
                                kv("server.address", "mysql"),
                                kv("server.port", "3306")))
                        .addSpans(span(traceId, kafkaClient, root,
                                Span.SpanKind.SPAN_KIND_CLIENT, "order-events publish",
                                at(traceStart, kafkaStartMs), at(traceStart, kafkaEndMs),
                                kv("messaging.system", "kafka"),
                                kv("messaging.destination.name", "order-events"),
                                kv("messaging.operation", "publish"),
                                kv("net.peer.name", "kafka"),
                                kv("server.port", "9092"))))
                .build();

        Span.Builder serviceBEntry = span(traceId, httpServer, httpClient,
                Span.SpanKind.SPAN_KIND_SERVER, "GET /api/orders/{orderId}",
                at(traceStart, 30), at(traceStart, serviceBEndMs),
                kv("http.method", "GET"),
                kv("http.status_code", "200"),
                kv("url.full", "/api/orders/10001"));
        addFaultAttributes(serviceBEntry, fault);

        Span.Builder cacheSpan = span(traceId, orderCache, httpServer,
                Span.SpanKind.SPAN_KIND_CLIENT, "GET order:10001",
                at(traceStart, 35), at(traceStart, 38),
                kv("db.system", "redis"),
                kv("db.statement", "GET order:10001"),
                kv("server.address", "redis"),
                kv("server.port", "6379"),
                kv("cache.hit", Boolean.toString(!fault.active())),
                kv("cache.ttl_seconds", Integer.toString(fault.cacheTtlSeconds())));
        addFaultAttributes(cacheSpan, fault);

        Span.Builder orderDbSpan = span(traceId, orderMysql, httpServer,
                Span.SpanKind.SPAN_KIND_CLIENT, "SELECT demo_order",
                at(traceStart, orderDbStartMs), at(traceStart, orderDbEndMs),
                kv("db.system", "mysql"),
                kv("db.name", "demo_apm"),
                kv("db.statement", "SELECT id, amount, status FROM demo_order WHERE id = ?"),
                kv("server.address", "mysql"),
                kv("server.port", "3306"),
                kv("demo.cache_fallback", Boolean.toString(fault.active())));
        addFaultAttributes(orderDbSpan, fault);

        ResourceSpans serviceB = ResourceSpans.newBuilder()
                .setResource(serviceResource(SERVICE_B, "service-b-1", "demo-host-b"))
                .addScopeSpans(ScopeSpans.newBuilder()
                        .addSpans(serviceBEntry)
                        .addSpans(cacheSpan)
                        .addSpans(orderDbSpan)
                        .addSpans(span(traceId, dubboServer, dubboClient,
                                Span.SpanKind.SPAN_KIND_SERVER,
                                "Dubbo DemoOrderService.findInventory",
                                at(traceStart, lateStartMs + 5), at(traceStart, lateStartMs + 45),
                                kv("rpc.system", "dubbo"),
                                kv("rpc.service", "com.databuff.demo.OrderService"),
                                kv("rpc.method", "findInventory")))
                        .addSpans(span(traceId, inventoryMysql, dubboServer,
                                Span.SpanKind.SPAN_KIND_CLIENT, "SELECT demo_inventory",
                                at(traceStart, lateStartMs + 10), at(traceStart, lateStartMs + 35),
                                kv("db.system", "mysql"),
                                kv("db.name", "demo_apm"),
                                kv("db.statement", "SELECT sku, available FROM demo_inventory WHERE sku = ?"),
                                kv("server.address", "mysql"),
                                kv("server.port", "3306"))))
                .build();

        byte[] traceBytes = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(serviceA)
                .addResourceSpans(serviceB)
                .build()
                .toByteArray();
        return new DemoTraceBatch(
                traceBytes,
                traceId,
                traceStart,
                traceEnd,
                fault,
                spanRef(SERVICE_A, "service-a-1", "demo-host-a", root, at(traceStart, 10)),
                spanRef(SERVICE_A, "service-a-1", "demo-host-a", httpClient, at(traceStart, 25)),
                spanRef(SERVICE_B, "service-b-1", "demo-host-b", httpServer, at(traceStart, 55)),
                spanRef(SERVICE_B, "service-b-1", "demo-host-b", orderCache, at(traceStart, 36)),
                spanRef(SERVICE_B, "service-b-1", "demo-host-b", orderMysql,
                        at(traceStart, orderDbStartMs + 5)));
    }

    private static void addFaultAttributes(Span.Builder span, DemoFaultSnapshot fault) {
        span.addAttributes(kv("demo.scenario", DemoFaultController.SCENARIO));
        span.addAttributes(kv("demo.fault.active", Boolean.toString(fault.active())));
        span.addAttributes(kv("config.key", DemoConfigurationChange.CHANGE_KEY));
        span.addAttributes(kv("config.version", fault.configVersion()));
        span.addAttributes(kv("cache.ttl_seconds", Integer.toString(fault.cacheTtlSeconds())));
        if (fault.active()) {
            span.addAttributes(kv("fault.run_id", fault.faultRunId()));
        }
    }

    private static long at(long traceStart, long offsetMs) {
        return traceStart + ms(offsetMs);
    }

    private static long ms(long value) {
        return value * 1_000_000L;
    }

    private static DemoTraceBatch.SpanRef spanRef(
            String service, String instanceId, String hostName, ByteString spanId, long timeNanos) {
        return new DemoTraceBatch.SpanRef(spanId, service, instanceId, hostName, timeNanos);
    }

    public static byte[] sampleErrorTraceExport() {
        long end = Instant.now().toEpochMilli() * 1_000_000L;
        long start = end - 50_000_000L;
        ByteString traceId = randomId(16);
        ByteString spanId = randomId(8);
        return ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .setResource(serviceResource(DEMO_SERVICE, "service-a-1", "demo-host-a"))
                        .addScopeSpans(ScopeSpans.newBuilder()
                                .addSpans(Span.newBuilder()
                                        .setTraceId(traceId)
                                        .setSpanId(spanId)
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
        return OtlpHttpExporter.postProtobuf(
                ingestBaseUrl, "/v1/metrics", sampleJvmPoolMetricsExport());
    }

    /** CLI: post one normal trace batch, or dump fixture bytes. */
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
            java.nio.file.Files.write(
                    java.nio.file.Path.of(args[1]), OtlpLogFixture.logExport(nextTraceBatch()));
            System.out.println("wrote " + args[1]);
            return;
        }
        String base = args.length > 0 ? args[0] : "http://127.0.0.1:4318";
        DemoTraceBatch batch = nextTraceBatch();
        int status = postTraceBatch(base, batch);
        int logStatus = OtlpLogFixture.postLogs(base, batch);
        int metricStatus = postMetrics(base);
        if (status < 200 || status >= 300 || logStatus < 200 || logStatus >= 300
                || metricStatus < 200 || metricStatus >= 300) {
            throw new IllegalStateException("OTLP export failed with HTTP "
                    + status + "/" + logStatus + "/" + metricStatus);
        }
        System.out.println("seeded trace + logs + metrics to " + base + " (HTTP "
                + status + "/" + logStatus + "/" + metricStatus + ")");
    }

    private static Resource.Builder serviceResource(
            String serviceName, String instanceId, String hostName) {
        return Resource.newBuilder()
                .addAttributes(kv("service.name", serviceName))
                .addAttributes(kv("host.name", hostName))
                .addAttributes(kv("service.instance.id", instanceId))
                .addAttributes(kv("k8s.namespace.name", "demo"))
                .addAttributes(kv("telemetry.sdk.language", "java"))
                .addAttributes(kv("process.runtime.name", "OpenJDK Runtime Environment"))
                .addAttributes(kv("process.runtime.version", "17.0.9"));
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
