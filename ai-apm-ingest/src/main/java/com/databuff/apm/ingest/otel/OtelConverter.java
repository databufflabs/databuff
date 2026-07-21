package com.databuff.apm.ingest.otel;

import com.databuff.apm.common.serde.DcSpanUtil;
import com.databuff.apm.common.meta.OtelAttributeMaps;
import com.databuff.apm.ingest.metric.JvmOtelMetricNormalizer;
import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.trace.TraceParentUtil;
import com.databuff.apm.common.trace.TraceSpanNames;
import com.databuff.apm.common.time.ApmTimeZones;
import com.databuff.apm.common.util.ServiceKeyUtil;
import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.metrics.v1.HistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step 1 · 格式转换：OTLP protobuf → DataBuff 内存对象。
 * <p>
 * 全程保持对象传递，避免在此处序列化为 JSON/bytes；后续 enrich / 聚合 / fill 均直接操作对象。
 */
public final class OtelConverter {

    private static final ObjectMapper METRIC_JSON = new ObjectMapper();
    private static final DateTimeFormatter DATETIME = ApmTimeZones.WALL_CLOCK;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** OTLP ExportTraceServiceRequest → {@link DcSpan} 列表。 */
    /** OTLP ExportTraceServiceRequest → {@link DcSpan} 列表。 */
    public List<ConvertedTrace> convertTraces(ExportTraceServiceRequest request) {
        List<ConvertedTrace> out = new ArrayList<>();
        for (ResourceSpans resourceSpans : request.getResourceSpansList()) {
            String serviceName = attribute(resourceSpans.getResource().getAttributesList(), "service.name");
            if (serviceName == null || serviceName.isBlank()) {
                continue;
            }
            String serviceKey = ServiceKeyUtil.of(serviceName);
            String hostName = attribute(resourceSpans.getResource().getAttributesList(), "host.name");
            if (hostName == null) {
                hostName = "";
            }
            for (ScopeSpans scopeSpans : resourceSpans.getScopeSpansList()) {
                for (Span span : scopeSpans.getSpansList()) {
                    try {
                        out.add(new ConvertedTrace(serviceKey, buildDcSpan(
                                serviceName,
                                serviceKey,
                                hostName,
                                resourceSpans.getResource().getAttributesList(),
                                span)));
                    } catch (IOException ignored) {
                        // skip malformed span
                    }
                }
            }
        }
        return out;
    }

    /** OTLP ExportMetricsServiceRequest → {@link OtlMetricLine} 列表。 */
    /** OTLP ExportMetricsServiceRequest → {@link OtlMetricLine} 列表。 */
    public List<ConvertedMetric> convertMetrics(ExportMetricsServiceRequest request) {
        List<ConvertedMetric> out = new ArrayList<>();
        int pointCount = 0;
        for (ResourceMetrics resourceMetrics : request.getResourceMetricsList()) {
            String serviceName = attribute(resourceMetrics.getResource().getAttributesList(), "service.name");
            if (serviceName == null || serviceName.isBlank()) {
                continue;
            }
            String serviceKey = ServiceKeyUtil.of(serviceName);
            for (ScopeMetrics scopeMetrics : resourceMetrics.getScopeMetricsList()) {
                for (Metric metric : scopeMetrics.getMetricsList()) {
                    if (metric.hasSum()) {
                        for (NumberDataPoint point : metric.getSum().getDataPointsList()) {
                            pointCount++;
                            logRawNumberPoint(serviceName, "sum", metric.getName(),
                                    resourceMetrics.getResource().getAttributesList(), point);
                            ConvertedMetric converted = new ConvertedMetric(serviceKey,
                                    buildMetricLine(serviceName, serviceKey, resourceMetrics.getResource().getAttributesList(),
                                            metric.getName(), point));
                            out.add(converted);
                            OtlpMetricDebugLogger.convertedLine(converted.line());
                        }
                    } else if (metric.hasGauge()) {
                        for (NumberDataPoint point : metric.getGauge().getDataPointsList()) {
                            pointCount++;
                            logRawNumberPoint(serviceName, "gauge", metric.getName(),
                                    resourceMetrics.getResource().getAttributesList(), point);
                            ConvertedMetric converted = new ConvertedMetric(serviceKey,
                                    buildMetricLine(serviceName, serviceKey, resourceMetrics.getResource().getAttributesList(),
                                            metric.getName(), point));
                            out.add(converted);
                            OtlpMetricDebugLogger.convertedLine(converted.line());
                        }
                    } else if (metric.hasHistogram()) {
                        for (HistogramDataPoint point : metric.getHistogram().getDataPointsList()) {
                            pointCount++;
                            Map<String, String> attrs = buildAttributeMap(
                                    resourceMetrics.getResource().getAttributesList(), point.getAttributesList());
                            OtlpMetricDebugLogger.rawOtlpPoint(
                                    serviceName,
                                    "histogram",
                                    metric.getName(),
                                    attrs,
                                    "sum=" + point.getSum() + ",count=" + point.getCount());
                            appendHistogramMetricLines(out, serviceKey, serviceName,
                                    resourceMetrics.getResource().getAttributesList(), metric.getName(), point);
                        }
                    } else if (metric.hasExponentialHistogram()) {
                        OtlpMetricDebugLogger.unsupportedInstrument(
                                serviceName, metric.getName(), "exponential_histogram not supported");
                    } else if (metric.hasSummary()) {
                        OtlpMetricDebugLogger.unsupportedInstrument(
                                serviceName, metric.getName(), "summary not supported");
                    } else {
                        OtlpMetricDebugLogger.unsupportedInstrument(
                                serviceName, metric.getName(), "unknown instrument type");
                    }
                }
            }
        }
        OtlpMetricDebugLogger.receivedBatch(request.getResourceMetricsCount(), pointCount);
        return out;
    }

    private void logRawNumberPoint(
            String serviceName,
            String instrument,
            String metricName,
            List<KeyValue> resourceAttributes,
            NumberDataPoint point) {
        Map<String, String> attrs = buildAttributeMap(resourceAttributes, point.getAttributesList());
        Object value = point.hasAsDouble() ? point.getAsDouble() : point.getAsInt();
        OtlpMetricDebugLogger.rawOtlpPoint(serviceName, instrument, metricName, attrs, value);
    }

    private OtlMetricLine buildMetricLine(
            String serviceName,
            String serviceKey,
            List<KeyValue> resourceAttributes,
            String metricName,
            NumberDataPoint point) {
        long timeNanos = point.getTimeUnixNano() > 0 ? point.getTimeUnixNano() : System.nanoTime();
        String serviceInstance = firstNonBlank(
                attribute(point.getAttributesList(), "service.instance.id"),
                attribute(resourceAttributes, "service.instance.id"));
        String threadPoolName = attribute(point.getAttributesList(), "thread.pool.name");
        String poolName = attribute(point.getAttributesList(), "pool.name");
        if ((threadPoolName == null || threadPoolName.isBlank())
                && poolName != null
                && metricName.contains("thread")) {
            threadPoolName = poolName;
        }
        return new OtlMetricLine(
                timeNanos / 1_000_000L,
                serviceKey,
                serviceName,
                metricName,
                point.hasAsDouble() ? point.getAsDouble() : point.getAsInt(),
                serviceInstance,
                attribute(resourceAttributes, "host.name"),
                threadPoolName,
                attribute(point.getAttributesList(), "object.pool.name"),
                attribute(point.getAttributesList(), "http.connection.pool.name"),
                attribute(point.getAttributesList(), "db.connection.pool.name"),
                poolName,
                buildAttributeMeta(resourceAttributes, point.getAttributesList()));
    }

    private void appendHistogramMetricLines(
            List<ConvertedMetric> out,
            String serviceKey,
            String serviceName,
            List<KeyValue> resourceAttributes,
            String metricName,
            HistogramDataPoint point) {
        Map<String, String> attributes = buildAttributeMap(resourceAttributes, point.getAttributesList());
        for (JvmOtelMetricNormalizer.NormalizedMetric normalized
                : JvmOtelMetricNormalizer.normalizeHistogram(metricName, attributes, point.getSum(), point.getCount())) {
            long timeNanos = point.getTimeUnixNano() > 0 ? point.getTimeUnixNano() : System.nanoTime();
            String serviceInstance = firstNonBlank(
                    attribute(point.getAttributesList(), "service.instance.id"),
                    attribute(resourceAttributes, "service.instance.id"));
            out.add(new ConvertedMetric(serviceKey, new OtlMetricLine(
                    timeNanos / 1_000_000L,
                    serviceKey,
                    serviceName,
                    normalized.identifier(),
                    normalized.value(),
                    serviceInstance,
                    attribute(resourceAttributes, "host.name"),
                    null,
                    null,
                    null,
                    null,
                    null,
                    buildAttributeMeta(resourceAttributes, point.getAttributesList()))));
            OtlpMetricDebugLogger.convertedLine(out.get(out.size() - 1).line());
        }
    }

    /** OTLP ExportLogsServiceRequest → {@link OtlLogLine} list. */
    public List<ConvertedLog> convertLogs(ExportLogsServiceRequest request) {
        List<ConvertedLog> out = new ArrayList<>();
        for (ResourceLogs resourceLogs : request.getResourceLogsList()) {
            String serviceName = attribute(resourceLogs.getResource().getAttributesList(), "service.name");
            if (serviceName == null || serviceName.isBlank()) {
                continue;
            }
            String serviceKey = ServiceKeyUtil.of(serviceName);
            List<KeyValue> resourceAttributes = resourceLogs.getResource().getAttributesList();
            String serviceInstance = firstNonBlank(
                    attribute(resourceAttributes, "service.instance.id"),
                    attribute(resourceAttributes, "k8s.pod.name"));
            String hostName = attribute(resourceAttributes, "host.name");
            if (hostName == null) {
                hostName = "";
            }
            if (serviceInstance == null) {
                serviceInstance = "";
            }
            String resourceJson = encodeAttributes(resourceAttributes);
            for (ScopeLogs scopeLogs : resourceLogs.getScopeLogsList()) {
                for (LogRecord logRecord : scopeLogs.getLogRecordsList()) {
                    OtlLogLine line = buildLogLine(
                            serviceName, serviceKey, serviceInstance, hostName, resourceJson, logRecord);
                    if (line != null) {
                        out.add(new ConvertedLog(serviceKey, line));
                    }
                }
            }
        }
        return out;
    }

    private OtlLogLine buildLogLine(
            String serviceName,
            String serviceKey,
            String serviceInstance,
            String hostName,
            String resourceJson,
            LogRecord logRecord) {
        long timeNs = logRecord.getTimeUnixNano();
        if (timeNs <= 0) {
            timeNs = logRecord.getObservedTimeUnixNano();
        }
        if (timeNs <= 0) {
            return null;
        }
        String body = logBody(logRecord.getBody());
        String attributesJson = encodeAttributes(logRecord.getAttributesList());
        if ((body == null || body.isBlank()) && (attributesJson == null || attributesJson.isBlank())) {
            return null;
        }
        String severityText = logRecord.getSeverityText();
        int severityNumber = logRecord.getSeverityNumberValue();
        if (severityText == null || severityText.isBlank()) {
            severityText = severityFromNumber(severityNumber);
        }
        return new OtlLogLine(
                timeNs,
                logRecord.getObservedTimeUnixNano(),
                DATETIME.format(Instant.ofEpochSecond(0, timeNs)),
                serviceKey,
                serviceName,
                serviceInstance,
                hostName,
                hex(logRecord.getTraceId()),
                hex(logRecord.getSpanId()),
                severityText,
                severityNumber,
                body == null ? "" : body,
                attributesJson,
                resourceJson);
    }

    private static String severityFromNumber(int severityNumber) {
        if (severityNumber >= 21) {
            return "FATAL";
        }
        if (severityNumber >= 17) {
            return "ERROR";
        }
        if (severityNumber >= 13) {
            return "WARN";
        }
        if (severityNumber >= 9) {
            return "INFO";
        }
        if (severityNumber >= 5) {
            return "DEBUG";
        }
        if (severityNumber >= 1) {
            return "TRACE";
        }
        return "UNSPECIFIED";
    }

    private String encodeAttributes(List<KeyValue> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        Map<String, String> map = new LinkedHashMap<>();
        collectAttributes(map, attributes);
        if (map.isEmpty()) {
            return null;
        }
        try {
            return METRIC_JSON.writeValueAsString(map);
        } catch (IOException e) {
            return null;
        }
    }

    private static String logBody(AnyValue body) {
        if (body == null) {
            return null;
        }
        if (body.hasStringValue()) {
            return body.getStringValue();
        }
        if (body.hasBytesValue()) {
            return body.getBytesValue().toStringUtf8();
        }
        if (body.hasKvlistValue()) {
            Map<String, String> map = new LinkedHashMap<>();
            for (KeyValue kv : body.getKvlistValue().getValuesList()) {
                String value = anyValue(kv.getValue());
                if (value != null && !value.isBlank()) {
                    map.put(kv.getKey(), value);
                }
            }
            try {
                return METRIC_JSON.writeValueAsString(map);
            } catch (IOException e) {
                return map.toString();
            }
        }
        if (body.hasArrayValue()) {
            List<String> values = new ArrayList<>();
            for (AnyValue item : body.getArrayValue().getValuesList()) {
                String value = anyValue(item);
                if (value != null) {
                    values.add(value);
                }
            }
            try {
                return METRIC_JSON.writeValueAsString(values);
            } catch (IOException e) {
                return values.toString();
            }
        }
        return anyValue(body);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private DcSpan buildDcSpan(
            String serviceName,
            String serviceKey,
            String hostName,
            List<KeyValue> resourceAttributes,
            Span span) throws IOException {
        long start = span.getStartTimeUnixNano();
        long end = span.getEndTimeUnixNano();
        long duration = Math.max(0, end - start);
        boolean errored = span.getStatus().getCode() == io.opentelemetry.proto.trace.v1.Status.StatusCode.STATUS_CODE_ERROR;

        // Single merged attribute map: resource attributes first, span attributes overlay.
        // Span-level keys win, which matches the previous spanAttributes + metaAttributes semantics.
        Map<String, String> metaAttributes = new LinkedHashMap<>();
        collectAttributes(metaAttributes, resourceAttributes);
        collectAttributes(metaAttributes, span.getAttributesList());

        DcSpan dc = new DcSpan();
        dc.minutes = toMinutesBucket(start);
        dc.hours = toHoursBucket(start);
        dc.serviceId = serviceKey;
        dc.service = serviceName;
        dc.serviceInstance = metaAttributes.get("service.instance.id");
        String otelName = span.getName();
        dc.resource = otelName;
        dc.name = TraceSpanNames.normalizeOtelName(otelName, metaAttributes);
        dc.trace_id = hex(span.getTraceId());
        dc.span_id = hex(span.getSpanId());
        if (isEmptyParentSpanId(span.getParentSpanId())) {
            dc.parent_id = "";
        } else {
            dc.parent_id = hex(span.getParentSpanId());
        }
        TraceParentUtil.applyIsParent(dc);
        dc.start = start;
        dc.end = end;
        dc.duration = duration;
        dc.startTime = DATETIME.format(Instant.ofEpochSecond(0, start));
        dc.error = errored ? 1 : 0;
        dc.slow = duration > 500_000_000L ? 1 : 0;
        dc.hostName = hostName.isEmpty() ? "unknown" : hostName;
        dc.host_id = dc.hostName;
        dc.type = span.getKind().name();
        dc.isIn = 0;
        dc.isOut = 0;
        dc.metaErrorType = metaAttributes.get("error.type");
        boolean elasticsearchSpan = TraceSpanNames.isElasticsearchMeta(metaAttributes);
        if (!elasticsearchSpan) {
            applyHttpAttributes(dc, metaAttributes);
        }
        // meta is materialized here once via Jackson (native MapSerializer); DCSpanJsonEncoder
        // re-escapes it via Jackson's optimized UTF8 string writer at flush time.
        dc.meta = OtelAttributeMaps.encode(metaAttributes);
        OtelAttributeMaps.cache(dc, metaAttributes);
        dc.metaPeerHostname = firstNonBlank(
                metaAttributes.get("server.address"),
                metaAttributes.get("net.peer.name"));
        return dc;
    }

    private static void applyHttpAttributes(DcSpan dc, Map<String, String> attributes) {
        dc.metaHttpMethod = firstNonBlank(
                attributes.get("http.method"),
                attributes.get("http.request.method"));
        dc.metaHttpStatusCode = firstIntFromMap(
                attributes, "http.status_code", "http.response.status_code");
        String httpUrl = firstNonBlank(
                attributes.get("url.full"),
                attributes.get("http.url"),
                attributes.get("http.route"),
                attributes.get("url.path"));
        if (httpUrl != null && !DcSpanUtil.isRpcProtocolUrl(httpUrl)) {
            dc.metaHttpUrl = DcSpanUtil.normalizeHttpUrl(httpUrl);
        }
    }

    private static Integer firstIntFromMap(Map<String, String> attributes, String... keys) {
        for (String key : keys) {
            String value = attributes.get(key);
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                // try next key
            }
        }
        return null;
    }

    private static String buildAttributeMeta(List<KeyValue> resourceAttributes, List<KeyValue> spanAttributes) {
        return OtelAttributeMaps.encode(buildAttributeMap(resourceAttributes, spanAttributes));
    }

    private static Map<String, String> buildAttributeMap(List<KeyValue> resourceAttributes, List<KeyValue> spanAttributes) {
        Map<String, String> meta = new LinkedHashMap<>();
        collectAttributes(meta, resourceAttributes);
        collectAttributes(meta, spanAttributes);
        return meta;
    }

    private static void collectAttributes(Map<String, String> target, List<KeyValue> attributes) {
        if (attributes == null) {
            return;
        }
        for (KeyValue kv : attributes) {
            String value = anyValue(kv.getValue());
            if (value != null && !value.isBlank()) {
                target.put(kv.getKey(), value.trim());
            }
        }
    }

    private static long toMinutesBucket(long startNanos) {
        long epochSec = startNanos / 1_000_000_000L;
        long minute = epochSec / 60;
        return Long.parseLong(ApmTimeZones.formatBucket(minute * 60L, "yyyyMMddHHmm"));
    }

    private static long toHoursBucket(long startNanos) {
        long epochSec = startNanos / 1_000_000_000L;
        long hour = epochSec / 3600;
        return Long.parseLong(ApmTimeZones.formatBucket(hour * 3600L, "yyyyMMddHH"));
    }

    private static boolean isEmptyParentSpanId(com.google.protobuf.ByteString parentSpanId) {
        if (parentSpanId == null || parentSpanId.isEmpty()) {
            return true;
        }
        for (int i = 0; i < parentSpanId.size(); i++) {
            if (parentSpanId.byteAt(i) != 0) {
                return false;
            }
        }
        return true;
    }

    private static String hex(com.google.protobuf.ByteString bytes) {
        if (bytes == null || bytes.isEmpty()) {
            return "";
        }
        char[] out = new char[bytes.size() * 2];
        for (int i = 0; i < bytes.size(); i++) {
            int value = bytes.byteAt(i) & 0xff;
            out[i * 2] = HEX[value >>> 4];
            out[i * 2 + 1] = HEX[value & 0x0f];
        }
        return new String(out);
    }

    private static String attribute(List<KeyValue> attributes, String key) {
        for (KeyValue kv : attributes) {
            if (key.equals(kv.getKey())) {
                return anyValue(kv.getValue());
            }
        }
        return null;
    }

    private static String anyValue(AnyValue value) {
        if (value.hasStringValue()) {
            return value.getStringValue();
        }
        if (value.hasIntValue()) {
            return Long.toString(value.getIntValue());
        }
        if (value.hasBoolValue()) {
            return Boolean.toString(value.getBoolValue());
        }
        return null;
    }

    public record ConvertedTrace(String serviceKey, DcSpan span) {
    }

    public record ConvertedMetric(String serviceKey, OtlMetricLine line) {
    }

    public record ConvertedLog(String serviceKey, OtlLogLine line) {
    }
}
