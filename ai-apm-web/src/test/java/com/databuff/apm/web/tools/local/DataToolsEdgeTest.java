package com.databuff.apm.web.tools.local;

import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.web.ai.TestBeanSupport;
import com.databuff.apm.web.monitor.service.AlarmService;
import com.databuff.apm.web.portal.ServicePortalService;
import com.databuff.apm.web.portal.TracePortalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataToolsEdgeTest {

    private static final String FROM_TIME = "2026-06-07 08:10:00";
    private static final String TO_TIME = "2026-06-07 09:10:00";

    private ServicePortalService servicePortalService;
    private TracePortalService tracePortalService;
    private AlarmService alarmService;
    private ApmReadRepository readRepository;
    private DataTools dataTools;

    @BeforeEach
    void setUp() {
        servicePortalService = mock(ServicePortalService.class);
        tracePortalService = mock(TracePortalService.class);
        alarmService = mock(AlarmService.class);
        readRepository = mock(ApmReadRepository.class);
        dataTools = TestBeanSupport.dataTools(
                servicePortalService, tracePortalService, alarmService, readRepository, new ObjectMapper());
    }

    @Test
    void rejectsBlankServiceType() {
        assertThat(dataTools.queryServicesByServiceType(null, null, null, null, null))
                .contains("\"serviceType is required\"");
    }

    @Test
    void rejectsUnsupportedServiceType() {
        assertThat(dataTools.queryServicesByServiceType("bogus", null, null, FROM_TIME, TO_TIME))
                .contains("\"serviceType must be one of service, web, db, mq, cache, remote\"");
    }

    @Test
    void rejectsBlankServiceNameForTopology() {
        assertThat(dataTools.queryServiceTopology("", null, FROM_TIME, TO_TIME))
                .contains("\"serviceName is required\"");
    }

    @Test
    void rejectsBlankTraceId() {
        assertThat(dataTools.queryTraceDetail("  ")).contains("\"traceId is required\"");
    }

    @Test
    void rejectsBlankServiceIdForAlarms() {
        assertThat(dataTools.queryServiceAlarms("", 0, FROM_TIME, TO_TIME))
                .contains("\"serviceId is required\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void limitsServiceListRows() {
        when(servicePortalService.basicServices(anyMap())).thenReturn(List.of(
                Map.of("service", "a"), Map.of("service", "b"), Map.of("service", "c")));

        String output = dataTools.queryServicesByServiceType("service", null, 2, FROM_TIME, TO_TIME);

        assertThat(output).contains("\"total\":2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void fallsBackToCustomServiceTypeForRemoteWhenEmpty() {
        when(servicePortalService.basicServices(anyMap()))
                .thenReturn(List.of(), List.of(Map.of("service", "demo")));

        String output = dataTools.queryServicesByServiceType("remote", null, 5, FROM_TIME, TO_TIME);

        verify(servicePortalService, times(2)).basicServices(anyMap());
        assertThat(output).contains("\"serviceType\":\"remote\"").contains("\"demo\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void appliesWebAndCacheServiceTypeFilters() {
        when(servicePortalService.basicServices(anyMap())).thenReturn(List.of());

        dataTools.queryServicesByServiceType("web", null, 5, FROM_TIME, TO_TIME);
        dataTools.queryServicesByServiceType("cache", null, 5, FROM_TIME, TO_TIME);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(servicePortalService, times(2)).basicServices(captor.capture());
        assertThat(captor.getAllValues().get(0)).containsEntry("serviceTypes", List.of("web"));
        assertThat(captor.getAllValues().get(1)).containsEntry("serviceType", "cache");
    }

    @Test
    void queryMetricDataRejectsMissingDependencies() {
        DataTools noRepository = TestBeanSupport.dataTools(
                servicePortalService, tracePortalService, alarmService, new ObjectMapper());

        assertThat(noRepository.queryMetricData(null, 10))
                .contains("\"metric query dependencies are not ready\"");
    }

    @Test
    void queryMetricDataRejectsEmptyRequests() {
        assertThat(dataTools.queryMetricData(List.of(), 10))
                .contains("\"queryRequests must be a non-empty list\"");
    }

    @Test
    void queryMetricDataRejectsNullItem() {
        java.util.List<MetricQueryRequest> requests = new java.util.ArrayList<>();
        requests.add(null);

        assertThat(dataTools.queryMetricData(requests, 10))
                .contains("\"queryRequests must not contain null items\"");
    }

    @Test
    void queryMetricDataWrapsRepositoryFailure() throws Exception {
        when(readRepository.queryRows(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new RuntimeException("tsdb down"));

        String output = dataTools.queryMetricData(List.of(request("metric_service")), 10);

        assertThat(output).contains("metric query failed").contains("tsdb down");
    }

    @Test
    void queryMetricDataRejectsUnsafeGroupBy() throws Exception {
        MetricQueryRequest request = request("metric_service");
        request.setGroupBy(List.of("bad name"));

        String output = dataTools.queryMetricData(List.of(request), 10);

        assertThat(output).contains("unsafe identifier");
    }

    @Test
    void queryMetricDataBuildsIsNullClause() throws Exception {
        when(readRepository.queryRows(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(Map.of("epoch_sec", 1L, "cnt", 1)));

        dataTools.queryMetricData(List.of(request("metric_service", List.of(where("field", "IS NULL", null)))), 10);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(readRepository).queryRows(sql.capture(), org.mockito.ArgumentMatchers.anyInt());
        assertThat(sql.getValue()).contains("`field` IS NULL");
    }

    @Test
    void queryMetricDataBuildsInClause() throws Exception {
        when(readRepository.queryRows(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(Map.of("epoch_sec", 1L, "cnt", 1)));

        dataTools.queryMetricData(
                List.of(request("metric_service", List.of(where("service_id", "IN", List.of("a", "b"))))), 10);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(readRepository).queryRows(sql.capture(), org.mockito.ArgumentMatchers.anyInt());
        assertThat(sql.getValue()).contains("`service_id` IN ('a','b')");
    }

    @Test
    void queryMetricDataBuildsLikeAndNotLikeClauses() throws Exception {
        when(readRepository.queryRows(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(Map.of("epoch_sec", 1L, "cnt", 1)));

        dataTools.queryMetricData(
                List.of(request("metric_service", List.of(where("name", "LIKE", "%x%"))),
                        request("metric_service", List.of(where("name", "NOT_LIKE", "y%")))),
                10);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(readRepository, times(2)).queryRows(sql.capture(), org.mockito.ArgumentMatchers.anyInt());
        assertThat(sql.getAllValues().get(0)).contains("`name` LIKE '%x%'");
        assertThat(sql.getAllValues().get(1)).contains("`name` NOT LIKE 'y%'");
    }

    @Test
    void queryMetricDataSkipsBlankValueClause() throws Exception {
        when(readRepository.queryRows(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(Map.of("epoch_sec", 1L, "cnt", 1)));

        dataTools.queryMetricData(
                List.of(request("metric_service", List.of(where("field", "=", "  ")))), 10);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(readRepository).queryRows(sql.capture(), org.mockito.ArgumentMatchers.anyInt());
        assertThat(sql.getValue()).doesNotContain("`field`");
    }

    @Test
    void queryMetricDataNormalizesDefaultOperator() throws Exception {
        when(readRepository.queryRows(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(Map.of("epoch_sec", 1L, "cnt", 1)));

        dataTools.queryMetricData(
                List.of(request("metric_service", List.of(where("field", "unknown-op", "x")))), 10);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(readRepository).queryRows(sql.capture(), org.mockito.ArgumentMatchers.anyInt());
        assertThat(sql.getValue()).contains("`field` = 'x'");
    }

    @Test
    void queryMetricDataSelectsRawFieldWithoutFunction() throws Exception {
        when(readRepository.queryRows(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(Map.of("epoch_sec", 1L, "cnt", 1)));

        MetricQueryRequest request = request("metric_service");
        MetricQueryAggregation aggregation = new MetricQueryAggregation();
        aggregation.setField("cnt");
        request.setAggregations(List.of(aggregation));

        dataTools.queryMetricData(List.of(request), 10);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(readRepository).queryRows(sql.capture(), org.mockito.ArgumentMatchers.anyInt());
        assertThat(sql.getValue()).contains("`cnt`");
    }

    @Test
    void queryMetricDataBuildsHourlyBucket() throws Exception {
        when(readRepository.queryRows(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(Map.of("epoch_sec", 1L, "cnt", 1)));

        MetricQueryRequest request = request("metric_service");
        request.setInterval(1);
        request.setIntervalUnit("h");

        dataTools.queryMetricData(List.of(request), 10);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(readRepository).queryRows(sql.capture(), org.mockito.ArgumentMatchers.anyInt());
        assertThat(sql.getValue()).contains("FLOOR(`ts` / 3600000)").contains("AS BIGINT) AS epoch_sec");
    }

    @Test
    void queryMetricDataFillsRowsWithoutAggregations() throws Exception {
        long fromMillis = com.databuff.apm.common.time.ApmTimeZones.wallClockToEpochMilli(FROM_TIME);
        long firstBucket = com.databuff.apm.common.query.TimeSeriesFillUtil.alignBucketEpochSecWithStep(fromMillis, 60);
        when(readRepository.queryRows(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(Map.of("epoch_sec", firstBucket, "total_cnt", 5)));

        MetricQueryRequest request = request("metric_service");
        request.setInterval(60);
        request.setIntervalUnit("s");

        String output = dataTools.queryMetricData(List.of(request), 200);

        assertThat(output).contains("\"total_cnt\":5").contains("\"time\":");
    }

    @Test
    @SuppressWarnings("unchecked")
    void queriesServiceAlarmsWithoutStatus() {
        when(alarmService.list(anyMap())).thenReturn(Map.of("data", Map.of("list", List.of())));

        String output = dataTools.queryServiceAlarms("demo-order", null, FROM_TIME, TO_TIME);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(alarmService).list(captor.capture());
        assertThat(captor.getValue())
                .containsEntry("serviceId", "demo-order")
                .doesNotContainKey("status");
        assertThat(output).contains("\"demo-order\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void appliesDirectionFiltersToTraceCondition() {
        when(servicePortalService.callGraphStats(anyMap())).thenReturn(Map.of());
        when(tracePortalService.callSpans(anyMap())).thenReturn(Map.of("data", List.of()));

        dataTools.queryTraceListByCondition(null, null, null, null, "in", FROM_TIME, TO_TIME, 5);
        dataTools.queryTraceListByCondition(null, null, null, null, "both", FROM_TIME, TO_TIME, 5);
        dataTools.queryTraceListByCondition(null, null, null, null, "bogus", FROM_TIME, TO_TIME, 5);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(servicePortalService, times(3)).callGraphStats(captor.capture());
        assertThat(captor.getAllValues().get(0)).containsEntry("isIn", 1);
        assertThat(captor.getAllValues().get(1)).containsEntry("isIn", 1).containsEntry("isOut", 1);
        assertThat(captor.getAllValues().get(2)).doesNotContainKey("isIn").doesNotContainKey("isOut");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ignoresNonListTopologyStats() {
        when(servicePortalService.getServiceInstanceRelations(anyMap()))
                .thenReturn(Map.of("upflowServiceStats", "not-a-list"));

        String output = dataTools.queryServiceTopology("service-a", "", FROM_TIME, TO_TIME);

        assertThat(output).contains("\"upflowServiceStats\":[]");
    }

    private static MetricQueryRequest request(String measurement) {
        MetricQueryRequest request = new MetricQueryRequest();
        request.setMeasurement(measurement);
        request.setStart(FROM_TIME);
        request.setEnd(TO_TIME);
        return request;
    }

    private static MetricQueryRequest request(String measurement, List<MetricQueryWhere> wheres) {
        MetricQueryRequest request = request(measurement);
        request.setWheres(wheres);
        return request;
    }

    private static MetricQueryWhere where(String field, String operator, Object value) {
        MetricQueryWhere where = new MetricQueryWhere();
        where.setField(field);
        where.setOperator(operator);
        where.setValue(value);
        return where;
    }
}
