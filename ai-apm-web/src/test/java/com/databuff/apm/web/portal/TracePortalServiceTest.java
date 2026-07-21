package com.databuff.apm.web.portal;

import com.databuff.apm.common.query.ApmQueryModels;
import com.databuff.apm.common.query.ApmQueryModels.ExceptionListPoint;
import com.databuff.apm.common.query.ApmQueryModels.ServiceFlowTreeRow;
import com.databuff.apm.common.query.ApmQueryModels.SpanDetail;
import com.databuff.apm.common.query.ApmQueryModels.SpanSummary;
import com.databuff.apm.common.query.TimeSeriesFillUtil;
import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.web.TestStorageSupport;
import com.databuff.apm.web.flow.ServiceFlowService;
import com.databuff.apm.web.trace.TraceQueryService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TracePortalServiceTest {

    @Test
    void delegatesServiceInstanceCounts() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        when(traceQuery.serviceInstanceCounts(any())).thenReturn(Map.of("demo-order", 2));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Integer> counts = service.serviceInstanceCounts(Map.of(
                "from", 0L,
                "to", 1000L));

        assertThat(counts.get("demo-order")).isEqualTo(2);
    }

    @Test
    void listUsesEntrySpanScopeForParentZero() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        when(traceQuery.spanList(any())).thenReturn(List.of());
        when(traceQuery.spanListCount(any())).thenReturn(0L);

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        service.list(Map.of(
                "parentId", "0",
                "serviceIds", List.of("9bf61532d56eb7b5"),
                "fromTime", "2026-06-05 22:40:00",
                "toTime", "2026-06-05 22:41:00",
                "offset", 0,
                "size", 50));

        org.mockito.ArgumentCaptor<TraceQueryService.SpanListRequest> captor =
                org.mockito.ArgumentCaptor.forClass(TraceQueryService.SpanListRequest.class);
        org.mockito.Mockito.verify(traceQuery).spanList(captor.capture());
        assertThat(captor.getValue().isParent()).isEqualTo(1);
        assertThat(captor.getValue().serviceIds()).containsExactly("9bf61532d56eb7b5");
    }

    @Test
    void listPushesErrorFilterToSpanListQuery() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        when(traceQuery.spanList(any())).thenReturn(List.of());
        when(traceQuery.spanListCount(any())).thenReturn(0L);

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        service.list(Map.of(
                "parentId", "0",
                "fromTime", "2026-06-20 07:02:00",
                "toTime", "2026-06-20 07:03:00",
                "error", 1,
                "offset", 0,
                "size", 50));

        org.mockito.ArgumentCaptor<TraceQueryService.SpanListRequest> listCaptor =
                org.mockito.ArgumentCaptor.forClass(TraceQueryService.SpanListRequest.class);
        org.mockito.Mockito.verify(traceQuery).spanList(listCaptor.capture());
        org.mockito.Mockito.verify(traceQuery).spanListCount(listCaptor.getValue());
        assertThat(listCaptor.getValue().error()).isEqualTo(1);
        assertThat(listCaptor.getValue().isParent()).isEqualTo(1);
    }

    @Test
    void buildsPortalSpanListEnvelope() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        when(traceQuery.spanListCount(any())).thenReturn(1L);
        when(traceQuery.spanList(any())).thenReturn(List.of(
                new SpanSummary(
                        "t1", "s1", "demo-order", null, "GET /orders",
                        "2026-06-01 12:00:00", 2_000_000L, 1,
                        "host-1", "GET /orders", "host-1", 500, "timeout")));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Object> resp = service.list(Map.of(
                "fromTime", "2026-06-01 11:00:00",
                "toTime", "2026-06-01 13:00:00",
                "offset", 0,
                "size", 20));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("trace_id")).isEqualTo("t1");
        assertThat(list.get(0).get("serviceId")).isEqualTo("464a0a08964a061e");
    }

    @Test
    void spanListCombinesHttpMethodAndUrlWhenResourceIsMethodOnly() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        when(traceQuery.spanListCount(any())).thenReturn(1L);
        when(traceQuery.spanList(any())).thenReturn(List.of(
                new SpanSummary(
                        "t1", "s1", "service-b", null, "GET",
                        "2026-06-16 13:06:00", 2_000_000L, 0,
                        "service-b-pod", "GET", "service-b-pod", 200, null,
                        null, null, "/callB")));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Object> resp = service.list(Map.of(
                "parentId", "0",
                "fromTime", "2026-06-16 13:05:00",
                "toTime", "2026-06-16 13:06:00",
                "offset", 0,
                "size", 50));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("resource")).isEqualTo("GET /callB");
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) list.get(0).get("meta");
        assertThat(meta.get("http.url")).isEqualTo("/callB");
        assertThat(meta.get("http.method")).isEqualTo("GET");
    }

    @Test
    void resourceSpanListUsesAllSpansScopeAndResourceFilter() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        when(traceQuery.spanListCount(any())).thenReturn(1L);
        when(traceQuery.spanList(any())).thenReturn(List.of(
                new SpanSummary(
                        "t1", "s1", "demo-order", null, "GET /demo/checkout",
                        "2026-06-01 12:00:00", 2_000_000L, 0,
                        "host-1", "GET /demo/checkout", "host-1", 200, null,
                        null, null, "/demo/checkout"),
                new SpanSummary(
                        "t2", "s2", "demo-order", null, "GET /demo/health",
                        "2026-06-01 12:00:01", 1_000_000L, 0,
                        "host-1", "GET /demo/health", "host-1", 200, null,
                        null, null, "/demo/health")));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Object> resp = service.spanList(Map.of(
                "url", "/demo/checkout",
                "componentType", "service.http",
                "fromTime", "2026-06-01 11:00:00",
                "toTime", "2026-06-01 13:00:00",
                "offset", 0,
                "size", 20));

        org.mockito.ArgumentCaptor<TraceQueryService.SpanListRequest> captor =
                org.mockito.ArgumentCaptor.forClass(TraceQueryService.SpanListRequest.class);
        org.mockito.Mockito.verify(traceQuery).spanList(captor.capture());
        assertThat(captor.getValue().isParent()).isNull();
        assertThat(captor.getValue().resource()).isEqualTo("/demo/checkout");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("resource")).isEqualTo("GET /demo/checkout");
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) list.get(0).get("meta");
        assertThat(meta.get("http.url")).isEqualTo("/demo/checkout");
    }

    @Test
    void resourceSpanListMatchesAbsoluteHttpUrlAgainstPortalUrl() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        when(traceQuery.spanListCount(any())).thenReturn(1L);
        when(traceQuery.spanList(any())).thenReturn(List.of(
                new SpanSummary(
                        "t1", "s1", "frontend", "046b2a50b8c3c54c",
                        "GET /api/recommendations?productIds=HQTGWGPNH4",
                        "2026-07-21 15:59:00", 2_000_000L, 0,
                        "frontend-pod", "GET",
                        "frontend-pod", 200, null,
                        null, null,
                        "http://frontend-proxy:8080/api/recommendations?productIds=HQTGWGPNH4"),
                new SpanSummary(
                        "t2", "s2", "frontend", "046b2a50b8c3c54c",
                        "GET /api/health",
                        "2026-07-21 15:59:01", 1_000_000L, 0,
                        "frontend-pod", "GET /api/health",
                        "frontend-pod", 200, null,
                        null, null,
                        "http://frontend-proxy:8080/api/health")));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Object> resp = service.spanList(Map.of(
                "url", "/api/recommendations?productIds=HQTGWGPNH4",
                "componentType", "service.http",
                "fromTime", "2026-07-21 15:00:00",
                "toTime", "2026-07-21 16:00:00",
                "offset", 0,
                "size", 20));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
        assertThat(list).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) list.get(0).get("meta");
        assertThat(meta.get("http.url"))
                .isEqualTo("http://frontend-proxy:8080/api/recommendations?productIds=HQTGWGPNH4");
    }

    @Test
    void errorResourceSpanListFiltersErrors() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryCallSpanCount(anyString())).thenReturn(1L);
        when(reader.querySpanSummaries(anyString())).thenReturn(List.of(
                new SpanSummary(
                        "t1", "s1", "demo-order", null, "/demo/checkout",
                        "2026-06-01 12:00:00", 2_000_000L, 1,
                        "host-1", "/demo/checkout", "host-1", 500, "timeout"),
                new SpanSummary(
                        "t2", "s2", "demo-order", null, "/demo/checkout",
                        "2026-06-01 12:00:01", 1_000_000L, 0,
                        "host-1", "/demo/checkout", "host-1", 200, null)));

        TracePortalService service = new TracePortalService(
                mock(TraceQueryService.class), mock(ServiceFlowService.class), reader, TestStorageSupport.storage());
        Map<String, Object> resp = service.errorSpanList(Map.of(
                "resource", "/demo/checkout",
                "fromTime", "2026-06-01 11:00:00",
                "toTime", "2026-06-01 13:00:00",
                "offset", 0,
                "size", 20));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("trace_id")).isEqualTo("t1");
    }

    @Test
    void filtersSpanListByResourceAndError() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        when(traceQuery.spanListCount(any())).thenReturn(1L);
        when(traceQuery.spanList(any())).thenReturn(List.of(
                new SpanSummary(
                        "t1", "s1", "demo-order", null, "GET /orders",
                        "2026-06-01 12:00:00", 2_000_000L, 1,
                        "host-1", "GET /orders", "host-1", 500, "timeout"),
                new SpanSummary(
                        "t2", "s2", "demo-order", null, "GET /health",
                        "2026-06-01 12:00:01", 1_000_000L, 0,
                        "host-1", "GET /health", "host-1", 200, null)));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Object> resp = service.list(Map.of(
                "fromTime", "2026-06-01 11:00:00",
                "toTime", "2026-06-01 13:00:00",
                "resource", "GET /orders",
                "error", 1,
                "offset", 0,
                "size", 20));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("trace_id")).isEqualTo("t1");
        assertThat(list.get(0).get("errorType")).isEqualTo("timeout");
    }

    @Test
    void buildsTabnavStatusFromSpans() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        when(traceQuery.spanList(any())).thenReturn(List.of(
                new SpanSummary(
                        "t1", "s1", "demo-order", null, "GET /orders",
                        "2026-06-01 12:00:00", 2_000_000_000L, 1,
                        "host-1", "GET /orders", "host-1", 500, "timeout")));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        List<String> tabs = service.tabnavStatus(Map.of(
                "serviceId", "demo-order",
                "resource", "GET /orders",
                "fromTime", "2026-06-01 11:00:00",
                "toTime", "2026-06-01 13:00:00"));

        assertThat(tabs).contains("tab-error", "tab-slow", "tab-log");
        assertThat(tabs).doesNotContain("tab-relation", "tab-baseinfo");
    }

    @Test
    void tabnavStatusReturnsEmptyWhenNoSpans() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        when(traceQuery.spanList(any())).thenReturn(List.of());

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        List<String> tabs = service.tabnavStatus(Map.of(
                "serviceId", "demo-order",
                "fromTime", "2026-06-01 11:00:00",
                "toTime", "2026-06-01 13:00:00"));

        assertThat(tabs).isEmpty();
    }

    @Test
    void computesResourcePercentFromSpans() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        when(traceQuery.spanList(any())).thenReturn(List.of(
                new SpanSummary(
                        "t1", "s1", "demo-order", null, "GET /orders",
                        "2026-06-01 12:00:00", 1_000_000L, 0,
                        "host-1", "GET /orders", "host-1", 200, null),
                new SpanSummary(
                        "t2", "s2", "demo-order", null, "GET /orders",
                        "2026-06-01 12:00:01", 3_000_000L, 0,
                        "host-1", "GET /orders", "host-1", 200, null)));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Object> percent = service.resourcePercent(Map.of(
                "serviceId", "demo-order",
                "resource", "GET /orders",
                "duration", 2_000_000L,
                "fromTime", "2026-06-01 11:00:00",
                "toTime", "2026-06-01 13:00:00"));

        assertThat(percent.get("max")).isEqualTo(3_000_000L);
        assertThat(percent.get("this")).isEqualTo(50);
    }

    @Test
    void cntGraphStatsUsesTraceMetricTrendBuckets() throws Exception {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        when(traceQuery.spanList(any())).thenReturn(List.of(
                new SpanSummary(
                        "t1", "s1", "demo-order", null, "GET /orders",
                        "2026-06-06 07:19:00", 2_000_000L, 0,
                        "host-1", "GET /orders", "host-1", 200, null)));

        ApmReadRepository reader = mock(ApmReadRepository.class);
        long bucket = TimeSeriesFillUtil.alignBucketEpochSec(
                PortalTimeParser.rangeFrom(Map.of("fromTime", "2026-06-06 06:20:00"), 0), 60);
        when(reader.queryComponentTrendBuckets(anyString())).thenAnswer(invocation -> {
            assertThat(invocation.getArgument(0, String.class)).contains("metric_service_trace");
            return List.of(new ApmQueryModels.ComponentTrendBucketPoint(
                    bucket, "demo-order", 12, 0, 24_000_000L, 3_000_000L, 1_000_000L, 0, 0));
        });

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), reader, TestStorageSupport.storage());
        Map<String, Object> graphs = service.cntGraphStats(Map.of(
                "fromTime", "2026-06-06 06:20:00",
                "toTime", "2026-06-06 07:20:00",
                "interval", 60,
                "parentId", "0"));

        org.mockito.Mockito.verify(traceQuery, org.mockito.Mockito.never()).spanList(any());
        org.mockito.Mockito.verify(reader).queryComponentTrendBuckets(anyString());
        @SuppressWarnings("unchecked")
        Map<String, Number> callCnts = (Map<String, Number>) graphs.get("callCnts");
        assertThat(callCnts.get(String.valueOf(bucket * 1000L))).isEqualTo(12L);
    }

    @Test
    void errorCntGraphStatsUsesTraceMetricErrorBuckets() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        long bucket = TimeSeriesFillUtil.alignBucketEpochSec(
                PortalTimeParser.rangeFrom(Map.of("fromTime", "2026-06-06 07:34:00"), 0), 60);
        when(reader.queryComponentTrendBuckets(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            assertThat(sql).contains("metric_service_trace");
            assertThat(sql).contains("`errorType` = 'error'");
            return List.of(new ApmQueryModels.ComponentTrendBucketPoint(
                    bucket, "demo-order", 0, 3, 0, 0, 0, 0, 0));
        });

        TracePortalService service = new TracePortalService(
                mock(TraceQueryService.class), mock(ServiceFlowService.class), reader, TestStorageSupport.storage());
        Map<String, Object> graphs = service.errorCntGraphStats(Map.of(
                "fromTime", "2026-06-06 07:34:00",
                "toTime", "2026-06-06 07:49:00",
                "interval", 60,
                "parentId", "0"));

        org.mockito.Mockito.verify(reader).queryComponentTrendBuckets(anyString());
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Long>> errorCnts = (Map<String, Map<String, Long>>) graphs.get("errorCnts");
        assertThat(errorCnts.get(String.valueOf(bucket * 1000L))).containsEntry("demo-order", 3L);
    }

    @Test
    void graphStatsUsesTraceMetricTrendBuckets() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        long bucket = TimeSeriesFillUtil.alignBucketEpochSec(
                PortalTimeParser.rangeFrom(Map.of("fromTime", "2026-06-01 11:00:00"), 0), 60);
        when(reader.queryComponentTrendBuckets(anyString())).thenAnswer(invocation -> {
            assertThat(invocation.getArgument(0, String.class)).contains("metric_service_trace");
            return List.of(new ApmQueryModels.ComponentTrendBucketPoint(
                    bucket, "demo-order", 10, 1, 12_000_000_000L, 3_000_000L, 1_000_000L, 0, 0));
        });

        TracePortalService service = new TracePortalService(
                mock(TraceQueryService.class), mock(ServiceFlowService.class), reader, TestStorageSupport.storage());
        Map<String, Object> graphs = service.graphStats(Map.of(
                "fromTime", "2026-06-01 11:00:00",
                "toTime", "2026-06-01 13:00:00",
                "serviceId", "demo-order"));

        @SuppressWarnings("unchecked")
        Map<String, Number> latencies =
                (Map<String, Number>) graphs.get("avgLatencys");
        assertThat(latencies.get(String.valueOf(bucket * 1000L))).isEqualTo(1_200_000_000.0);
    }

    @Test
    void metricGraphStatsFillsFullTimeRange() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        long from = PortalTimeParser.rangeFrom(Map.of("fromTime", "2026-06-06 06:20:00"), 0);
        long to = PortalTimeParser.rangeTo(Map.of("toTime", "2026-06-06 07:20:00"), 0);
        long earlyBucket = TimeSeriesFillUtil.alignBucketEpochSec(from, 60);
        long lateBucket = TimeSeriesFillUtil.alignBucketEpochSec(to - 1, 60);
        when(reader.queryComponentTrendBuckets(anyString())).thenReturn(List.of(
                new ApmQueryModels.ComponentTrendBucketPoint(
                        earlyBucket, "demo-order", 8, 0, 8_000_000L, 2_000_000L, 1_000_000L, 0, 0),
                new ApmQueryModels.ComponentTrendBucketPoint(
                        lateBucket, "demo-order", 5, 0, 5_000_000L, 2_000_000L, 1_000_000L, 0, 0)));

        TracePortalService service = new TracePortalService(
                mock(TraceQueryService.class), mock(ServiceFlowService.class), reader, TestStorageSupport.storage());
        Map<String, Object> graphs = service.cntGraphStats(Map.of(
                "fromTime", "2026-06-06 06:20:00",
                "toTime", "2026-06-06 07:20:00",
                "interval", 60,
                "parentId", "0"));

        @SuppressWarnings("unchecked")
        Map<String, Number> callCnts = (Map<String, Number>) graphs.get("callCnts");
        assertThat(callCnts.get(String.valueOf(earlyBucket * 1000L))).isEqualTo(8L);
        assertThat(callCnts.get(String.valueOf(lateBucket * 1000L))).isEqualTo(5L);
        assertThat(callCnts).hasSizeGreaterThan(30);
    }

    @Test
    void buildsTraceDetailSpans() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        long rootStartNs = 1_000_000_000_000_000_000L;
        when(traceQuery.traceDetail(any())).thenReturn(List.of(
                new SpanDetail("t1", "s1", "0", "demo-order", null, "GET /orders",
                        "2026-06-01 12:00:00", rootStartNs, 2_000_000L, 0, "host-1"),
                new SpanDetail("t1", "s2", "s1", "demo-order", null, "SELECT orders",
                        "2026-06-01 12:00:00", rootStartNs + 50_000_000L, 1_000_000L, 0, "host-1")));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Object> resp = service.traceSpans(Map.of("traceId", "t1", "size", 100));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> spans = (List<Map<String, Object>>) resp.get("data");
        assertThat(spans).hasSize(2);
        assertThat(spans.get(0).get("parent_id")).isEqualTo("0");
        assertThat(spans.get(0).get("relativeTime")).isEqualTo(0L);
        assertThat(spans.get(1).get("relativeTime")).isEqualTo(50_000_000L);
    }

    @Test
    void traceDetailSpansPreserveDirectionAndHttpUrlResource() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        long rootStartNs = 1_000_000_000_000_000_000L;
        when(traceQuery.traceDetail(any())).thenReturn(List.of(
                new SpanDetail(
                        "t1", "s1", "0", "demo-order", null, "GET", "2026-06-01 12:00:00",
                        rootStartNs, 2_000_000L, 0, "host-1", "inst-1", "GET", "web",
                        1, 0, "{}", null, 200, "GET", "http://example.test/orders?id=1", null),
                new SpanDetail(
                        "t1", "s2", "s1", "demo-order", null, "POST", "2026-06-01 12:00:00",
                        rootStartNs + 50_000_000L, 1_000_000L, 0, "host-1", "inst-1", "POST", "web",
                        0, 1, "{}", null, 200, "POST", "/pay", null)));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Object> resp = service.traceSpans(Map.of("traceId", "t1", "size", 100));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> spans = (List<Map<String, Object>>) resp.get("data");
        assertThat(spans.get(0).get("resource")).isEqualTo("GET /orders?id=1");
        assertThat(spans.get(0).get("service_type")).isEqualTo("web");
        assertThat(spans.get(0).get("type")).isEqualTo("web");
        assertThat(spans.get(0).get("isIn")).isEqualTo(1);
        assertThat(spans.get(0).get("isOut")).isEqualTo(0);
        assertThat(spans.get(1).get("resource")).isEqualTo("POST /pay");
        assertThat(spans.get(1).get("isIn")).isEqualTo(0);
        assertThat(spans.get(1).get("isOut")).isEqualTo(1);
    }

    @Test
    void traceDetailSpansMapSpanKindTypeToPortalWebDisplay() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        long rootStartNs = 1_000_000_000_000_000_000L;
        when(traceQuery.traceDetail(any())).thenReturn(List.of(
                new SpanDetail(
                        "t1", "s1", "0", "service-a", null, "GET /demo/checkout", "2026-06-01 12:00:00",
                        rootStartNs, 2_000_000L, 0, "host-1", "inst-1", "GET /demo/checkout", "SPAN_KIND_SERVER",
                        1, 0, "{}", null, 200, "GET", "/demo/checkout", null)));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Object> resp = service.traceSpans(Map.of("traceId", "t1", "size", 100));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> spans = (List<Map<String, Object>>) resp.get("data");
        assertThat(spans.get(0).get("service_type")).isEqualTo("web");
        assertThat(spans.get(0).get("type")).isEqualTo("web");
    }

    @Test
    void traceDetailEntrySpanMapsClientPeerFromMeta() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        long rootStartNs = 1_000_000_000_000_000_000L;
        when(traceQuery.traceDetail(any())).thenReturn(List.of(
                new SpanDetail(
                        "t1", "s1", "0", "flagd", null, "EventStream", "2026-06-01 12:00:00",
                        rootStartNs, 2_000_000L, 0, "flagd-host", "flagd-instance-1",
                        "flagd.evaluation.v1.Service/EventStream", "SPAN_KIND_SERVER",
                        1, 0,
                        "{\"client.service\":\"fraud-detection\",\"client.ip\":\"fraud-inst-1\",\"rpc.system\":\"grpc\"}",
                        null, null, null, null, null)));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Object> resp = service.traceSpans(Map.of("traceId", "t1", "size", 100));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> spans = (List<Map<String, Object>>) resp.get("data");
        assertThat(spans.get(0).get("clientService")).isEqualTo("fraud-detection");
        assertThat(spans.get(0).get("clientServiceInstance")).isEqualTo("fraud-inst-1");
    }

    @Test
    void traceDetailSpansResolveComponentServiceTypes() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        long rootStartNs = 1_000_000_000_000_000_000L;
        when(traceQuery.traceDetail(any())).thenReturn(List.of(
                new SpanDetail(
                        "t1", "s1", "0", "service-a", null, "GET /demo/checkout", "2026-06-01 12:00:00",
                        rootStartNs, 2_000_000L, 0, "host-1", "inst-1", "GET /demo/checkout", "SPAN_KIND_SERVER",
                        1, 0, "{}", null, 200, "GET", "/demo/checkout", null),
                new SpanDetail(
                        "t1", "s2", "s1", "service-a", null, "SELECT demo_order", "2026-06-01 12:00:00",
                        rootStartNs + 10_000_000L, 500_000L, 0, "host-1", "inst-1", "SELECT demo_order", "SPAN_KIND_CLIENT",
                        0, 1, "{\"db.system\":\"mysql\",\"db.operation\":\"select\"}", null, null, null, null, null),
                new SpanDetail(
                        "t1", "s3", "s1", "service-a", null, "SET order:10001", "2026-06-01 12:00:00",
                        rootStartNs + 20_000_000L, 300_000L, 0, "host-1", "inst-1", "SET order:10001", "SPAN_KIND_CLIENT",
                        0, 1, "{\"db.system\":\"redis\",\"db.statement\":\"SET order:10001\"}", null, null, null, null, null)));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Object> resp = service.traceSpans(Map.of("traceId", "t1", "size", 100));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> spans = (List<Map<String, Object>>) resp.get("data");
        assertThat(spans.get(0).get("service_type")).isEqualTo("web");
        assertThat(spans.get(1).get("service_type")).isEqualTo("db");
        assertThat(spans.get(1).get("type")).isEqualTo("mysql");
        assertThat(spans.get(2).get("service_type")).isEqualTo("cache");
        assertThat(spans.get(2).get("type")).isEqualTo("redis");
    }

    @Test
    void preservesSubMillisecondTraceSpanDuration() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        long rootStartNs = 1_000_000_000_000_000_000L;
        when(traceQuery.traceDetail(any())).thenReturn(List.of(
                new SpanDetail("t1", "s1", "0", "demo-order", null, "GET /orders",
                        "2026-06-01 12:00:00", rootStartNs, 4_000_000L, 0, "host-1"),
                new SpanDetail("t1", "s2", "s1", "demo-order", null, "SELECT * FROM orders",
                        "2026-06-01 12:00:00", rootStartNs + 1_000_000L, 500_000L, 0, "host-1")));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Object> resp = service.traceSpans(Map.of("traceId", "t1", "size", 100));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> spans = (List<Map<String, Object>>) resp.get("data");
        assertThat(spans.get(1).get("duration")).isEqualTo(500_000L);
        assertThat(spans.get(1).get("exectime")).isEqualTo(500_000L);
    }

    @Test
    void serviceFlowBuildsTreeFromTraceId() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        long rootStartNs = 1_000_000_000_000_000_000L;
        when(traceQuery.traceDetail(any())).thenReturn(List.of(
                new SpanDetail("t1", "s1", "0", "gateway", "gw-id", "GET /",
                        "2026-06-01 12:00:00", rootStartNs, 100_000_000L, 0, "host-gw"),
                new SpanDetail("t1", "s2", "s1", "gateway", "gw-id", "route",
                        "2026-06-01 12:00:00", rootStartNs + 10_000_000L, 20_000_000L, 0, "host-gw"),
                new SpanDetail("t1", "s3", "s2", "checkout", "co-id", "POST /pay",
                        "2026-06-01 12:00:00", rootStartNs + 20_000_000L, 50_000_000L, 0, "host-co")));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Object> resp = service.serviceFlow(Map.of("traceId", "t1"));

        assertThat(resp.get("service")).isEqualTo("gateway");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) resp.get("children");
        assertThat(children).hasSize(1);
        assertThat(children.get(0).get("service")).isEqualTo("checkout");
    }

    @Test
    void pairsClientAndServerCallSpans() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryMetaServices(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("demo-order")) {
                return List.of(new ApmQueryModels.MetaServicePoint(
                        "demo-order", "demo-order", null, null, null, null, null, null, null, null, null, null, false, null, null, null, null));
            }
            return List.of(new ApmQueryModels.MetaServicePoint(
                    "demo-pay", "demo-pay", null, null, null, null, null, null, null, null, null, null, false, null, null, null, null));
        });
        when(reader.queryCallSpanCount(anyString())).thenReturn(1L);
        ApmQueryModels.CallSpanRow serverSpan =
                new ApmQueryModels.CallSpanRow(
                        "t1", "s2", "s1", 100L, 110L, "GET /orders", 900_000L, 0, 0,
                        "demo-order", "464a0a08964a061e", "order-1", "demo-pay", "5531560ada6ec064", "pay-1",
                        "demo-order", "464a0a08964a061e", "order-1", 1, 0,
                        "GET /orders", "{}", null, 200, "GET", "/orders", null);
        ApmQueryModels.CallSpanRow clientSpan =
                new ApmQueryModels.CallSpanRow(
                        "t1", "s1", "0", 100L, 110L, "GET /orders", 1_000_000L, 0, 0,
                        "demo-pay", "5531560ada6ec064", "pay-1", "", "", "",
                        "demo-order", "464a0a08964a061e", "order-1", 0, 1,
                        "GET /orders", "{}", null, 200, "GET", "/orders", null);
        when(reader.queryCallSpans(anyString()))
                .thenReturn(List.of(clientSpan))
                .thenReturn(List.of(serverSpan));

        TracePortalService service = new TracePortalService(
                mock(TraceQueryService.class), mock(ServiceFlowService.class), reader, TestStorageSupport.storage());
        Map<String, Object> resp = service.callSpans(Map.of(
                "serviceId", "demo-order",
                "srcServiceId", "demo-pay",
                "resource", "GET /orders",
                "componentType", "service.http",
                "fromTime", "2026-06-01 11:00:00",
                "toTime", "2026-06-01 13:00:00",
                "offset", 0,
                "size", 20));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("data");
        assertThat(resp.get("total")).isEqualTo(1L);
        assertThat(rows).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> client = (Map<String, Object>) rows.get(0).get("client");
        @SuppressWarnings("unchecked")
        Map<String, Object> server = (Map<String, Object>) rows.get(0).get("server");
        assertThat(client.get("span_id")).isEqualTo("s1");
        assertThat(server.get("span_id")).isEqualTo("s2");
        assertThat(rows.get(0).get("httpMethod")).isEqualTo("GET");
    }

    @Test
    void skipsCallSpanCountWhenTotalIsNotRequested() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryMetaServices(anyString())).thenReturn(List.of(new ApmQueryModels.MetaServicePoint(
                "demo-order", "demo-order", null, null, null, null, null, null, null, null, null, null,
                false, null, null, null, null)));
        ApmQueryModels.CallSpanRow clientSpan = new ApmQueryModels.CallSpanRow(
                "t1", "s1", "0", 100L, 110L, "GET /orders", 1_000_000L, 0, 0,
                "demo-pay", "pay-id", "pay-1", "", "", "",
                "demo-order", "demo-order", "order-1", 0, 1,
                "GET /orders", "{}", null, 200, "GET", "/orders", null);
        when(reader.queryCallSpans(anyString()))
                .thenReturn(List.of(clientSpan))
                .thenReturn(List.of());

        TracePortalService service = new TracePortalService(
                mock(TraceQueryService.class), mock(ServiceFlowService.class), reader, TestStorageSupport.storage());
        Map<String, Object> response = service.callSpans(Map.of(
                "serviceId", "demo-order",
                "componentType", "service.http",
                "fromTime", "2026-06-01 11:00:00",
                "toTime", "2026-06-01 13:00:00",
                "offset", 0,
                "size", 10,
                "includeTotal", false));

        assertThat(response.get("total")).isEqualTo(1L);
        verify(reader, never()).queryCallSpanCount(anyString());
    }

    @Test
    void queriesHttpCallSpansByPathWhenSpanResourceUsesRouteTemplate() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryMetaServices(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("5457a0119281bb98")) {
                return List.of(new ApmQueryModels.MetaServicePoint(
                        "5457a0119281bb98", "service-b", null, null, "web", null, null, null, null, null, null, null,
                        false, null, null, null, null));
            }
            return List.of(new ApmQueryModels.MetaServicePoint(
                    "9bf61532d56eb7b5", "service-a", null, null, "web", null, null, null, null, null, null, null,
                    false, null, null, null, null));
        });
        when(reader.queryCallSpanCount(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            assertThat(sql).contains("LIKE '%/api/orders/10001%'");
            assertThat(sql).contains("= 'GET'");
            assertThat(sql).contains("`meta.http.method`");
            assertThat(sql).containsAnyOf("9bf61532d56eb7b5", "service-a");
            assertThat(sql).containsAnyOf("5457a0119281bb98", "service-b");
            return 1L;
        });
        ApmQueryModels.CallSpanRow clientSpan =
                new ApmQueryModels.CallSpanRow(
                        "trace-8", "http-client", "root-a", 100L, 110L, "GET /api/orders/{orderId}", 1_000_000L, 0, 0,
                        "service-a", "9bf61532d56eb7b5", "service-a-1", "service-a", "9bf61532d56eb7b5", "service-a-1",
                        "service-b", "5457a0119281bb98", "service-b-1", 0, 1,
                        "GET /api/orders/{orderId}", "{}", null, 200, "GET", "/api/orders/10001", null);
        when(reader.queryCallSpans(anyString())).thenReturn(List.of(clientSpan));

        TracePortalService service = new TracePortalService(
                mock(TraceQueryService.class), mock(ServiceFlowService.class), reader, TestStorageSupport.storage());
        Map<String, Object> resp = service.callSpans(Map.of(
                "serviceId", "5457a0119281bb98",
                "srcServiceId", "9bf61532d56eb7b5",
                "resource", "/api/orders/10001",
                "httpMethod", "GET",
                "componentType", "service.http",
                "fromTime", "2026-06-05 21:45:00",
                "toTime", "2026-06-05 21:46:00",
                "offset", 0,
                "size", 50));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("data");
        assertThat(resp.get("total")).isEqualTo(1L);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("url")).isEqualTo("/api/orders/10001");
    }

    @Test
    void queriesDbCallSpansByDstServiceAndSqlStatement() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryMetaServices(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("dad537de7e10e098")) {
                return List.of(new ApmQueryModels.MetaServicePoint(
                        "dad537de7e10e098", "mysql", null, null, "db", null, null, null, null, null, null, null,
                        true, null, null, null, null));
            }
            return List.of(new ApmQueryModels.MetaServicePoint(
                    "9bf61532d56eb7b5", "service-a", null, null, "web", null, null, null, null, null, null, null,
                    false, null, null, null, null));
        });
        when(reader.queryCallSpanCount(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            assertThat(sql).contains("`dstServiceId` = 'dad537de7e10e098'");
            assertThat(sql).contains("`srcServiceId` = '9bf61532d56eb7b5'");
            assertThat(sql).contains("db.statement");
            assertThat(sql).contains("(FLOOR(`end` / 1000000 / 60000) * 60000) >= ");
            assertThat(sql).contains("(FLOOR(`end` / 1000000 / 60000) * 60000) < ");
            assertThat(sql).doesNotContain("`startTime` >=");
            return 1L;
        });
        ApmQueryModels.CallSpanRow dbSpan =
                new ApmQueryModels.CallSpanRow(
                        "t-db", "db1", "root", 100L, 110L, "INSERT demo_order_audit", 20_000_000L, 0, 0,
                        "service-a", "9bf61532d56eb7b5", "service-a-1", "service-a", "9bf61532d56eb7b5", "service-a-1",
                        "mysql", "dad537de7e10e098", "", 1, 1,
                        "INSERT demo_order_audit",
                        "{\"db.system\":\"mysql\",\"db.name\":\"demo_apm\","
                                + "\"db.statement\":\"INSERT INTO demo_order_audit(order_id, channel) VALUES (?, ?)\"}",
                        null, null, null, null, null);
        when(reader.queryCallSpans(anyString())).thenReturn(List.of(dbSpan));

        TracePortalService service = new TracePortalService(
                mock(TraceQueryService.class), mock(ServiceFlowService.class), reader, TestStorageSupport.storage());
        Map<String, Object> resp = service.callSpans(Map.of(
                "serviceId", "dad537de7e10e098",
                "srcServiceId", "9bf61532d56eb7b5",
                "resource", "INSERT INTO demo_order_audit(order_id, channel) VALUES (?, ?)",
                "componentType", "service.db",
                "fromTime", "2026-06-05 21:37:00",
                "toTime", "2026-06-05 21:38:00",
                "offset", 0,
                "size", 50));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("data");
        assertThat(resp.get("total")).isEqualTo(1L);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("dbType")).isEqualTo("mysql");
        assertThat(rows.get(0).get("sqlDatabase")).isEqualTo("demo_apm");
    }

    @Test
    void multipleServiceFlowReturnsEmptyWhenEntrypointMissing() {
        TracePortalService service = new TracePortalService(
                mock(TraceQueryService.class), mock(ServiceFlowService.class), mock(ApmReadRepository.class),
                TestStorageSupport.storage());
        Map<String, Object> resp = service.multipleServiceFlow(Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> flows = (Map<String, Object>) resp.get("serviceFlows");
        assertThat(flows).isEmpty();
    }

    @Test
    void exceptionListReadsMetricServiceExceptionRows() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryExceptionListCount(anyString())).thenReturn(1L);
        when(reader.queryExceptionList(anyString())).thenReturn(List.of(
                new ExceptionListPoint(
                        1_710_000_000_000L,
                        "/api/orders/10001",
                        "InsufficientStockException",
                        "service-b",
                        "service-b-id",
                        "inst-1",
                        "/demo/checkout",
                        2L)));

        TracePortalService service = new TracePortalService(
                mock(TraceQueryService.class), mock(ServiceFlowService.class), reader, TestStorageSupport.storage());
        Map<String, Object> resp = service.exceptionList(Map.of(
                "serviceId", "service-b",
                "fromTime", "2026-06-01 11:00:00",
                "toTime", "2026-06-01 13:00:00",
                "offset", 0,
                "size", 20));

        assertThat(resp.get("total")).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("data");
        assertThat(rows.get(0).get("resource")).isEqualTo("/api/orders/10001");
        assertThat(rows.get(0).get("errorType")).isEqualTo("InsufficientStockException");
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) rows.get(0).get("meta");
        assertThat(meta.get("error.type")).isEqualTo("InsufficientStockException");
    }

    @Test
    void multipleServiceFlowBuildsTreeFromMetricRows() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryServiceFlowTreeRows(anyString())).thenReturn(List.of(
                new ServiceFlowTreeRow("root", "", "gateway", "gw", "GET /", 1, 10, 0, 10, 1000),
                new ServiceFlowTreeRow("child", "root", "checkout", "co", "POST /pay", 1, 4, 0, 4, 400)));

        TracePortalService service = new TracePortalService(
                mock(TraceQueryService.class), mock(ServiceFlowService.class), reader, TestStorageSupport.storage());
        Map<String, Object> resp = service.multipleServiceFlow(Map.of(
                "entrypointPathId", "123456789",
                "fromTime", "2026-06-05 21:37:00",
                "toTime", "2026-06-05 21:38:00"));

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> flows = (Map<String, Map<String, Object>>) resp.get("serviceFlows");
        assertThat(flows).containsKey("gateway");
    }
}
