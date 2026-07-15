package com.databuff.apm.ingest.metric;

import com.databuff.apm.common.metric.MetricSchemaRegistry;
import com.databuff.apm.common.model.OptimizedMetric;
import com.databuff.apm.common.serde.OptimizedMetricUtil;
import com.databuff.apm.common.serde.ReusableJson;
import com.databuff.apm.common.storage.MetricRowTimeUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Merges optimized metrics and encodes Doris JSON rows via {@link MetricSchemaRegistry}. */
public final class OptimizedMetricAccumulator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<Integer, OptimizedMetric> byTsId = new LinkedHashMap<>();

    public void merge(OptimizedMetric incoming) {
        byTsId.merge(incoming.tsId(), incoming, OptimizedMetric::merge);
    }

    public void merge(byte[] serialized) {
        if (serialized.length == 0) {
            return;
        }
        OptimizedMetric incoming = OptimizedMetricUtil.deserialize(serialized);
        byTsId.merge(incoming.tsId(), incoming, OptimizedMetric::merge);
    }

    public void mergeAll(List<OptimizedMetric> metrics) {
        for (OptimizedMetric metric : metrics) {
            merge(metric);
        }
    }

    public List<OptimizedMetric> drainMetrics() {
        List<OptimizedMetric> metrics = new java.util.ArrayList<>(byTsId.values());
        byTsId.clear();
        return metrics;
    }

    public List<byte[]> drainRows() throws JsonProcessingException {
        List<byte[]> rows = new java.util.ArrayList<>(byTsId.size());
        for (OptimizedMetric metric : byTsId.values()) {
            rows.add(toDorisRow(metric));
        }
        byTsId.clear();
        return rows;
    }

    public int size() {
        return byTsId.size();
    }

    static byte[] toDorisRow(OptimizedMetric metric) throws JsonProcessingException {
        Map<String, Object> row = new HashMap<>();
        MetricRowTimeUtil.putTsAndMetricTime(row, metric.timestamp() / 1_000_000L);
        MetricSchemaRegistry.applyTagValues(row, metric.measurement(), metric.tagValuesRef());
        MetricSchemaRegistry.applyFieldValues(row, metric.measurement(), metric.fieldValuesRef());
        return ReusableJson.writeValueAsBytes(JSON, row);
    }
}
