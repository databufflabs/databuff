package com.databuff.apm.ingest.metric;

import com.databuff.apm.common.serde.MetricDorisJsonRow;
import com.databuff.apm.common.storage.DorisTableNames;
import com.databuff.apm.ingest.meta.MetaServiceCollector;
import com.databuff.apm.ingest.otel.OtlMetricLine;
import com.databuff.apm.ingest.otel.OtlpMetricDebugLogger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Maps OTLP metric lines and writes them directly to Doris batch writers.
 * JVM rows from the same export request are merged by aggregate key so partial
 * Stream Load rows do not null out other {@code metric_jvm} columns.
 */
public final class OtlpMetricDirectWriter {

    private static final List<String> JVM_KEY_COLUMNS = List.of(
            "metric_time", "ts", "instance", "service", "service_id", "service_instance", "tag_host");

    private final MetricWriteRouter metricWriteRouter;
    private final MetaServiceCollector metaServiceCollector;

    public OtlpMetricDirectWriter(MetricWriteRouter metricWriteRouter, MetaServiceCollector metaServiceCollector) {
        this.metricWriteRouter = metricWriteRouter;
        this.metaServiceCollector = metaServiceCollector;
    }

    public OtlpMetricDirectWriter(MetricWriteRouter metricWriteRouter) {
        this(metricWriteRouter, null);
    }

    public void write(List<OtlMetricLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        Map<String, Map<String, Object>> jvmRows = new LinkedHashMap<>();
        Map<String, Integer> jvmPartialCounts = new LinkedHashMap<>();
        int skippedMap = 0;
        for (OtlMetricLine line : lines) {
            if (metaServiceCollector != null) {
                metaServiceCollector.remember(line);
            }
            Optional<OtlpMetricRowMapper.MappedRow> mapped = OtlpMetricRowMapper.map(line);
            if (mapped.isEmpty()) {
                skippedMap++;
                OtlpMetricDebugLogger.mapSkipped(line, "no mapped row");
                continue;
            }
            OtlpMetricRowMapper.MappedRow row = mapped.get();
            if (DorisTableNames.METRIC_JVM.equals(row.table())) {
                Map<String, Object> fields = row.fields();
                if (fields == null) {
                    skippedMap++;
                    continue;
                }
                mergeJvmRow(jvmRows, jvmPartialCounts, fields);
                continue;
            }
            metricWriteRouter.offerMappedRow(row);
        }
        String sampleService = lines.stream()
                .map(OtlMetricLine::service)
                .filter(service -> service != null && !service.isBlank())
                .findFirst()
                .orElse("");
        int jvmLines = (int) lines.stream()
                .filter(line -> line.metric() != null && line.metric().startsWith("jvm."))
                .count();
        OtlpMetricDebugLogger.ingestBatch(sampleService, lines.size(), jvmLines, skippedMap);
        for (Map.Entry<String, Map<String, Object>> entry : jvmRows.entrySet()) {
            Map<String, Object> row = entry.getValue();
            Set<String> metricFields = metricFieldNames(row);
            OtlpMetricDebugLogger.mergedJvmRow(
                    String.valueOf(row.getOrDefault("service", "")),
                    String.valueOf(row.getOrDefault("service_id", "")),
                    jvmPartialCounts.getOrDefault(entry.getKey(), 0),
                    metricFields);
            metricWriteRouter.offerJvmRow(MetricDorisJsonRow.ofMap(row));
        }
    }

    private static void mergeJvmRow(
            Map<String, Map<String, Object>> pending,
            Map<String, Integer> partialCounts,
            Map<String, Object> fields) {
        String key = aggregateKey(fields);
        partialCounts.merge(key, 1, Integer::sum);
        Map<String, Object> merged = pending.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            Object value = entry.getValue();
            if (value != null) {
                merged.put(entry.getKey(), value);
            }
        }
    }

    private static Set<String> metricFieldNames(Map<String, Object> row) {
        Set<String> fields = new TreeSet<>();
        for (String key : row.keySet()) {
            if (!JVM_KEY_COLUMNS.contains(key)) {
                fields.add(key);
            }
        }
        return fields;
    }

    private static String aggregateKey(Map<String, Object> row) {
        StringBuilder key = new StringBuilder();
        for (String column : JVM_KEY_COLUMNS) {
            if (!key.isEmpty()) {
                key.append('\u0001');
            }
            Object value = row.get(column);
            key.append(column).append('=').append(value == null ? "" : value);
        }
        return key.toString();
    }
}
