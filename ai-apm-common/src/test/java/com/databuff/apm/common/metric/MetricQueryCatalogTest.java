package com.databuff.apm.common.metric;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricQueryCatalogTest {

    @Test
    void expandSkipsDisabledRows() {
        MetricCoreSeedRow disabled = seedRow("svc.off", Map.of("cnt", field("sum", "计数")), false);
        assertThat(MetricQueryCatalog.expand(List.of(disabled))).isEmpty();
    }

    @Test
    void expandBaseFieldsAndDerivedMetrics() {
        Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
        fields.put("cnt", field("sum", "请求数"));
        fields.put("error", field("sum", "错误数"));
        fields.put("slow", field("sum", "慢请求"));
        fields.put("sumDuration", field("sum", "总耗时"));
        fields.put("maxDuration", fieldWithValue("gauge", "最大耗时", Map.of("min", 0)));

        MetricCoreSeedRow row = seedRow("svc.req", fields, true);
        Map<String, MetricQueryDefinition> expanded = MetricQueryCatalog.expand(List.of(row));

        assertThat(expanded).containsKeys(
                "svc.req.cnt",
                "svc.req.error",
                "svc.req.slow",
                "svc.req.sumDuration",
                "svc.req.maxDuration",
                "svc.req.avgDuration",
                "svc.req.error.pct",
                "svc.req.success.pct",
                "svc.req.slow.pct");

        MetricQueryDefinition cnt = expanded.get("svc.req.cnt");
        assertThat(cnt.getFormula()).isEqualTo("sum(\"cnt\")");
        assertThat(cnt.getAggregatorType()).isEqualTo("sum");
        assertThat(cnt.getDorisTable()).isEqualTo("metric_svc");
        assertThat(cnt.getBuiltin()).isTrue();

        MetricQueryDefinition avg = expanded.get("svc.req.avgDuration");
        assertThat(avg.getField()).isNull();
        assertThat(avg.getFormula()).isEqualTo("sum(sumDuration)/sum(cnt)");
        assertThat(avg.getAggregatorType()).isEqualTo("formula");
        assertThat(avg.getMetricCn()).isEqualTo("平均耗时");

        MetricQueryDefinition errorPct = expanded.get("svc.req.error.pct");
        assertThat(errorPct.getUnit()).isEqualTo("percent");
        assertThat(errorPct.getFormula()).isEqualTo("(sum(error)/sum(cnt)) * 100");
    }

    @Test
    void expandUsesAvgFormulaForGaugeAggregator() {
        Map<String, Map<String, Object>> fields = Map.of(
                "latency", Map.of(
                        "aggregatorType", "gauge",
                        "describe", "",
                        "unit", "",
                        "unit_cn", "",
                        "metric_cn", ""));
        MetricCoreSeedRow row = seedRow("svc.lat", fields, true);

        MetricQueryDefinition def = MetricQueryCatalog.expand(List.of(row)).get("svc.lat.latency");
        assertThat(def.getFormula()).isEqualTo("avg(\"latency\")");
        assertThat(def.getUnit()).isEqualTo("count");
        assertThat(def.getMetricCn()).isEqualTo("svc.lat.latency");
    }

    @Test
    void expandMergesMultipleRowsInSortedOrder() {
        MetricCoreSeedRow a = seedRow("aaa", Map.of("cnt", field("sum", "a")), true);
        MetricCoreSeedRow z = seedRow("zzz", Map.of("cnt", field("sum", "z")), true);
        Map<String, MetricQueryDefinition> expanded = MetricQueryCatalog.expand(List.of(z, a));
        assertThat(expanded.keySet()).containsExactly("aaa.cnt", "zzz.cnt");
    }

    private static MetricCoreSeedRow seedRow(
            String measurement,
            Map<String, Map<String, Object>> fields,
            boolean enabled) {
        return new MetricCoreSeedRow(
                42L,
                "type1",
                "type2",
                "type3",
                "demo",
                "databuff",
                measurement,
                "metric_svc",
                "seed desc",
                Map.of("env", "env"),
                Map.of("env", "prod"),
                fields,
                enabled,
                true);
    }

    private static Map<String, Object> field(String aggregator, String metricCn) {
        return Map.of(
                "aggregatorType", aggregator,
                "describe", metricCn,
                "unit", "count",
                "unit_cn", "个",
                "metric_cn", metricCn);
    }

    private static Map<String, Object> fieldWithValue(
            String aggregator, String metricCn, Map<String, Object> fieldValue) {
        Map<String, Object> map = new LinkedHashMap<>(field(aggregator, metricCn));
        map.put("fieldValue", fieldValue);
        return map;
    }
}
