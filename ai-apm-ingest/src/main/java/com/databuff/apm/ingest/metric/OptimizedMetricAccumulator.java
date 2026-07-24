package com.databuff.apm.ingest.metric;

import com.databuff.apm.common.model.OptimizedMetric;
import com.databuff.apm.common.serde.MetricDorisJsonRow;
import com.databuff.apm.common.serde.OptimizedMetricUtil;
import com.databuff.apm.common.storage.DorisJsonRow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Merges optimized metrics; Doris encoding is deferred via {@link MetricDorisJsonRow}. */
public final class OptimizedMetricAccumulator {

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
        List<OptimizedMetric> metrics = new ArrayList<>(byTsId.values());
        byTsId.clear();
        return metrics;
    }

    /** Test helper: materialize NDJSON bytes (production offer path stays lazy). */
    public List<byte[]> drainRows() {
        List<byte[]> rows = new ArrayList<>(byTsId.size());
        for (OptimizedMetric metric : byTsId.values()) {
            rows.add(DorisJsonRow.toByteArray(toDorisJsonRow(metric)));
        }
        byTsId.clear();
        return rows;
    }

    public int size() {
        return byTsId.size();
    }

    static DorisJsonRow toDorisJsonRow(OptimizedMetric metric) {
        return MetricDorisJsonRow.of(metric);
    }
}
