package com.databuff.apm.web.metric;

import com.databuff.apm.common.query.ApmQueryModels;
import com.databuff.apm.web.TestStorageSupport;

import com.databuff.apm.common.query.ApmQueryModels.HttpEndpointPoint;
import com.databuff.apm.common.query.ApmQueryModels.MetricSeriesPoint;

import com.databuff.apm.common.query.ApmQueryModels.ServiceMetricPoint;

import com.databuff.apm.common.storage.ApmReadRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetricQueryServiceTest {

    @Test
    void returnsServiceSeriesFromDoris() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryServiceMetrics(anyString())).thenReturn(List.of(
                new ServiceMetricPoint("2026-06-01 12:00:00", "demo-order", 100, 2, 12.5)));

        MetricQueryService service = new MetricQueryService(reader, TestStorageSupport.storage());
        assertThat(service.serviceSeries(new MetricQueryService.ServiceSeriesRequest("demo-order", 0, 1000)))
                .hasSize(1);
    }

    @Test
    void returnsEmptyWhenDorisFails() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryServiceMetrics(anyString())).thenThrow(new RuntimeException("down"));
        MetricQueryService service = new MetricQueryService(reader, TestStorageSupport.storage());
        assertThat(service.serviceSeries(new MetricQueryService.ServiceSeriesRequest(null, 0, 1000))).isEmpty();
    }

    @Test
    void returnsHttpEndpointsFromDoris() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryHttpEndpoints(anyString())).thenReturn(List.of(
                new HttpEndpointPoint("demo-order-id", "demo-order", "/orders", "GET", "200", 50, 2, 12.0, 24_000_000.0)));
        MetricQueryService service = new MetricQueryService(reader, TestStorageSupport.storage());
        assertThat(service.httpEndpoints(new MetricQueryService.HttpQueryRequest("demo-order", 0, 1000, 50)))
                .hasSize(1);
    }

    @Test
    void returnsHttpLatencyFromDoris() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryHttpLatencyBuckets(anyString())).thenReturn(List.of(
                new ApmQueryModels.HttpLatencyBucketPoint("0-50ms", 10, 0)));
        MetricQueryService service = new MetricQueryService(reader, TestStorageSupport.storage());
        assertThat(service.httpLatencyBuckets(new MetricQueryService.HttpQueryRequest("demo-order", 0, 1000, 50)))
                .hasSize(1);
    }

    @Test
    void returnsLastTagsFromDoris() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryDistinctTags(anyString())).thenReturn(List.of("pool-a", "pool-b"));
        MetricQueryService service = new MetricQueryService(reader, TestStorageSupport.storage());
        assertThat(service.lastTags(new MetricQueryService.LastTagsRequest(
                0, 1000,
                List.of("service.thread.pool.poolSize"),
                List.of("threadPoolName"),
                List.of())))
                .containsEntry("threadPoolName", List.of("pool-a", "pool-b"));
    }

    @Test
    void returnsMetricChartSeries() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryMetricSeries(anyString())).thenReturn(List.of(
                new MetricSeriesPoint(1_710_000_000L, 25.0)));
        MetricQueryService service = new MetricQueryService(reader, TestStorageSupport.storage());
        List<java.util.Map<String, Object>> chart = service.metricChart(java.util.Map.of(
                "start", 1_710_000_000L,
                "end", 1_710_003_600L,
                "query", java.util.Map.of("A", java.util.Map.of("metric", "jvm.thread_count"))));
        assertThat(chart).hasSize(1);
        @SuppressWarnings("unchecked")
        List<List<Number>> values = (List<List<Number>>) chart.get(0).get("values");
        assertThat(values).hasSize(60);
        assertThat(values.get(0)).isEqualTo(List.of(1_710_000_000_000L, 25.0));
        assertThat(values.get(1)).containsExactly(1_710_000_060_000L, null);
    }

    @Test
    void returnsMetricChartTopGroups() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryTopGroups(anyString())).thenReturn(List.of("inst-1"));
        when(reader.queryMetricSeries(anyString())).thenReturn(List.of(
                new MetricSeriesPoint(1_710_000_000L, 10.0)));
        MetricQueryService service = new MetricQueryService(reader, TestStorageSupport.storage());
        List<java.util.Map<String, Object>> chart = service.metricChart(java.util.Map.of(
                "start", 1_710_000_000L,
                "end", 1_710_003_600L,
                "query", java.util.Map.of("A", java.util.Map.of(
                        "metric", "jvm.thread_count",
                        "aggs", "mean",
                        "by", List.of("serviceInstance"),
                        "order", java.util.Map.of("limit", 5)))));
        assertThat(chart).hasSize(1);
        assertThat(chart.get(0).get("tags")).isEqualTo(java.util.Map.of("serviceInstance", "inst-1"));
        verify(reader).queryTopGroups(org.mockito.ArgumentMatchers.argThat(sql ->
                sql.contains("AVG(`thread_count`)")));
    }

    @Test
    void returnsMetricChartWithMeanAggregationAtServiceLevel() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryMetricSeries(anyString())).thenReturn(List.of(
                new MetricSeriesPoint(1_710_000_000L, 42.0)));
        MetricQueryService service = new MetricQueryService(reader, TestStorageSupport.storage());
        List<java.util.Map<String, Object>> chart = service.metricChart(java.util.Map.of(
                "start", 1_710_000_000L,
                "end", 1_710_003_600L,
                "query", java.util.Map.of("A", java.util.Map.of(
                        "metric", "jvm.thread_count",
                        "aggs", "mean",
                        "from", List.of(java.util.Map.of(
                                "left", "serviceId",
                                "operator", "=",
                                "right", "demo-order"))))));
        assertThat(chart).hasSize(1);
        verify(reader).queryMetricSeries(org.mockito.ArgumentMatchers.argThat(sql ->
                sql.contains("AVG(`thread_count`)") && !sql.contains("SUM(`thread_count`)")));
    }

    @Test
    void returnsJvmGcChartWithDeltaSqlAndMsUnit() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryMetricSeries(anyString())).thenReturn(List.of(
                new MetricSeriesPoint(1_710_000_000L, 2.0)));
        MetricQueryService service = new MetricQueryService(reader, TestStorageSupport.storage());
        List<java.util.Map<String, Object>> countChart = service.metricChart(java.util.Map.of(
                "start", 1_710_000_000L,
                "end", 1_710_003_600L,
                "query", java.util.Map.of("A", java.util.Map.of(
                        "metric", "jvm.gc.minor_collection_count",
                        "aggs", "mean"))));
        assertThat(countChart.get(0).get("units")).isEqualTo(List.of("time", "count"));
        verify(reader).queryMetricSeries(org.mockito.ArgumentMatchers.argThat(sql ->
                sql.contains("LAG(counter_value)")));

        when(reader.queryMetricSeries(anyString())).thenReturn(List.of(
                new MetricSeriesPoint(1_710_000_000L, 150.0)));
        List<java.util.Map<String, Object>> timeChart = service.metricChart(java.util.Map.of(
                "start", 1_710_000_000L,
                "end", 1_710_003_600L,
                "query", java.util.Map.of("A", java.util.Map.of(
                        "metric", "jvm.gc.minor_collection_time",
                        "aggs", "mean"))));
        assertThat(timeChart.get(0).get("units")).isEqualTo(List.of("time", "ms"));
    }

    @Test
    void returnsMetricChartGroupedByService() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryTopGroups(anyString())).thenReturn(List.of("demo-order", "demo-pay"));
        when(reader.queryMetricSeries(anyString())).thenReturn(List.of(
                new MetricSeriesPoint(1_710_000_000L, 42.0)));
        MetricQueryService service = new MetricQueryService(reader, TestStorageSupport.storage());
        List<java.util.Map<String, Object>> chart = service.metricChart(java.util.Map.of(
                "start", 1_710_000_000L,
                "end", 1_710_003_600L,
                "query", java.util.Map.of("A", java.util.Map.of(
                        "metric", "service.http.cnt",
                        "aggs", "sum",
                        "by", List.of("service"),
                        "from", List.of(),
                        "types", List.of()))));
        assertThat(chart).hasSize(2);
        assertThat(chart.get(0).get("tags")).isEqualTo(java.util.Map.of("service", "demo-order"));
        assertThat(chart.get(1).get("tags")).isEqualTo(java.util.Map.of("service", "demo-pay"));
    }

    @Test
    void returnsServiceExceptionSeries() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryMetricSeries(anyString())).thenReturn(List.of(
                new MetricSeriesPoint(1_710_000_000L, 4.0)));
        MetricQueryService service = new MetricQueryService(reader, TestStorageSupport.storage());
        List<java.util.Map<String, Object>> chart = service.metricChart(java.util.Map.of(
                "start", 1_710_000_000L,
                "end", 1_710_003_600L,
                "query", java.util.Map.of("A", java.util.Map.of(
                        "metric", "service.exception.cnt",
                        "from", List.of(java.util.Map.of(
                                "left", "serviceId",
                                "operator", "=",
                                "right", "demo-order"))))));
        assertThat(chart).hasSize(1);
        @SuppressWarnings("unchecked")
        List<List<Number>> values = (List<List<Number>>) chart.get(0).get("values");
        assertThat(values).hasSize(60);
        assertThat(values.get(0)).isEqualTo(List.of(1_710_000_000_000L, 4.0));
        assertThat(values.get(1)).containsExactly(1_710_000_060_000L, null);
    }

    @Test
    void returnsServiceAvgDurationChartSeries() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryMetricSeries(anyString())).thenReturn(List.of(
                new MetricSeriesPoint(1_710_000_000L, 12.5)));
        MetricQueryService service = new MetricQueryService(reader, TestStorageSupport.storage());
        List<java.util.Map<String, Object>> chart = service.metricChart(java.util.Map.of(
                "start", 1_710_000_000L,
                "end", 1_710_003_600L,
                "query", java.util.Map.of("A", java.util.Map.of(
                        "metric", "service.avgDuration",
                        "aggs", "sum",
                        "by", List.of(),
                        "from", List.of(),
                        "types", List.of()))));
        assertThat(chart).hasSize(1);
        verify(reader).queryMetricSeries(org.mockito.ArgumentMatchers.argThat(sql ->
                sql.contains("metric_service")
                        && sql.contains("SUM(`sumDuration`) / NULLIF(SUM(`cnt`), 0) / 1000000")
                        && !sql.contains("`avgDuration`")));
    }
}
