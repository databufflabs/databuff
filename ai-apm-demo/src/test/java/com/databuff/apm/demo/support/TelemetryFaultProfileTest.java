package com.databuff.apm.demo.support;

import com.databuff.apm.demo.fault.DemoConfigurationChange;
import com.databuff.apm.demo.fault.DemoFaultSnapshot;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.Span;
import org.apache.skywalking.apm.network.common.v3.KeyStringValuePair;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject;
import org.apache.skywalking.apm.network.language.agent.v3.SpanObject;
import org.apache.skywalking.apm.network.logging.v3.LogData;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryFaultProfileTest {

    private static final DemoFaultSnapshot FAULT = DemoFaultSnapshot.active(
            "fault-test-001",
            Instant.parse("2026-09-11T02:30:00Z"),
            Instant.parse("2026-09-11T02:33:00Z"));

    @Test
    void otlpFaultTraceShowsSlowServiceBPropagatingToServiceAWithHttp200() throws Exception {
        DemoTraceBatch batch = OtlpTraceFixture.nextTraceBatch(FAULT);
        ExportTraceServiceRequest trace = ExportTraceServiceRequest.parseFrom(batch.traceBytes());

        Span serviceA = otlpSpan(trace, "service-a", "GET /demo/checkout");
        Span serviceB = otlpSpan(trace, "service-b", "GET /api/orders/{orderId}");
        Span redis = otlpSpan(trace, "service-b", "GET order:10001");
        Span mysql = otlpSpan(trace, "service-b", "SELECT demo_order");

        assertThat(durationMillis(serviceA)).isEqualTo(3_200);
        assertThat(durationMillis(serviceB)).isEqualTo(3_100);
        assertThat(durationMillis(mysql)).isEqualTo(2_950);
        assertThat(attributes(serviceA))
                .containsEntry("http.status_code", "200")
                .containsEntry("fault.run_id", "fault-test-001")
                .containsEntry("config.version", "demo-cache-ttl-0");
        assertThat(attributes(serviceB)).containsEntry("http.status_code", "200");
        assertThat(attributes(redis))
                .containsEntry("cache.hit", "false")
                .containsEntry("cache.ttl_seconds", "0");
        assertThat(trace.getResourceSpansList().stream()
                .flatMap(resource -> resource.getScopeSpansList().stream())
                .flatMap(scope -> scope.getSpansList().stream())
                .anyMatch(span -> span.getStatus().getCode()
                        == io.opentelemetry.proto.trace.v1.Status.StatusCode.STATUS_CODE_ERROR))
                .isFalse();
    }

    @Test
    void otlpNormalTraceAndLogsReturnToTheFastCacheHitProfile() throws Exception {
        DemoTraceBatch batch = OtlpTraceFixture.nextTraceBatch(DemoFaultSnapshot.normal());
        ExportTraceServiceRequest trace = ExportTraceServiceRequest.parseFrom(batch.traceBytes());

        assertThat(durationMillis(otlpSpan(trace, "service-a", "GET /demo/checkout")))
                .isEqualTo(240);
        assertThat(durationMillis(otlpSpan(trace, "service-b", "GET /api/orders/{orderId}")))
                .isEqualTo(80);
        assertThat(durationMillis(otlpSpan(trace, "service-b", "SELECT demo_order")))
                .isEqualTo(45);
        assertThat(attributes(otlpSpan(trace, "service-b", "GET order:10001")))
                .containsEntry("cache.hit", "true")
                .containsEntry("cache.ttl_seconds", "300");

        String logs = otlpBodies(OtlpLogFixture.logExport(batch));
        assertThat(logs)
                .contains("cache hit orderId=10001 ttlSeconds=300")
                .doesNotContain("InsufficientStockException")
                .doesNotContain("Slow request");
    }

    @Test
    void otlpFaultLogsAreTraceCorrelatedAndCarryRunAndConfigVersion() throws Exception {
        DemoTraceBatch batch = OtlpTraceFixture.nextTraceBatch(FAULT);
        ExportLogsServiceRequest logs = ExportLogsServiceRequest.parseFrom(
                OtlpLogFixture.logExport(batch));

        List<LogRecord> records = logs.getResourceLogsList().stream()
                .flatMap(resource -> resource.getScopeLogsList().stream())
                .flatMap(scope -> scope.getLogRecordsList().stream())
                .toList();
        assertThat(records).isNotEmpty().allSatisfy(record -> {
            assertThat(record.getTraceId()).isEqualTo(batch.traceId());
            assertThat(record.getSpanId()).isNotEmpty();
            assertThat(attributes(record.getAttributesList()))
                    .containsEntry("fault.run_id", "fault-test-001")
                    .containsEntry("config.version", "demo-cache-ttl-0");
        });
        assertThat(records.stream().map(record -> record.getBody().getStringValue()).toList())
                .anyMatch(body -> body.contains("cache bypassed") && body.contains("ttlSeconds=0"))
                .anyMatch(body -> body.contains("durationMs=3100") && body.contains("status=200"))
                .anyMatch(body -> body.contains("durationMs=3200") && body.contains("service-b"));
    }

    @Test
    void configurationLogAndKafkaEventShareAuditKeys() throws Exception {
        DemoConfigurationChange change = new DemoConfigurationChange(
                "fault-test-001",
                DemoConfigurationChange.Action.APPLIED,
                Instant.parse("2026-09-11T02:30:00Z"));
        ExportLogsServiceRequest logs = ExportLogsServiceRequest.parseFrom(
                OtlpLogFixture.configurationLogExport(change));
        LogRecord record = logs.getResourceLogs(0).getScopeLogs(0).getLogRecords(0);
        Map<String, String> logAttributes = attributes(record.getAttributesList());
        String kafka = change.toKafkaJson();

        assertThat(logAttributes)
                .containsEntry("fault.run_id", "fault-test-001")
                .containsEntry("config.version", "demo-cache-ttl-0")
                .containsEntry("change.key", "order.detail.cache.ttl-seconds")
                .containsEntry("change.before", "300")
                .containsEntry("change.after", "0");
        assertThat(kafka)
                .startsWith("{")
                .endsWith("}")
                .contains("\"id\":\"change-fault-test-001-applied\"")
                .contains("\"fault_run_id\":\"fault-test-001\"")
                .contains("\"config_version\":\"demo-cache-ttl-0\"")
                .contains("\"change_key\":\"order.detail.cache.ttl-seconds\"")
                .contains("\"before\":\"300\"")
                .contains("\"after\":\"0\"");
    }

    @Test
    void skyWalkingFaultProfileMatchesOtlpSemantics() {
        DemoSkyWalkingBatch batch = SkyWalkingTraceFixture.nextBatch(FAULT);
        SpanObject serviceA = skyWalkingSpan(batch, "service-a", "GET /demo/checkout");
        SpanObject serviceB = skyWalkingSpan(
                batch, "service-b", "GET /api/orders/{orderId}");
        SpanObject redis = skyWalkingSpan(batch, "service-b", "GET order:10001");
        SpanObject mysql = skyWalkingSpan(batch, "service-b", "SELECT demo_order");

        assertThat(serviceA.getEndTime() - serviceA.getStartTime()).isEqualTo(3_200);
        assertThat(serviceB.getEndTime() - serviceB.getStartTime()).isEqualTo(3_100);
        assertThat(mysql.getEndTime() - mysql.getStartTime()).isEqualTo(2_950);
        assertThat(tags(redis))
                .containsEntry("cache.hit", "false")
                .containsEntry("cache.ttl_seconds", "0")
                .containsEntry("fault.run_id", "fault-test-001");
        assertThat(batch.segments().getSegmentsList())
                .flatExtracting(SegmentObject::getSpansList)
                .allSatisfy(span -> assertThat(span.getIsError()).isFalse());

        List<LogData> logs = SkyWalkingLogFixture.logsForBatch(batch);
        assertThat(logs).allSatisfy(log -> {
            assertThat(log.hasTraceContext()).isTrue();
            assertThat(tags(log)).containsEntry("fault.run_id", "fault-test-001");
        });
        assertThat(logs.stream().map(log -> log.getBody().getText().getText()).toList())
                .anyMatch(body -> body.contains("cache bypassed"))
                .anyMatch(body -> body.contains("durationMs=3100"))
                .anyMatch(body -> body.contains("durationMs=3200"));
    }

    private static Span otlpSpan(
            ExportTraceServiceRequest trace, String service, String name) {
        for (ResourceSpans resource : trace.getResourceSpansList()) {
            if (!service.equals(attributes(resource.getResource().getAttributesList())
                    .get("service.name"))) {
                continue;
            }
            for (var scope : resource.getScopeSpansList()) {
                for (Span span : scope.getSpansList()) {
                    if (name.equals(span.getName())) {
                        return span;
                    }
                }
            }
        }
        throw new AssertionError("span not found: " + service + "/" + name);
    }

    private static long durationMillis(Span span) {
        return (span.getEndTimeUnixNano() - span.getStartTimeUnixNano()) / 1_000_000L;
    }

    private static Map<String, String> attributes(Span span) {
        return attributes(span.getAttributesList());
    }

    private static Map<String, String> attributes(List<KeyValue> values) {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (KeyValue value : values) {
            attributes.put(value.getKey(), value.getValue().getStringValue());
        }
        return attributes;
    }

    private static String otlpBodies(byte[] export) throws Exception {
        return ExportLogsServiceRequest.parseFrom(export).getResourceLogsList().stream()
                .flatMap(resource -> resource.getScopeLogsList().stream())
                .flatMap(scope -> scope.getLogRecordsList().stream())
                .map(record -> record.getBody().getStringValue())
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private static SpanObject skyWalkingSpan(
            DemoSkyWalkingBatch batch, String service, String operation) {
        return batch.segments().getSegmentsList().stream()
                .filter(segment -> service.equals(segment.getService()))
                .flatMap(segment -> segment.getSpansList().stream())
                .filter(span -> operation.equals(span.getOperationName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "SkyWalking span not found: " + service + "/" + operation));
    }

    private static Map<String, String> tags(SpanObject span) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (KeyStringValuePair tag : span.getTagsList()) {
            tags.put(tag.getKey(), tag.getValue());
        }
        return tags;
    }

    private static Map<String, String> tags(LogData log) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (KeyStringValuePair tag : log.getTags().getDataList()) {
            tags.put(tag.getKey(), tag.getValue());
        }
        return tags;
    }
}
