package com.databuff.apm.demo.support;

import com.databuff.apm.demo.fault.DemoConfigurationChange;
import com.databuff.apm.demo.fault.DemoFaultController;
import com.databuff.apm.demo.fault.DemoFaultSnapshot;
import org.apache.skywalking.apm.network.common.v3.KeyStringValuePair;
import org.apache.skywalking.apm.network.language.agent.v3.RefType;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentCollection;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentReference;
import org.apache.skywalking.apm.network.language.agent.v3.SpanObject;
import org.apache.skywalking.apm.network.language.agent.v3.SpanType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** SkyWalking form of the same normal/fault topology produced by {@link OtlpTraceFixture}. */
public final class SkyWalkingTraceFixture {

    public static final String SERVICE_A = OtlpTraceFixture.SERVICE_A;
    public static final String SERVICE_B = OtlpTraceFixture.SERVICE_B;
    private static final String HOST_A = "demo-host-a";
    private static final String HOST_B = "demo-host-b";

    private SkyWalkingTraceFixture() {
    }

    public static DemoSkyWalkingBatch nextBatch() {
        return nextBatch(DemoFaultSnapshot.normal());
    }

    public static DemoSkyWalkingBatch nextBatch(DemoFaultSnapshot fault) {
        long rootDurationMs = fault.active() ? 3_200L : 240L;
        long serviceBEndMs = fault.active() ? 3_130L : 110L;
        long httpClientEndMs = fault.active() ? 3_130L : 120L;
        long orderDbStartMs = fault.active() ? 70L : 45L;
        long orderDbEndMs = fault.active() ? 3_020L : 90L;
        long lateStartMs = fault.active() ? 3_135L : 125L;
        long traceEndMs = Instant.now().toEpochMilli();
        long traceStartMs = traceEndMs - rootDurationMs;
        long t0 = traceStartMs;
        String traceId = randomTraceId();
        String segmentAId = randomSegmentId();
        String segmentBId = randomSegmentId();

        SpanObject root = spanA(0, -1, SpanType.Entry, "GET /demo/checkout",
                t0, t0 + rootDurationMs, false,
                faultTags(fault,
                        tag("http.method", "GET"),
                        tag("url", "/demo/checkout"),
                        tag("status_code", "200")));
        SpanObject cartRedis = exitSpanA(1, 0, "GET cart",
                t0 + 5, t0 + 18, "redis:6379",
                tag("db.type", "redis"), tag("db.statement", "GET cart:10001"));
        SpanObject remoteHttp = exitSpanA(2, 0,
                "HTTP GET payments.example.com /api/risk/check",
                t0 + 12, t0 + 19, "payments.example.com:443",
                tag("http.method", "GET"),
                tag("url", "https://payments.example.com/api/risk/check"),
                tag("status_code", "200"));
        SpanObject httpClient = exitSpanA(3, 0, "HTTP GET service-b /api/orders",
                t0 + 20, t0 + httpClientEndMs, "service-b:8080",
                faultTags(fault,
                        tag("http.method", "GET"),
                        tag("url", "http://service-b:8080/api/orders/10001"),
                        tag("status_code", "200")));
        long esStartMs = fault.active() ? 3_132L : 100L;
        long esEndMs = fault.active() ? 3_150L : 118L;
        SpanObject esClient = exitSpanA(4, 0, "/orders/_search",
                t0 + esStartMs, t0 + esEndMs, "es:9200",
                tag("db.type", "elasticsearch"), tag("db.statement", "orders/_search"));
        SpanObject dubboClient = exitSpanA(5, 0, "Dubbo DemoOrderService.findInventory",
                t0 + lateStartMs, t0 + lateStartMs + 45, "service-b:20880",
                tag("rpc.system", "dubbo"),
                tag("rpc.service", "com.databuff.demo.OrderService"),
                tag("rpc.method", "findInventory"));
        long auditStartMs = fault.active() ? 3_180L : 208L;
        long auditEndMs = fault.active() ? 3_192L : 228L;
        SpanObject auditMysql = exitSpanA(6, 0, "INSERT demo_order_audit",
                t0 + auditStartMs, t0 + auditEndMs, "mysql:3306",
                tag("db.type", "mysql"),
                tag("db.name", "demo_apm"),
                tag("db.statement", "INSERT INTO demo_order_audit(order_id, channel) VALUES (?, ?)"));
        long kafkaStartMs = fault.active() ? 3_190L : 230L;
        long kafkaEndMs = fault.active() ? 3_198L : 238L;
        SpanObject kafkaClient = exitSpanA(7, 0, "order-events publish",
                t0 + kafkaStartMs, t0 + kafkaEndMs, "kafka:9092",
                tag("messaging.system", "kafka"),
                tag("messaging.destination.name", "order-events"),
                tag("messaging.operation", "publish"));

        SegmentObject segmentA = SegmentObject.newBuilder()
                .setTraceId(traceId)
                .setTraceSegmentId(segmentAId)
                .setService(SERVICE_A)
                .setServiceInstance("service-a-1")
                .addSpans(root)
                .addSpans(cartRedis)
                .addSpans(remoteHttp)
                .addSpans(httpClient)
                .addSpans(esClient)
                .addSpans(dubboClient)
                .addSpans(auditMysql)
                .addSpans(kafkaClient)
                .build();

        SpanObject entryB = spanB(0, -1, SpanType.Entry, "GET /api/orders/{orderId}",
                t0 + 30, t0 + serviceBEndMs, false,
                faultTags(fault,
                        tag("http.method", "GET"),
                        tag("url", "/api/orders/10001"),
                        tag("status_code", "200")))
                .toBuilder()
                .addRefs(crossRef(traceId, segmentAId, 3, SERVICE_A, "service-a-1",
                        "GET /demo/checkout", "service-b:8080"))
                .build();
        SpanObject orderCache = exitSpanB(1, 0, "GET order:10001",
                t0 + 35, t0 + 38, "redis:6379",
                faultTags(fault,
                        tag("db.type", "redis"),
                        tag("db.statement", "GET order:10001"),
                        tag("cache.hit", Boolean.toString(!fault.active())),
                        tag("cache.ttl_seconds", Integer.toString(fault.cacheTtlSeconds()))));
        SpanObject orderDb = exitSpanB(2, 0, "SELECT demo_order",
                t0 + orderDbStartMs, t0 + orderDbEndMs, "mysql:3306",
                faultTags(fault,
                        tag("db.type", "mysql"),
                        tag("db.name", "demo_apm"),
                        tag("db.statement", "SELECT id, amount, status FROM demo_order WHERE id = ?"),
                        tag("demo.cache_fallback", Boolean.toString(fault.active()))));
        SpanObject dubboServer = spanB(3, -1, SpanType.Entry,
                "Dubbo DemoOrderService.findInventory",
                t0 + lateStartMs + 5, t0 + lateStartMs + 45, false,
                tag("rpc.system", "dubbo"),
                tag("rpc.service", "com.databuff.demo.OrderService"),
                tag("rpc.method", "findInventory"))
                .toBuilder()
                .addRefs(crossRef(traceId, segmentAId, 5, SERVICE_A, "service-a-1",
                        "Dubbo DemoOrderService.findInventory", "service-b:20880"))
                .build();
        SpanObject inventoryDb = exitSpanB(4, 3, "SELECT demo_inventory",
                t0 + lateStartMs + 10, t0 + lateStartMs + 35, "mysql:3306",
                tag("db.type", "mysql"),
                tag("db.name", "demo_apm"),
                tag("db.statement", "SELECT sku, available FROM demo_inventory WHERE sku = ?"));

        SegmentObject segmentB = SegmentObject.newBuilder()
                .setTraceId(traceId)
                .setTraceSegmentId(segmentBId)
                .setService(SERVICE_B)
                .setServiceInstance("service-b-1")
                .addSpans(entryB)
                .addSpans(orderCache)
                .addSpans(orderDb)
                .addSpans(dubboServer)
                .addSpans(inventoryDb)
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
                fault,
                spanRef(segmentAId, 0, SERVICE_A, "service-a-1", HOST_A, t0 + 10),
                spanRef(segmentAId, 3, SERVICE_A, "service-a-1", HOST_A, t0 + 25),
                spanRef(segmentBId, 0, SERVICE_B, "service-b-1", HOST_B, t0 + 55),
                spanRef(segmentBId, 1, SERVICE_B, "service-b-1", HOST_B, t0 + 36),
                spanRef(segmentBId, 2, SERVICE_B, "service-b-1", HOST_B,
                        t0 + orderDbStartMs + 5));
    }

    private static KeyStringValuePair[] faultTags(
            DemoFaultSnapshot fault, KeyStringValuePair... base) {
        List<KeyStringValuePair> tags = new ArrayList<>(List.of(base));
        tags.add(tag("demo.scenario", DemoFaultController.SCENARIO));
        tags.add(tag("demo.fault.active", Boolean.toString(fault.active())));
        tags.add(tag("config.key", DemoConfigurationChange.CHANGE_KEY));
        tags.add(tag("config.version", fault.configVersion()));
        tags.add(tag("cache.ttl_seconds", Integer.toString(fault.cacheTtlSeconds())));
        if (fault.active()) {
            tags.add(tag("fault.run_id", fault.faultRunId()));
        }
        return tags.toArray(KeyStringValuePair[]::new);
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
            String segmentId,
            int spanId,
            String service,
            String instance,
            String hostName,
            long timeMs) {
        return new DemoSkyWalkingBatch.SpanRef(
                segmentId, spanId, service, instance, hostName, timeMs);
    }

    private static SpanObject spanA(
            int spanId,
            int parentSpanId,
            SpanType spanType,
            String operationName,
            long startMs,
            long endMs,
            boolean errored,
            KeyStringValuePair... tags) {
        return span(spanId, parentSpanId, spanType, operationName,
                startMs, endMs, errored, HOST_A, tags);
    }

    private static SpanObject spanB(
            int spanId,
            int parentSpanId,
            SpanType spanType,
            String operationName,
            long startMs,
            long endMs,
            boolean errored,
            KeyStringValuePair... tags) {
        return span(spanId, parentSpanId, spanType, operationName,
                startMs, endMs, errored, HOST_B, tags);
    }

    private static SpanObject exitSpanA(
            int spanId,
            int parentSpanId,
            String operationName,
            long startMs,
            long endMs,
            String peer,
            KeyStringValuePair... tags) {
        return exitSpan(spanId, parentSpanId, operationName,
                startMs, endMs, peer, HOST_A, tags);
    }

    private static SpanObject exitSpanB(
            int spanId,
            int parentSpanId,
            String operationName,
            long startMs,
            long endMs,
            String peer,
            KeyStringValuePair... tags) {
        return exitSpan(spanId, parentSpanId, operationName,
                startMs, endMs, peer, HOST_B, tags);
    }

    private static SpanObject exitSpan(
            int spanId,
            int parentSpanId,
            String operationName,
            long startMs,
            long endMs,
            String peer,
            String hostName,
            KeyStringValuePair... tags) {
        return span(spanId, parentSpanId, SpanType.Exit, operationName,
                startMs, endMs, false, hostName, tags)
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
                .setIsError(errored)
                .addTags(tag("host.name", hostName))
                .addTags(tag("k8s.namespace.name", "demo"));
        for (KeyStringValuePair value : tags) {
            builder.addTags(value);
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
        for (byte current : bytes) {
            hex.append(String.format("%02x", current));
        }
        return hex.toString();
    }
}
