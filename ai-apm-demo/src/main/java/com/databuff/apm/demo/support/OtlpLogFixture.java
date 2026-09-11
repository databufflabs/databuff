package com.databuff.apm.demo.support;

import com.databuff.apm.demo.fault.DemoConfigurationChange;
import com.databuff.apm.demo.fault.DemoFaultSnapshot;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.opentelemetry.proto.resource.v1.Resource;

/** Correlated request logs and untraced configuration audit logs for the demo scenario. */
public final class OtlpLogFixture {

    private OtlpLogFixture() {
    }

    public static byte[] logExport(DemoTraceBatch batch) {
        ExportLogsServiceRequest.Builder request = ExportLogsServiceRequest.newBuilder();
        DemoFaultSnapshot fault = batch.fault();

        DemoTraceBatch.SpanRef root = batch.serviceARoot();
        request.addResourceLogs(resourceLogs(root,
                info(batch, root, root.timeNanos(),
                        "Received checkout request orderId=10001 channel=web"),
                info(batch, root, root.timeNanos() + ms(8),
                        "Checkout started orderId=10001"),
                info(batch, root, root.timeNanos() + ms(15),
                        "Delegating order lookup to service-b")));

        DemoTraceBatch.SpanRef httpClient = batch.serviceAHttpClient();
        request.addResourceLogs(resourceLogs(httpClient,
                info(batch, httpClient, httpClient.timeNanos(),
                        "HTTP client calling service-b GET /api/orders/10001"),
                info(batch, httpClient, httpClient.timeNanos() + ms(12),
                        fault.active()
                                ? "Downstream service-b responded in 3100ms; GET /demo/checkout durationMs=3200 status=200"
                                : "service-b responded 200 in 100ms; GET /demo/checkout durationMs=240")));

        DemoTraceBatch.SpanRef httpServer = batch.serviceBHttpServer();
        request.addResourceLogs(resourceLogs(httpServer,
                info(batch, httpServer, httpServer.timeNanos(),
                        "GET /api/orders/10001 from service-a"),
                fault.active()
                        ? warn(batch, httpServer, httpServer.timeNanos() + ms(8),
                                "Order detail cache bypassed orderId=10001 ttlSeconds=0 cacheHit=false")
                        : info(batch, httpServer, httpServer.timeNanos() + ms(8),
                                "Order detail cache hit orderId=10001 ttlSeconds=300 cacheHit=true"),
                fault.active()
                        ? warn(batch, httpServer, httpServer.timeNanos() + ms(22),
                                "Slow request GET /api/orders/10001 durationMs=3100 status=200")
                        : info(batch, httpServer, httpServer.timeNanos() + ms(22),
                                "GET /api/orders/10001 completed durationMs=80 status=200")));

        DemoTraceBatch.SpanRef orderDb = batch.serviceBOrderDatabase();
        request.addResourceLogs(resourceLogs(orderDb,
                info(batch, orderDb, orderDb.timeNanos(),
                        fault.active()
                                ? "Cache miss forced demo_order fallback query orderId=10001"
                                : "Validated order 10001 from demo_order"),
                fault.active()
                        ? warn(batch, orderDb, orderDb.timeNanos() + ms(10),
                                "Slow demo_order query durationMs=2950 rows=1")
                        : info(batch, orderDb, orderDb.timeNanos() + ms(10),
                                "demo_order query completed durationMs=45 rows=1")));
        return request.build().toByteArray();
    }

    public static byte[] configurationLogExport(DemoConfigurationChange change) {
        long timeNanos = change.occurredAt().toEpochMilli() * 1_000_000L;
        LogRecord record = LogRecord.newBuilder()
                .setTimeUnixNano(timeNanos)
                .setObservedTimeUnixNano(timeNanos)
                .setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_INFO)
                .setSeverityText("INFO")
                .setBody(AnyValue.newBuilder().setStringValue(
                        change.logEvent() + " change_key=" + DemoConfigurationChange.CHANGE_KEY
                                + " before=" + change.beforeValue()
                                + " after=" + change.afterValue()
                                + " fault_run_id=" + change.faultRunId()
                                + " config_version=" + change.configVersion()))
                .addAttributes(kv("event.name", change.logEvent()))
                .addAttributes(kv("event.kind", "config_change"))
                .addAttributes(kv("change.action", change.actionTag()))
                .addAttributes(kv("change.key", DemoConfigurationChange.CHANGE_KEY))
                .addAttributes(kv("change.before", change.beforeValue()))
                .addAttributes(kv("change.after", change.afterValue()))
                .addAttributes(kv("fault.run_id", change.faultRunId()))
                .addAttributes(kv("config.version", change.configVersion()))
                .addAttributes(kv("event.id", change.eventId()))
                .build();
        return ExportLogsServiceRequest.newBuilder()
                .addResourceLogs(ResourceLogs.newBuilder()
                        .setResource(serviceResource("service-b", "service-b-1", "demo-host-b"))
                        .addScopeLogs(ScopeLogs.newBuilder().addLogRecords(record)))
                .build()
                .toByteArray();
    }

    public static int postLogs(String ingestBaseUrl, DemoTraceBatch batch) throws Exception {
        return OtlpHttpExporter.postProtobuf(ingestBaseUrl, "/v1/logs", logExport(batch));
    }

    public static int postConfigurationLog(
            String ingestBaseUrl, DemoConfigurationChange change) throws Exception {
        return OtlpHttpExporter.postProtobuf(
                ingestBaseUrl, "/v1/logs", configurationLogExport(change));
    }

    private static ResourceLogs resourceLogs(DemoTraceBatch.SpanRef span, LogRecord... records) {
        ScopeLogs.Builder scopeLogs = ScopeLogs.newBuilder();
        for (LogRecord record : records) {
            scopeLogs.addLogRecords(record);
        }
        return ResourceLogs.newBuilder()
                .setResource(serviceResource(span.service(), span.instanceId(), span.hostName()))
                .addScopeLogs(scopeLogs)
                .build();
    }

    private static LogRecord info(
            DemoTraceBatch batch, DemoTraceBatch.SpanRef span, long timeNanos, String message) {
        return logRecord(
                batch, span, timeNanos, SeverityNumber.SEVERITY_NUMBER_INFO, "INFO", message);
    }

    private static LogRecord warn(
            DemoTraceBatch batch, DemoTraceBatch.SpanRef span, long timeNanos, String message) {
        return logRecord(
                batch, span, timeNanos, SeverityNumber.SEVERITY_NUMBER_WARN, "WARN", message);
    }

    private static LogRecord logRecord(
            DemoTraceBatch batch,
            DemoTraceBatch.SpanRef span,
            long timeNanos,
            SeverityNumber severityNumber,
            String severityText,
            String body) {
        LogRecord.Builder record = LogRecord.newBuilder()
                .setTimeUnixNano(timeNanos)
                .setObservedTimeUnixNano(timeNanos)
                .setSeverityNumber(severityNumber)
                .setSeverityText(severityText)
                .setTraceId(batch.traceId())
                .setSpanId(span.spanId())
                .setBody(AnyValue.newBuilder().setStringValue(body))
                .addAttributes(kv("order.id", "10001"))
                .addAttributes(kv("config.version", batch.fault().configVersion()))
                .addAttributes(kv("cache.ttl_seconds",
                        Integer.toString(batch.fault().cacheTtlSeconds())));
        if (batch.fault().active()) {
            record.addAttributes(kv("fault.run_id", batch.fault().faultRunId()));
        }
        return record.build();
    }

    private static Resource.Builder serviceResource(
            String serviceName, String instanceId, String hostName) {
        return Resource.newBuilder()
                .addAttributes(kv("service.name", serviceName))
                .addAttributes(kv("host.name", hostName))
                .addAttributes(kv("service.instance.id", instanceId))
                .addAttributes(kv("k8s.namespace.name", "demo"));
    }

    private static long ms(long value) {
        return value * 1_000_000L;
    }

    private static KeyValue kv(String key, String value) {
        return KeyValue.newBuilder()
                .setKey(key)
                .setValue(AnyValue.newBuilder().setStringValue(value))
                .build();
    }
}
