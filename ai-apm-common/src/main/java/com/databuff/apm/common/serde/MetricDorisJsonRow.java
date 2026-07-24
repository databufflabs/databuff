package com.databuff.apm.common.serde;

import com.databuff.apm.common.metric.MetricSchemaRegistry;
import com.databuff.apm.common.model.OptimizedMetric;
import com.databuff.apm.common.storage.DorisJsonRow;
import com.databuff.apm.common.storage.MetricRowTimeUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Lazily encodes a metric as one Doris NDJSON line on Stream Load flush.
 * <p>
 * Hold either an {@link OptimizedMetric} (build the row map at encode time) or a
 * pre-built field map (OTLP / JVM merge). Avoids {@code HashMap + byte[]} on the offer path.
 */
public final class MetricDorisJsonRow implements DorisJsonRow {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OptimizedMetric metric;
    private final Map<String, Object> rowMap;

    private MetricDorisJsonRow(OptimizedMetric metric, Map<String, Object> rowMap) {
        this.metric = metric;
        this.rowMap = rowMap;
    }

    public static MetricDorisJsonRow of(OptimizedMetric metric) {
        return new MetricDorisJsonRow(Objects.requireNonNull(metric, "metric"), null);
    }

    public static MetricDorisJsonRow ofMap(Map<String, Object> row) {
        return new MetricDorisJsonRow(null, Objects.requireNonNull(row, "row"));
    }

    @Override
    public void writeTo(OutputStream out) throws IOException {
        Map<String, Object> row = rowMap != null ? rowMap : buildRow(metric);
        try {
            ReusableJson.writeValue(JSON, out, row);
        } catch (JsonProcessingException e) {
            throw new IOException("metric json encode failed", e);
        }
    }

    @Override
    public long estimatedBytes() {
        if (rowMap != null) {
            return estimateMapBytes(rowMap);
        }
        return estimateOptimizedBytes(metric);
    }

    static Map<String, Object> buildRow(OptimizedMetric metric) {
        Map<String, Object> row = new HashMap<>();
        MetricRowTimeUtil.putTsAndMetricTime(row, metric.timestamp() / 1_000_000L);
        MetricSchemaRegistry.applyTagValues(row, metric.measurement(), metric.tagValuesRef());
        MetricSchemaRegistry.applyFieldValues(row, metric.measurement(), metric.fieldValuesRef());
        return row;
    }

    private static long estimateOptimizedBytes(OptimizedMetric metric) {
        long n = 160L;
        n += len(metric.measurement());
        for (String tag : metric.tagValuesRef()) {
            n += len(tag) + 8L;
        }
        n += (long) metric.fieldValuesRef().length * 16L;
        return n + 1L;
    }

    private static long estimateMapBytes(Map<String, Object> row) {
        long n = 64L;
        for (Map.Entry<String, Object> e : row.entrySet()) {
            n += len(e.getKey()) + 8L;
            Object v = e.getValue();
            if (v instanceof String s) {
                n += s.length() + 2L;
            } else if (v instanceof Number) {
                n += 16L;
            } else if (v != null) {
                n += 24L;
            }
        }
        return n + 1L;
    }

    private static long len(String s) {
        return s == null ? 0L : s.length();
    }
}
