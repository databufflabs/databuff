package com.databuff.apm.ingest.metric;

import com.databuff.apm.common.metric.MetricSchemaRegistry;
import com.databuff.apm.common.meta.OtelAttributeMaps;
import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.serde.DcSpanUtil;
import com.databuff.apm.common.serde.MetricDorisJsonRow;
import com.databuff.apm.ingest.otel.OtlMetricLine;
import com.databuff.apm.common.storage.DorisJsonRow;
import com.databuff.apm.common.storage.MetricIdentifierParser;
import com.databuff.apm.common.storage.MetricRowTimeUtil;
import com.databuff.apm.ingest.otel.OtlpMetricDebugLogger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Maps raw OTLP metric JSON lines to Doris table rows for pool/JVM metrics. */
public final class OtlpMetricRowMapper {

    private static final ObjectMapper JSON = new ObjectMapper();

    private OtlpMetricRowMapper() {
    }

    /**
     * Lazy row: field map is kept until Stream Load flush ({@link MetricDorisJsonRow}).
     * JVM merge path reads {@link #fields()} before offer.
     */
    public record MappedRow(String table, Map<String, Object> fields) {
        public MappedRow {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(fields, "fields");
        }

        public DorisJsonRow toDorisJsonRow() {
            return MetricDorisJsonRow.ofMap(fields);
        }

        /** Materialize for tests / debug logging. */
        public byte[] rowBytes() {
            return DorisJsonRow.toByteArray(toDorisJsonRow());
        }
    }

    public static Optional<MappedRow> map(OtlMetricLine line) {
            if (line == null || line.metric() == null || line.metric().isBlank()) {
            OtlpMetricDebugLogger.mapSkipped(line, "blank metric");
            return Optional.empty();
        }
        try {
            String normalized = normalizeMetricName(line.metric(), OtelAttributeMaps.parse(line.resourceMeta()));
            MetricIdentifierParser.ParsedMetric parsed;
            try {
                parsed = MetricIdentifierParser.parse(normalized);
            } catch (IllegalArgumentException ex) {
                OtlpMetricDebugLogger.mapSkipped(line, "unsupported identifier: " + normalized);
                return Optional.empty();
            }
            if (!MetricSchemaRegistry.isOtlpMeasurement(parsed.measurement())) {
                OtlpMetricDebugLogger.mapSkipped(line, "not otlp measurement: " + parsed.measurement());
                return Optional.empty();
            }
            String table = MetricIdentifierParser.dorisTableName(parsed.measurement());
            Map<String, Object> row = new HashMap<>();
            MetricRowTimeUtil.putTsAndMetricTime(row, line.tsMillis());
            row.put("service", line.service());
            row.put("service_id", line.serviceId());
            putIfPresent(row, "service_instance", line.serviceInstance());
            applyMeasurementTagsFromLine(row, parsed.measurement(), line);
            putFieldValue(row, parsed, line.value());
            MappedRow mapped = new MappedRow(table, row);
            OtlpMetricDebugLogger.mappedRow(line, mapped);
            return Optional.of(mapped);
        } catch (Exception ex) {
            OtlpMetricDebugLogger.mapSkipped(line, "exception: " + ex.getMessage());
            return Optional.empty();
        }
    }

    public static Optional<MappedRow> map(byte[] rawJson) {
        if (rawJson == null || rawJson.length == 0) {
            return Optional.empty();
        }
        try {
            JsonNode node = JSON.readTree(rawJson);
            String metricName = text(node, "metric");
            if (metricName == null || metricName.isBlank()) {
                return Optional.empty();
            }
            String normalized = normalizeMetricName(metricName, readPointAttributes(node));
            MetricIdentifierParser.ParsedMetric parsed;
            try {
                parsed = MetricIdentifierParser.parse(normalized);
            } catch (IllegalArgumentException ex) {
                return Optional.empty();
            }
            if (!MetricSchemaRegistry.isOtlpMeasurement(parsed.measurement())) {
                return Optional.empty();
            }
            String table = MetricIdentifierParser.dorisTableName(parsed.measurement());
            Map<String, Object> row = new HashMap<>();
            MetricRowTimeUtil.putTsAndMetricTime(row, readTsMillis(node));
            row.put("service", text(node, "service"));
            row.put("service_id", text(node, "service_id"));
            putIfPresent(row, "service_instance", text(node, "service_instance"));
            applyMeasurementTags(row, parsed.measurement(), node);
            putFieldValue(row, parsed, numericValue(node));
            return Optional.of(new MappedRow(table, row));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    static String normalizeMetricName(String metricName) {
        return normalizeMetricName(metricName, Map.of());
    }

    static String normalizeMetricName(String metricName, Map<String, String> attributes) {
        return JvmOtelMetricNormalizer.normalizeIdentifier(metricName, attributes).orElse(metricName);
    }

    private static Map<String, String> readPointAttributes(JsonNode node) {
        JsonNode attributes = node.get("attributes");
        if (attributes == null || !attributes.isObject()) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        attributes.fields().forEachRemaining(entry -> out.put(entry.getKey(), entry.getValue().asText()));
        return out;
    }

    private static void putFieldValue(
            Map<String, Object> row,
            MetricIdentifierParser.ParsedMetric parsed,
            Number value) {
        String column = MetricIdentifierParser.toDorisFieldColumn(parsed);
        if (parsed.measurement().startsWith("jvm")) {
            row.put(MetricSchemaRegistry.streamLoadJsonKey(column), value);
            return;
        }
        row.put(column, value);
    }

    private static boolean isExtendedMeasurement(String measurement) {
        return MetricSchemaRegistry.isOtlpMeasurement(measurement);
    }

    private static void applyMeasurementTagsFromLine(Map<String, Object> row, String measurement, OtlMetricLine line) {
        if ("service.instance".equals(measurement)) {
            DcSpan span = new DcSpan();
            span.service = line.service();
            span.serviceId = line.serviceId();
            span.serviceInstance = line.serviceInstance();
            span.hostName = line.tagHost();
            span.meta = line.resourceMeta();
            Map<String, String> tags = DcSpanUtil.serviceInstanceTags(
                    span,
                    firstNonBlank(line.serviceInstance(), line.tagHost()),
                    OtelAttributeMaps.parse(line.resourceMeta()));
            MetricSchemaRegistry.applyTagValues(row, measurement,
                    MetricSchemaRegistry.tagValuesFromMap(measurement, tags));
            return;
        }
        if (measurement.startsWith("jvm")) {
            putIfPresent(row, "instance", firstNonBlank(line.serviceInstance()));
            putIfPresent(row, "tag_host", line.tagHost());
            return;
        }
        String poolTag = switch (measurement) {
            case "service.thread.pool" -> "threadPoolName";
            case "service.object.pool", "service.object.pool.get" -> "objectPoolName";
            case "service.http.connection.pool", "service.http.connection.pool.get" -> "httpConnectionPoolName";
            case "service.db.connection.pool", "service.db.connection.pool.get" -> "connectionPoolName";
            default -> null;
        };
        if (poolTag != null) {
            String poolValue = switch (poolTag) {
                case "threadPoolName" -> line.threadPoolName();
                case "objectPoolName" -> line.objectPoolName();
                case "httpConnectionPoolName" -> line.httpConnectionPoolName();
                case "connectionPoolName" -> line.connectionPoolName();
                default -> null;
            };
            putIfPresent(row, poolTag, poolValue);
        }
    }

    private static void applyMeasurementTags(Map<String, Object> row, String measurement, JsonNode node) {
        if (measurement.startsWith("jvm")) {
            putIfPresent(row, "instance", firstNonBlank(text(node, "instance"), text(node, "service_instance")));
            putIfPresent(row, "tag_host", firstNonBlank(text(node, "tag_host"), text(node, "host_name")));
            return;
        }
        String poolTag = switch (measurement) {
            case "service.thread.pool" -> "threadPoolName";
            case "service.object.pool", "service.object.pool.get" -> "objectPoolName";
            case "service.http.connection.pool", "service.http.connection.pool.get" -> "httpConnectionPoolName";
            case "service.db.connection.pool", "service.db.connection.pool.get" -> "connectionPoolName";
            default -> null;
        };
        if (poolTag != null) {
            putIfPresent(row, poolTag, text(node, poolTag));
        }
        if ("service.exception".equals(measurement)) {
            putIfPresent(row, "exceptionName", firstNonBlank(text(node, "exceptionName"), text(node, "errorType")));
        }
    }

    private static void putIfPresent(Map<String, Object> row, String key, String value) {
        if (value != null && !value.isBlank()) {
            row.put(key, value);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static long readTsMillis(JsonNode node) {
        JsonNode ts = node.get("ts");
        if (ts == null || ts.isNull()) {
            return System.currentTimeMillis();
        }
        if (ts.isIntegralNumber()) {
            return ts.longValue();
        }
        if (ts.isFloatingPointNumber()) {
            return ts.longValue();
        }
        String text = ts.asText();
        if (text == null || text.isBlank()) {
            return System.currentTimeMillis();
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return System.currentTimeMillis();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private static Number numericValue(JsonNode node) {
        JsonNode value = node.get("value");
        if (value == null || value.isNull()) {
            return 0;
        }
        if (value.isIntegralNumber()) {
            return value.longValue();
        }
        return value.doubleValue();
    }
}
