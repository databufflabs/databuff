package com.databuff.apm.common.metric;

import com.databuff.apm.common.storage.DorisVarcharLimits;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Ingest-side measurement schemas (see {@code metric-catalog.json}): tag/field column names
 * and order for Doris row mapping and metric identifier parsing. Doris DDL and portal metadata
 * live in {@code deploy/common/sql/databuff.sql}.
 */
public final class MetricSchemaRegistry {

    public record MetricTableSchema(String measurement, List<String> tagColumns, List<String> fields) {
        public String tableName() {
            return MetricSchemaRegistry.tableName(measurement);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, MetricTableSchema> BY_MEASUREMENT = loadCatalog();
    private static final List<String> MEASUREMENTS_SORTED = BY_MEASUREMENT.keySet().stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();
    private static final List<String> ALL_TABLES = BY_MEASUREMENT.values().stream()
            .map(MetricTableSchema::tableName)
            .distinct()
            .sorted()
            .toList();

    private static final List<String> TRACE_DERIVED = List.of(
            "service", "service.trace", "service.instance", "service.http", "service.rpc", "service.db",
            "service.redis", "service.mq", "service.config", "service.remote",
            "service.flow", "service.exception");

    private MetricSchemaRegistry() {
    }

    public static String metricTable(String measurement) {
        return "metric_" + measurement.replace('.', '_');
    }

    public static List<String> measurementsSortedLongestFirst() {
        return MEASUREMENTS_SORTED;
    }

    public static List<String> allTableNames() {
        return ALL_TABLES;
    }

    public static List<String> allMeasurements() {
        return BY_MEASUREMENT.keySet().stream().sorted().toList();
    }

    public static Optional<MetricTableSchema> schema(String measurement) {
        return Optional.ofNullable(BY_MEASUREMENT.get(measurement));
    }

    public static String tableName(String measurement) {
        if (measurement != null && measurement.startsWith("jvm")) {
            return "metric_jvm";
        }
        return metricTable(measurement);
    }

    public static boolean isKnownMeasurement(String measurement) {
        return BY_MEASUREMENT.containsKey(measurement);
    }

    public static boolean isTraceDerived(String measurement) {
        return TRACE_DERIVED.contains(measurement);
    }

    public static boolean isOtlpMeasurement(String measurement) {
        if (measurement == null || measurement.isBlank()) {
            return false;
        }
        if (isTraceDerived(measurement)) {
            return false;
        }
        return measurement.startsWith("jvm")
                || measurement.startsWith("service.thread.pool")
                || measurement.startsWith("service.object.pool")
                || measurement.startsWith("service.http.connection.pool")
                || measurement.startsWith("service.db.connection.pool")
                || "service.cpu".equals(measurement)
                || "service.mem".equals(measurement)
                || "service.io".equals(measurement)
                || "service.net".equals(measurement)
                || "service.tcp".equals(measurement)
                || "service.instance".equals(measurement)
                || "service.health_status".equals(measurement)
                || "service.thread.pool.cost".equals(measurement);
    }

    public static String toColumnName(String tag) {
        if ("serviceId".equals(tag)) {
            return "service_id";
        }
        if ("serviceInstance".equals(tag)) {
            return "service_instance";
        }
        return tag;
    }

    public static String toFieldColumnName(String field) {
        return field;
    }

    public static String streamLoadJsonKey(String dorisColumn) {
        if (dorisColumn == null) {
            return null;
        }
        return dorisColumn.replace('.', '_');
    }

    private static boolean isExcludedField(String field) {
        return field != null && (field.startsWith("upper(") || field.startsWith("upper_"));
    }

    public static void applyTagValues(Map<String, Object> row, String measurement, String[] tagValues) {
        MetricTableSchema schema = BY_MEASUREMENT.get(measurement);
        if (schema == null) {
            for (int i = 0; i < tagValues.length; i++) {
                row.put("tag" + i, tagAt(tagValues, i));
            }
            return;
        }
        List<String> columns = schema.tagColumns();
        for (int i = 0; i < columns.size(); i++) {
            String column = columns.get(i);
            row.put(column, DorisVarcharLimits.truncateMetricTag(column, tagAt(tagValues, i)));
        }
    }

    public static void applyFieldValues(Map<String, Object> row, String measurement, long[] fieldValues) {
        MetricTableSchema schema = BY_MEASUREMENT.get(measurement);
        if (schema == null || schema.fields().isEmpty()) {
            for (int i = 0; i < fieldValues.length; i++) {
                row.put("field" + i, fieldValues[i]);
            }
            return;
        }
        if (isLegacyTraceFieldShape(measurement, fieldValues)) {
            applyLegacyTraceFields(row, measurement, fieldValues);
            return;
        }
        List<String> fields = schema.fields();
        for (int i = 0; i < fields.size() && i < fieldValues.length; i++) {
            row.put(fields.get(i), fieldValues[i]);
        }
    }

    public static String[] tagValuesFromMap(String measurement, Map<String, String> values) {
        MetricTableSchema schema = BY_MEASUREMENT.get(measurement);
        if (schema == null) {
            return new String[0];
        }
        return schema.tagColumns().stream()
                .map(column -> values.getOrDefault(column, ""))
                .toArray(String[]::new);
    }

    private static boolean isLegacyTraceFieldShape(String measurement, long[] fieldValues) {
        if (fieldValues.length > 4) {
            return false;
        }
        if ("service.instance".equals(measurement) || "service.health_status".equals(measurement)) {
            return false;
        }
        return isTraceDerived(measurement)
                || measurement.startsWith("service.http")
                || measurement.startsWith("service.rpc")
                || measurement.startsWith("service.db")
                || measurement.startsWith("service.redis")
                || measurement.startsWith("service.mq")
                || measurement.startsWith("service.config")
                || "service.flow".equals(measurement);
    }

    private static void applyLegacyTraceFields(Map<String, Object> row, String measurement, long[] fieldValues) {
        MetricTableSchema schema = BY_MEASUREMENT.get(measurement);
        if (schema == null) {
            return;
        }
        Map<String, Number> named = new LinkedHashMap<>();
        for (String field : schema.fields()) {
            named.put(field, 0);
        }
        if ("service.exception".equals(measurement)) {
            if (fieldValues.length > 0) {
                named.put("cnt", fieldValues[0]);
            }
        } else {
            if (fieldValues.length > 0) {
                named.put("cnt", fieldValues[0]);
            }
            if (fieldValues.length > 1) {
                named.put("error", fieldValues[1]);
            }
            if (fieldValues.length > 2) {
                named.put("sumDuration", fieldValues[2]);
            }
            if (fieldValues.length > 3 && fieldValues[3] > 0) {
                named.put("maxDuration", fieldValues[3]);
            } else if (fieldValues.length > 0 && fieldValues[0] == 1 && fieldValues.length > 2) {
                named.put("maxDuration", fieldValues[2]);
            }
            if (fieldValues.length > 1 && named.containsKey("slow")) {
                named.put("slow", fieldValues[1]);
            }
        }
        named.forEach(row::put);
    }

    private static String tagAt(String[] tags, int index) {
        return index < tags.length ? tags[index] : "";
    }

    private static Map<String, MetricTableSchema> loadCatalog() {
        Map<String, MetricTableSchema> result = new LinkedHashMap<>();
        try (InputStream in = MetricSchemaRegistry.class.getResourceAsStream("/metric-catalog.json")) {
            if (in == null) {
                throw new IllegalStateException("metric-catalog.json not found on classpath");
            }
            JsonNode root = JSON.readTree(in);
            JsonNode measurements = root.get("measurements");
            measurements.fields().forEachRemaining(entry -> {
                JsonNode node = entry.getValue();
                if (node.has("include") && !node.get("include").asBoolean()) {
                    return;
                }
                result.put(entry.getKey(), parseSchema(entry.getKey(), node));
            });
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
        addPoolGetSchema(result, "service.http.connection.pool.get",
                List.of("httpConnectionPoolName", "service", "service_id", "service_instance"),
                List.of("waitTime", "count"));
        addPoolSchema(result, "service.http.connection.pool",
                List.of("httpConnectionPoolName", "service", "service_id", "service_instance"),
                List.of("activeSize", "idleSize", "maxSize", "waiterNum"));
        addPoolGetSchema(result, "service.db.connection.pool.get",
                List.of("connectionPoolName", "service", "service_id", "service_instance"),
                List.of("waitTime", "count"));
        addPoolGetSchema(result, "service.object.pool.get",
                List.of("objectPoolName", "service", "service_id", "service_instance"),
                List.of("waitTime", "count"));
        return Map.copyOf(result);
    }

    private static void addPoolGetSchema(
            Map<String, MetricTableSchema> result,
            String measurement,
            List<String> tags,
            List<String> fields) {
        result.put(measurement, new MetricTableSchema(measurement, tags, fields));
    }

    private static void addPoolSchema(
            Map<String, MetricTableSchema> result,
            String measurement,
            List<String> tags,
            List<String> fields) {
        result.put(measurement, new MetricTableSchema(measurement, tags, fields));
    }

    private static MetricTableSchema parseSchema(String measurement, JsonNode node) {
        List<String> tags = new ArrayList<>();
        JsonNode tagNode = node.has("tags_open_source") ? node.get("tags_open_source") : node.get("tags");
        if (tagNode != null && tagNode.isArray()) {
            tagNode.forEach(t -> tags.add(toColumnName(t.asText())));
        }
        List<String> fields = new ArrayList<>();
        JsonNode fieldNode = node.get("fields");
        if (fieldNode != null && fieldNode.isArray()) {
            fieldNode.forEach(f -> {
                String name = f.asText();
                if (!isExcludedField(name)) {
                    fields.add(name);
                }
            });
        } else if (fieldNode != null && fieldNode.isObject()) {
            fieldNode.fields().forEachRemaining(f -> {
                String name = f.getKey();
                if (!isExcludedField(name)) {
                    fields.add(name);
                }
            });
        }
        return new MetricTableSchema(measurement, List.copyOf(tags), List.copyOf(fields));
    }
}
