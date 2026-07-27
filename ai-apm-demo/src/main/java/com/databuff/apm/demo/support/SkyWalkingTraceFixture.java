package com.databuff.apm.demo.support;

import org.apache.skywalking.apm.network.common.v3.KeyStringValuePair;
import org.apache.skywalking.apm.network.language.agent.v3.RefType;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentCollection;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentReference;
import org.apache.skywalking.apm.network.language.agent.v3.SpanObject;
import org.apache.skywalking.apm.network.language.agent.v3.SpanType;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * SkyWalking checkout demo — span topology aligned with {@link OtlpTraceFixture}
 * so per-minute request counts match OTLP (service-a=2, service-b=4, etc.).
 */
public final class SkyWalkingTraceFixture {

    public static final String SERVICE_A = OtlpTraceFixture.SERVICE_A;
    public static final String SERVICE_B = OtlpTraceFixture.SERVICE_B;
    private static final String HOST_A = "demo-host-a";
    private static final String HOST_B = "demo-host-b";

    private SkyWalkingTraceFixture() {
    }

    public static DemoSkyWalkingBatch nextBatch() {
        long traceEndMs = Instant.now().toEpochMilli();
        long traceStartMs = traceEndMs - 240;
        String traceId = randomTraceId();
        String segmentAId = randomSegmentId();
        String segmentBId = randomSegmentId();
        long t0 = traceStartMs;

        // --- service-a segment (8 spans, mirrors OTLP resource service-a) ---
        SpanObject root = spanA(0, -1, SpanType.Entry, "GET /demo/checkout",
                t0, t0 + 240, false,
                tag("http.method", "GET"),
                tag("url", "/demo/checkout"),
                tag("http.status_code", "200"));

        SpanObject redisClient = exitSpanA(1, 0, "GET cart",
                t0 + 5, t0 + 18, "redis:6379",
                tag("db.type", "redis"),
                tag("db.statement", "GET cart:10001"));

        SpanObject remoteHttp = exitSpanA(2, 0, "HTTP GET payments.example.com /api/risk/check",
                t0 + 12, t0 + 19, "payments.example.com:443",
                tag("http.method", "GET"),
                tag("url", "https://payments.example.com/api/risk/check"),
                tag("http.status_code", "200"));

        SpanObject httpClient = exitSpanA(3, 0, "HTTP GET service-b /api/orders",
                t0 + 20, t0 + 120, "service-b:8080",
                tag("http.method", "GET"),
                tag("url", "http://service-b:8080/api/orders/10001"),
                tag("http.status_code", "200"));

        SpanObject esClient = exitSpanA(4, 0, "/orders/_search",
                t0 + 100, t0 + 118, "es:9200",
                tag("db.type", "elasticsearch"),
                tag("db.statement", "orders/_search"));

        SpanObject dubboClient = exitSpanA(5, 0, "Dubbo DemoOrderService.findInventory",
                t0 + 125, t0 + 205, "service-b:20880",
                tag("rpc.system", "dubbo"),
                tag("rpc.service", "com.databuff.demo.OrderService"),
                tag("rpc.method", "findInventory"));

        SpanObject auditMysql = exitSpanA(6, 0, "INSERT demo_order_audit",
                t0 + 208, t0 + 228, "mysql:3306",
                tag("db.type", "mysql"),
                tag("db.name", "demo_apm"),
                tag("db.statement", "INSERT INTO demo_order_audit(order_id, channel) VALUES (?, ?)"));

        SpanObject kafkaClient = exitSpanA(7, 0, "order-events publish",
                t0 + 230, t0 + 238, "kafka:9092",
                tag("messaging.system", "kafka"),
                tag("messaging.destination.name", "order-events"),
                tag("messaging.operation", "publish"));

        SegmentObject segmentA = SegmentObject.newBuilder()
                .setTraceId(traceId)
                .setTraceSegmentId(segmentAId)
                .setService(SERVICE_A)
                .setServiceInstance("service-a-1")
                .addSpans(root)
                .addSpans(redisClient)
                .addSpans(remoteHttp)
                .addSpans(httpClient)
                .addSpans(esClient)
                .addSpans(dubboClient)
                .addSpans(auditMysql)
                .addSpans(kafkaClient)
                .build();

        // --- service-b segment (5 spans, mirrors OTLP resource service-b) ---
        SpanObject entryB = spanB(0, -1, SpanType.Entry, "GET /api/orders/{orderId}",
                t0 + 30, t0 + 110, false,
                tag("http.method", "GET"),
                tag("url", "/api/orders/10001"),
                tag("http.status_code", "200"))
                .toBuilder()
                .addRefs(crossRef(traceId, segmentAId, 3, SERVICE_A, "service-a-1",
                        "GET /demo/checkout", "service-b:8080"))
                .build();

        SpanObject dbSpan = exitSpanB(1, 0, "SELECT demo_order",
                t0 + 45, t0 + 90, "mysql:3306",
                tag("db.type", "mysql"),
                tag("db.name", "demo_apm"),
                tag("db.statement", "SELECT id, amount, status FROM demo_order WHERE id = ?"));

        SpanObject redisServerB = exitSpanB(2, 0, "SET order:10001",
                t0 + 95, t0 + 108, "redis:6379",
                tag("db.type", "redis"),
                tag("db.statement", "SET order:10001"));

        SpanObject dubboServer = spanB(3, -1, SpanType.Entry, "Dubbo DemoOrderService.findInventory",
                t0 + 135, t0 + 195, false,
                tag("rpc.system", "dubbo"),
                tag("rpc.service", "com.databuff.demo.OrderService"),
                tag("rpc.method", "findInventory"))
                .toBuilder()
                .addRefs(crossRef(traceId, segmentAId, 5, SERVICE_A, "service-a-1",
                        "Dubbo DemoOrderService.findInventory", "service-b:20880"))
                .build();

        SpanObject errorDbSpan = exitSpanB(4, 3, "SELECT demo_inventory",
                t0 + 150, t0 + 180, "mysql:3306", true,
                tag("db.type", "mysql"),
                tag("db.name", "demo_apm"),
                tag("db.statement", "SELECT sku, available FROM demo_inventory WHERE sku = ?"),
                tag("error.type", "InsufficientStockException"));

        SegmentObject segmentB = SegmentObject.newBuilder()
                .setTraceId(traceId)
                .setTraceSegmentId(segmentBId)
                .setService(SERVICE_B)
                .setServiceInstance("service-b-1")
                .addSpans(entryB)
                .addSpans(dbSpan)
                .addSpans(redisServerB)
                .addSpans(dubboServer)
                .addSpans(errorDbSpan)
                .build();

        SegmentCollection segments = SegmentCollection.newBuilder()
                .addSegments(segmentA)
                .addSegments(segmentB)
                .build();

        return new DemoSkyWalkingBatch(
                segments,
                traceId,
                segmentAId,
                segmentBId,
                traceStartMs,
                traceEndMs,
                spanRef(segmentAId, 0, SERVICE_A, "service-a-1", HOST_A, t0 + 10),
                spanRef(segmentAId, 3, SERVICE_A, "service-a-1", HOST_A, t0 + 25),
                spanRef(segmentBId, 0, SERVICE_B, "service-b-1", HOST_B, t0 + 55),
                spanRef(segmentBId, 4, SERVICE_B, "service-b-1", HOST_B, t0 + 165));
    }

    private static SegmentReference crossRef(
            String traceId,
            String parentSegmentId,
            int parentSpanId,
            String parentService,
            String parentInstance,
            String parentEndpoint,
            String peer) {
        return SegmentReference.newBuilder()
                .setRefType(RefType.CrossProcess)
                .setTraceId(traceId)
                .setParentTraceSegmentId(parentSegmentId)
                .setParentSpanId(parentSpanId)
                .setParentService(parentService)
                .setParentServiceInstance(parentInstance)
                .setParentEndpoint(parentEndpoint)
                .setNetworkAddressUsedAtPeer(peer)
                .build();
    }

    private static DemoSkyWalkingBatch.SpanRef spanRef(
            String segmentId, int spanId, String service, String instance, String hostName, long timeMs) {
        return new DemoSkyWalkingBatch.SpanRef(segmentId, spanId, service, instance, hostName, timeMs);
    }

    private static SpanObject spanA(
            int spanId, int parentSpanId, SpanType spanType, String operationName,
            long startMs, long endMs, boolean errored, KeyStringValuePair... tags) {
        return span(spanId, parentSpanId, spanType, operationName, startMs, endMs, errored, HOST_A, tags);
    }

    private static SpanObject spanB(
            int spanId, int parentSpanId, SpanType spanType, String operationName,
            long startMs, long endMs, boolean errored, KeyStringValuePair... tags) {
        return span(spanId, parentSpanId, spanType, operationName, startMs, endMs, errored, HOST_B, tags);
    }

    private static SpanObject exitSpanA(
            int spanId, int parentSpanId, String operationName,
            long startMs, long endMs, String peer, KeyStringValuePair... tags) {
        return exitSpan(spanId, parentSpanId, operationName, startMs, endMs, peer, false, HOST_A, tags);
    }

    private static SpanObject exitSpanB(
            int spanId, int parentSpanId, String operationName,
            long startMs, long endMs, String peer, KeyStringValuePair... tags) {
        return exitSpan(spanId, parentSpanId, operationName, startMs, endMs, peer, false, HOST_B, tags);
    }

    private static SpanObject exitSpanB(
            int spanId, int parentSpanId, String operationName,
            long startMs, long endMs, String peer, boolean errored, KeyStringValuePair... tags) {
        return exitSpan(spanId, parentSpanId, operationName, startMs, endMs, peer, errored, HOST_B, tags);
    }

    private static SpanObject exitSpan(
            int spanId,
            int parentSpanId,
            String operationName,
            long startMs,
            long endMs,
            String peer,
            boolean errored,
            String hostName,
            KeyStringValuePair... tags) {
        return span(spanId, parentSpanId, SpanType.Exit, operationName, startMs, endMs, errored, hostName, tags)
                .toBuilder()
                .setPeer(peer)
                .build();
    }

    private static SpanObject span(
            int spanId,
            int parentSpanId,
            SpanType spanType,
            String operationName,
            long startMs,
            long endMs,
            boolean errored,
            String hostName,
            KeyStringValuePair... tags) {
        SpanObject.Builder builder = SpanObject.newBuilder()
                .setSpanId(spanId)
                .setParentSpanId(parentSpanId)
                .setStartTime(startMs)
                .setEndTime(endMs)
                .setOperationName(operationName)
                .setSpanType(spanType)
                .setIsError(errored);
        builder.addTags(tag("host.name", hostName));
        builder.addTags(tag("k8s.namespace.name", "demo"));
        for (KeyStringValuePair t : tags) {
            builder.addTags(t);
        }
        return builder.build();
    }

    private static KeyStringValuePair tag(String key, String value) {
        return KeyStringValuePair.newBuilder().setKey(key).setValue(value).build();
    }

    private static String randomTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String randomSegmentId() {
        byte[] bytes = new byte[16];
        ThreadLocalRandom.current().nextBytes(bytes);
        StringBuilder hex = new StringBuilder(32);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
