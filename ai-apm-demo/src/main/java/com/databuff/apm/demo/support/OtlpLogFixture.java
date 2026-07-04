package com.databuff.apm.demo.support;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.opentelemetry.proto.resource.v1.Resource;

public final class OtlpLogFixture {

    private OtlpLogFixture() {
    }

    public static byte[] logExport(DemoTraceBatch batch) {
        ExportLogsServiceRequest.Builder request = ExportLogsServiceRequest.newBuilder();
        DemoTraceBatch.SpanRef root = batch.serviceARoot();
        request.addResourceLogs(resourceLogs(root,
                info(batch, root, root.timeNanos(),
                        "Received checkout request orderId=10001 channel=web"),
                info(batch, root, root.timeNanos() + 3_000_000L,
                        "Validating cart contents for user demo-user"),
                info(batch, root, root.timeNanos() + 8_000_000L,
                        "Checkout started orderId=10001"),
                info(batch, root, root.timeNanos() + 15_000_000L,
                        "Delegating inventory check to service-b")));

        DemoTraceBatch.SpanRef httpClient = batch.serviceAHttpClient();
        request.addResourceLogs(resourceLogs(httpClient,
                info(batch, httpClient, httpClient.timeNanos(),
                        "HTTP client calling service-b GET /api/orders/10001"),
                info(batch, httpClient, httpClient.timeNanos() + 12_000_000L,
                        "service-b responded 200 in 95ms")));

        DemoTraceBatch.SpanRef httpServer = batch.serviceBHttpServer();
        request.addResourceLogs(resourceLogs(httpServer,
                info(batch, httpServer, httpServer.timeNanos(),
                        "GET /api/orders/10001 from service-a"),
                info(batch, httpServer, httpServer.timeNanos() + 8_000_000L,
                        "Loaded order 10001 from demo_order"),
                info(batch, httpServer, httpServer.timeNanos() + 22_000_000L,
                        "Order status=CONFIRMED amount=199.00")));

        DemoTraceBatch.SpanRef dubboMysql = batch.serviceBDubboMysql();
        request.addResourceLogs(resourceLogs(dubboMysql,
                info(batch, dubboMysql, dubboMysql.timeNanos(),
                        "Querying inventory for sku DEMO-10001"),
                warn(batch, dubboMysql, dubboMysql.timeNanos() + 6_000_000L,
                        "Available stock below threshold (2 units)"),
                error(batch, dubboMysql, dubboMysql.timeNanos() + 12_000_000L,
                        "InsufficientStockException: inventory unavailable for sku DEMO-10001")));
        return request.build().toByteArray();
    }

    public static int postLogs(String ingestBaseUrl, DemoTraceBatch batch) throws Exception {
        return OtlpHttpExporter.postProtobuf(ingestBaseUrl, "/v1/logs", logExport(batch));
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
        return logRecord(batch.traceId(), span, timeNanos, SeverityNumber.SEVERITY_NUMBER_INFO, "INFO", message);
    }

    private static LogRecord warn(
            DemoTraceBatch batch, DemoTraceBatch.SpanRef span, long timeNanos, String message) {
        return logRecord(batch.traceId(), span, timeNanos, SeverityNumber.SEVERITY_NUMBER_WARN, "WARN", message);
    }

    private static LogRecord error(
            DemoTraceBatch batch, DemoTraceBatch.SpanRef span, long timeNanos, String message) {
        return logRecord(batch.traceId(), span, timeNanos, SeverityNumber.SEVERITY_NUMBER_ERROR, "ERROR", message);
    }

    private static LogRecord logRecord(
            ByteString traceId,
            DemoTraceBatch.SpanRef span,
            long timeNanos,
            SeverityNumber severityNumber,
            String severityText,
            String body) {
        return LogRecord.newBuilder()
                .setTimeUnixNano(timeNanos)
                .setObservedTimeUnixNano(timeNanos)
                .setSeverityNumber(severityNumber)
                .setSeverityText(severityText)
                .setTraceId(traceId)
                .setSpanId(span.spanId())
                .setBody(AnyValue.newBuilder().setStringValue(body))
                .addAttributes(kv("order.id", "10001"))
                .build();
    }

    private static Resource.Builder serviceResource(String serviceName, String instanceId, String hostName) {
        return Resource.newBuilder()
                .addAttributes(kv("service.name", serviceName))
                .addAttributes(kv("host.name", hostName))
                .addAttributes(kv("service.instance.id", instanceId))
                .addAttributes(kv("k8s.namespace.name", "demo"));
    }

    private static KeyValue kv(String key, String value) {
        return KeyValue.newBuilder()
                .setKey(key)
                .setValue(AnyValue.newBuilder().setStringValue(value))
                .build();
    }
}
