package com.databuff.apm.demo.support;

import com.databuff.apm.demo.fault.DemoConfigurationChange;
import com.databuff.apm.demo.fault.DemoFaultSnapshot;
import org.apache.skywalking.apm.network.logging.v3.LogData;
import org.apache.skywalking.apm.network.logging.v3.LogDataBody;
import org.apache.skywalking.apm.network.logging.v3.LogTags;
import org.apache.skywalking.apm.network.logging.v3.TextLog;
import org.apache.skywalking.apm.network.logging.v3.TraceContext;

import java.util.ArrayList;
import java.util.List;

/** SkyWalking logs aligned with {@link OtlpLogFixture}. */
public final class SkyWalkingLogFixture {

    private SkyWalkingLogFixture() {
    }

    public static List<LogData> logsForBatch(DemoSkyWalkingBatch batch) {
        List<LogData> logs = new ArrayList<>();
        DemoFaultSnapshot fault = batch.fault();

        DemoSkyWalkingBatch.SpanRef root = batch.serviceARoot();
        logs.add(log(root, batch, root.timeMs(), "INFO",
                "Received checkout request orderId=10001 channel=web"));
        logs.add(log(root, batch, root.timeMs() + 8, "INFO",
                "Checkout started orderId=10001"));
        logs.add(log(root, batch, root.timeMs() + 15, "INFO",
                "Delegating order lookup to service-b"));

        DemoSkyWalkingBatch.SpanRef httpClient = batch.serviceAHttpClient();
        logs.add(log(httpClient, batch, httpClient.timeMs(), "INFO",
                "HTTP client calling service-b GET /api/orders/10001"));
        logs.add(log(httpClient, batch, httpClient.timeMs() + 12, "INFO",
                fault.active()
                        ? "Downstream service-b responded in 3100ms; GET /demo/checkout durationMs=3200 status=200"
                        : "service-b responded 200 in 100ms; GET /demo/checkout durationMs=240"));

        DemoSkyWalkingBatch.SpanRef httpServer = batch.serviceBHttpServer();
        logs.add(log(httpServer, batch, httpServer.timeMs(), "INFO",
                "GET /api/orders/10001 from service-a"));
        logs.add(log(httpServer, batch, httpServer.timeMs() + 8,
                fault.active() ? "WARN" : "INFO",
                fault.active()
                        ? "Order detail cache bypassed orderId=10001 ttlSeconds=0 cacheHit=false"
                        : "Order detail cache hit orderId=10001 ttlSeconds=300 cacheHit=true"));
        logs.add(log(httpServer, batch, httpServer.timeMs() + 22,
                fault.active() ? "WARN" : "INFO",
                fault.active()
                        ? "Slow request GET /api/orders/10001 durationMs=3100 status=200"
                        : "GET /api/orders/10001 completed durationMs=80 status=200"));

        DemoSkyWalkingBatch.SpanRef orderDb = batch.serviceBOrderDatabase();
        logs.add(log(orderDb, batch, orderDb.timeMs(), "INFO",
                fault.active()
                        ? "Cache miss forced demo_order fallback query orderId=10001"
                        : "Validated order 10001 from demo_order"));
        logs.add(log(orderDb, batch, orderDb.timeMs() + 10,
                fault.active() ? "WARN" : "INFO",
                fault.active()
                        ? "Slow demo_order query durationMs=2950 rows=1"
                        : "demo_order query completed durationMs=45 rows=1"));
        return logs;
    }

    public static LogData configurationLog(DemoConfigurationChange change) {
        LogTags.Builder tags = LogTags.newBuilder()
                .addData(KeyStringValuePairUtil.of("level", "INFO"))
                .addData(KeyStringValuePairUtil.of("host.name", "demo-host-b"))
                .addData(KeyStringValuePairUtil.of("k8s.namespace.name", "demo"))
                .addData(KeyStringValuePairUtil.of("event.name", change.logEvent()))
                .addData(KeyStringValuePairUtil.of("event.kind", "config_change"))
                .addData(KeyStringValuePairUtil.of("change.action", change.actionTag()))
                .addData(KeyStringValuePairUtil.of("change.key", DemoConfigurationChange.CHANGE_KEY))
                .addData(KeyStringValuePairUtil.of("change.before", change.beforeValue()))
                .addData(KeyStringValuePairUtil.of("change.after", change.afterValue()))
                .addData(KeyStringValuePairUtil.of("fault.run_id", change.faultRunId()))
                .addData(KeyStringValuePairUtil.of("config.version", change.configVersion()))
                .addData(KeyStringValuePairUtil.of("event.id", change.eventId()));
        return LogData.newBuilder()
                .setTimestamp(change.occurredAt().toEpochMilli())
                .setService("service-b")
                .setServiceInstance("service-b-1")
                .setBody(LogDataBody.newBuilder().setText(TextLog.newBuilder().setText(
                        change.logEvent() + " change_key=" + DemoConfigurationChange.CHANGE_KEY
                                + " before=" + change.beforeValue()
                                + " after=" + change.afterValue()
                                + " fault_run_id=" + change.faultRunId()
                                + " config_version=" + change.configVersion())))
                .setTags(tags)
                .build();
    }

    private static LogData log(
            DemoSkyWalkingBatch.SpanRef span,
            DemoSkyWalkingBatch batch,
            long timeMs,
            String level,
            String body) {
        LogTags.Builder tags = LogTags.newBuilder()
                .addData(KeyStringValuePairUtil.of("level", level))
                .addData(KeyStringValuePairUtil.of("host.name", span.hostName()))
                .addData(KeyStringValuePairUtil.of("k8s.namespace.name", "demo"))
                .addData(KeyStringValuePairUtil.of("order.id", "10001"))
                .addData(KeyStringValuePairUtil.of("config.version", batch.fault().configVersion()))
                .addData(KeyStringValuePairUtil.of(
                        "cache.ttl_seconds", Integer.toString(batch.fault().cacheTtlSeconds())));
        if (batch.fault().active()) {
            tags.addData(KeyStringValuePairUtil.of("fault.run_id", batch.fault().faultRunId()));
        }
        return LogData.newBuilder()
                .setTimestamp(timeMs)
                .setService(span.service())
                .setServiceInstance(span.serviceInstance())
                .setBody(LogDataBody.newBuilder().setText(TextLog.newBuilder().setText(body)))
                .setTraceContext(TraceContext.newBuilder()
                        .setTraceId(batch.traceId())
                        .setTraceSegmentId(span.segmentId())
                        .setSpanId(span.spanId()))
                .setTags(tags)
                .build();
    }
}
