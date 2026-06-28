package com.databuff.apm.web.tools.local;

import com.databuff.apm.common.query.TimeSeriesFillUtil;
import com.databuff.apm.common.time.ApmTimeZones;
import com.databuff.apm.web.ai.TestBeanSupport;
import com.databuff.apm.web.monitor.service.AlarmService;
import com.databuff.apm.common.storage.ApmReadRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataToolsTest {

    private static final String FROM_TIME = "2026-06-07 08:10:00";
    private static final String TO_TIME = "2026-06-07 09:10:00";

    private ServicePortalService servicePortalService;
    private TracePortalService tracePortalService;
    private AlarmService alarmService;
    private DataTools dataTools;

    @BeforeEach
    void setUp() {
        servicePortalService = mock(ServicePortalService.class);
        tracePortalService = mock(TracePortalService.class);
        alarmService = mock(AlarmService.class);
        dataTools = TestBeanSupport.dataTools(servicePortalService, tracePortalService, alarmService, new ObjectMapper());
    }

    @Test
    @SuppressWarnings("unchecked")
    void treatsLiteralNullKeywordAsUnfiltered() {
        when(servicePortalService.basicServices(anyMap()))
                .thenReturn(List.of(Map.of("service", "demo")));

        String output = dataTools.queryServicesAll("null", null, null);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(servicePortalService).basicServices(captor.capture());
        assertThat(captor.getValue())
                .containsEntry("ignoreTime", 0)
                .doesNotContainKey("serviceName")
                .containsKeys("fromTime", "toTime");
        assertServiceListDefaultHourWindow(captor.getValue());
        assertThat(output).contains("\"demo\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void queriesServicesByTypeFromCatalog() {
        when(servicePortalService.basicServices(anyMap()))
                .thenReturn(List.of(Map.of("service", "demo")));

        String output = dataTools.queryServicesByServiceType("service", "demo", 10, null, null);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(servicePortalService).basicServices(captor.capture());
        assertThat(captor.getValue())
                .containsEntry("ignoreTime", 0)
                .containsEntry("serviceName", "demo")
                .containsKeys("fromTime", "toTime");
        assertServiceListDefaultHourWindow(captor.getValue());
        assertThat(captor.getValue().get("serviceTypes")).isEqualTo(List.of("web", "custom"));
        assertThat(output).contains("\"serviceType\":\"service\"").contains("\"demo\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void queriesAllServicesFromCatalog() {
        when(servicePortalService.basicServices(anyMap()))
                .thenReturn(List.of(Map.of("service", "demo")));

        String output = dataTools.queryServicesAll(null, null, null);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(servicePortalService).basicServices(captor.capture());
        assertThat(captor.getValue())
                .containsEntry("ignoreTime", 0)
                .containsKeys("fromTime", "toTime");
        assertServiceListDefaultHourWindow(captor.getValue());
        assertThat(output).contains("\"serviceType\":\"all\"").contains("\"demo\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void queriesAllServicesInTimeWindow() {
        when(servicePortalService.basicServices(anyMap()))
                .thenReturn(List.of(Map.of("service", "demo")));

        String output = dataTools.queryServicesAll(null, FROM_TIME, TO_TIME);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(servicePortalService).basicServices(captor.capture());
        assertThat(captor.getValue())
                .containsEntry("ignoreTime", 0)
                .containsEntry("fromTime", FROM_TIME)
                .containsEntry("toTime", TO_TIME);
        assertThat(output)
                .contains("\"serviceType\":\"all\"")
                .contains("\"fromTime\":\"" + FROM_TIME + "\"")
                .contains("\"toTime\":\"" + TO_TIME + "\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void queriesServicesByTypeInTimeWindow() {
        when(servicePortalService.basicServices(anyMap()))
                .thenReturn(List.of(Map.of("service", "demo")));

        String output = dataTools.queryServicesByServiceType("db", "demo", 10, FROM_TIME, TO_TIME);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(servicePortalService).basicServices(captor.capture());
        assertThat(captor.getValue())
                .containsEntry("ignoreTime", 0)
                .containsEntry("fromTime", FROM_TIME)
                .containsEntry("toTime", TO_TIME)
                .containsEntry("serviceType", "db");
        assertThat(output).contains("\"serviceType\":\"db\"");
    }

    @Test
    void rejectsPartialTimeRangeForServiceList() {
        String output = dataTools.queryServicesAll(null, FROM_TIME, null);

        assertThat(output)
                .contains("\"ok\":false")
                .contains("toTime is required");
    }

    @Test
    void rejectsServiceTopologyWithoutTimeRange() {
        String output = dataTools.queryServiceTopology("service-a", "", null, null);

        assertThat(output)
                .contains("\"ok\":false")
                .contains("getCurrentTimeRange");
    }

    @Test
    @SuppressWarnings("unchecked")
    void queriesServiceTopologyByServiceNameAndTimeRange() {
        when(servicePortalService.getServiceInstanceRelations(anyMap()))
                .thenReturn(Map.of("nodes", List.of(Map.of("service", "service-a"))));

        String output = dataTools.queryServiceTopology(
                "service-a", "", FROM_TIME, TO_TIME);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(servicePortalService).getServiceInstanceRelations(captor.capture());
        assertThat(captor.getValue())
                .containsEntry("serviceName", "service-a")
                .containsEntry("fromTime", FROM_TIME)
                .containsEntry("toTime", TO_TIME);
        assertThat(captor.getValue())
                .doesNotContainKey("serviceId")
                .doesNotContainKey("service");
        assertThat(output).contains("\"serviceName\":\"service-a\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void queriesServiceTopologyWithLlmMissingDateTimeSpace() {
        when(servicePortalService.getServiceInstanceRelations(anyMap()))
                .thenReturn(Map.of("nodes", List.of(Map.of("service", "service-a"))));

        String output = dataTools.queryServiceTopology(
                "service-a", "", "2026-06-0812:27:00", "2026-06-0812:37:00");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(servicePortalService).getServiceInstanceRelations(captor.capture());
        assertThat(captor.getValue())
                .containsEntry("fromTime", "2026-06-08 12:27:00")
                .containsEntry("toTime", "2026-06-08 12:37:00");
        assertThat(output).contains("\"fromTime\":\"2026-06-08 12:27:00\"");
    }

    @Test
    void convertsServiceTopologyDurationFromNsToMs() {
        when(servicePortalService.getServiceInstanceRelations(anyMap())).thenReturn(Map.of(
                "reqCnt", 1.0,
                "upflowServiceStats", List.of(Map.of(
                        "serviceId", "upstream",
                        "reqInCnt", 10L,
                        "reqOutCnt", 10L,
                        "reqInTime", 900_000_000.0,
                        "reqOutTime", 1_260_000_000.0,
                        "componentType", "service.http")),
                "downflowServiceStats", List.of(),
                "serviceId2Name", List.of()));

        String output = dataTools.queryServiceTopology(
                "service-a", "", FROM_TIME, TO_TIME);

        assertThat(output).contains("\"reqInTime\":900.0");
        assertThat(output).contains("\"reqOutTime\":1260.0");
    }

    @Test
    @SuppressWarnings("unchecked")
    void queriesTraceListWithMetricStatsAndTraceRows() {
        when(servicePortalService.callGraphStats(anyMap())).thenReturn(Map.of("callCnts", Map.of("k", 1)));
        when(tracePortalService.callSpans(anyMap())).thenReturn(Map.of("data", List.of(Map.of("trace_id", "t1"))));

        String output = dataTools.queryTraceListByCondition(
                "order", "pay", "service.http", "/pay", "out", FROM_TIME, TO_TIME, 5);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(servicePortalService).callGraphStats(captor.capture());
        assertThat(captor.getValue())
                .containsEntry("fromTime", FROM_TIME)
                .containsEntry("toTime", TO_TIME)
                .containsEntry("srcServiceId", "order")
                .containsEntry("serviceId", "pay")
                .containsEntry("isOut", 1);
        assertThat(output).contains("\"metricStats\"").contains("\"trace_id\":\"t1\"");
    }

    @Test
    void queriesTraceDetailByTraceId() {
        when(tracePortalService.traceSpans(anyMap())).thenReturn(Map.of("data", List.of(Map.of("span_id", "s1"))));

        String output = dataTools.queryTraceDetail("trace-1");

        assertThat(output)
                .contains("\"traceId\":\"trace-1\"")
                .contains("\"span_id\":\"s1\"")
                .doesNotContain("\"fromTime\"");
    }

    @Test
    void queriesMetricDataFromMetricCoreDorisTable() throws Exception {
        ApmReadRepository readRepository = mock(ApmReadRepository.class);
        when(readRepository.queryRows(anyString(), eq(10)))
                .thenReturn(List.of(Map.of("exceptionName", "RuntimeException", "metric_value", 3)));
        DataTools metricDataTools = TestBeanSupport.dataTools(
                servicePortalService,
                tracePortalService,
                alarmService,
                readRepository,
                new ObjectMapper());

        MetricQueryRequest queryRequest = metricQueryRequest(
                "metric_service_exception",
                List.of(aggregation("SUM", "cnt", "metric_value")),
                List.of(where("service", "=", "service-a")),
                List.of("exceptionName"),
                0,
                null,
                FROM_TIME,
                TO_TIME);

        String output = metricDataTools.queryMetricData(
                List.of(queryRequest),
                10);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(readRepository).queryRows(sqlCaptor.capture(), eq(10));
        assertThat(sqlCaptor.getValue())
                .contains("databuff.`metric_service_exception`")
                .contains("`service` = 'service-a'")
                .contains("GROUP BY");
        assertThat(output)
                .startsWith("[[")
                .contains("\"exceptionName\":\"RuntimeException\"")
                .endsWith("]]");
    }

    @Test
    void fillsMissingMetricTrendBucketsWithNull() throws Exception {
        ApmReadRepository readRepository = mock(ApmReadRepository.class);
        long fromMillis = ApmTimeZones.wallClockToEpochMilli(FROM_TIME);
        long firstBucket = TimeSeriesFillUtil.alignBucketEpochSecWithStep(fromMillis, 60);
        when(readRepository.queryRows(anyString(), eq(200)))
                .thenReturn(List.of(Map.of("epoch_sec", firstBucket, "reqCount", 5)));
        DataTools metricDataTools = TestBeanSupport.dataTools(
                servicePortalService,
                tracePortalService,
                alarmService,
                readRepository,
                new ObjectMapper());

        MetricQueryRequest queryRequest = metricQueryRequest(
                "metric_service",
                List.of(aggregation("SUM", "cnt", "reqCount")),
                List.of(where("service", "=", "service-a")),
                List.of(),
                60,
                "s",
                FROM_TIME,
                TO_TIME);

        String output = metricDataTools.queryMetricData(List.of(queryRequest), 200);

        assertThat(output).contains("\"reqCount\":5").contains("\"reqCount\":null");
        assertThat(output).contains("\"time\":\"" + FROM_TIME + "\"");
        assertThat(output.split("\"time\"").length - 1).isEqualTo(60);
        assertThat(output).doesNotContain("\"epoch_sec\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void queriesServiceAlarmsWithExplicitTimeRange() {
        when(alarmService.list(anyMap())).thenReturn(Map.of("data", Map.of("list", List.of())));

        String output = dataTools.queryServiceAlarms("demo-order", 0, FROM_TIME, TO_TIME);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(alarmService).list(captor.capture());
        assertThat(captor.getValue())
                .containsEntry("fromTime", FROM_TIME)
                .containsEntry("toTime", TO_TIME)
                .containsEntry("serviceId", "demo-order");
        assertThat(captor.getValue().get("status")).isEqualTo(List.of(0));
        assertThat(output).contains("\"serviceId\":\"demo-order\"");
    }

    private static MetricQueryRequest metricQueryRequest(
            String measurement,
            List<MetricQueryAggregation> aggregations,
            List<MetricQueryWhere> wheres,
            List<String> groupBy,
            int interval,
            String intervalUnit,
            String start,
            String end) {
        MetricQueryRequest request = new MetricQueryRequest();
        request.setMeasurement(measurement);
        request.setAggregations(aggregations);
        request.setWheres(wheres);
        request.setGroupBy(groupBy);
        request.setInterval(interval);
        request.setIntervalUnit(intervalUnit);
        request.setStart(start);
        request.setEnd(end);
        return request;
    }

    @Test
    void queriesMetricDataWithInListJsonStringValue() throws Exception {
        ApmReadRepository readRepository = mock(ApmReadRepository.class);
        when(readRepository.queryRows(anyString(), eq(10)))
                .thenReturn(List.of(Map.of("service", "service-a", "total_cnt", 5)));
        DataTools metricDataTools = TestBeanSupport.dataTools(
                servicePortalService,
                tracePortalService,
                alarmService,
                readRepository,
                new ObjectMapper());

        MetricQueryRequest queryRequest = metricQueryRequest(
                "metric_service",
                List.of(aggregation("SUM", "cnt", "total_cnt")),
                List.of(where(
                        "service_id",
                        "INLIST",
                        "[\"9bf61532d56eb7b5\",\"5457a0119281bb98\"]")),
                List.of("service"),
                0,
                null,
                FROM_TIME,
                TO_TIME);

        metricDataTools.queryMetricData(List.of(queryRequest), 10);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(readRepository).queryRows(sqlCaptor.capture(), eq(10));
        assertThat(sqlCaptor.getValue())
                .contains("`service_id` IN ('9bf61532d56eb7b5','5457a0119281bb98')")
                .doesNotContain("[\"9bf61532");
    }

    private static void assertServiceListDefaultHourWindow(Map<String, Object> body) {
        long from = ApmTimeZones.wallClockToEpochMilli(String.valueOf(body.get("fromTime")));
        long to = ApmTimeZones.wallClockToEpochMilli(String.valueOf(body.get("toTime")));
        assertThat(to - from).isEqualTo(3_600_000L);
    }

    private static MetricQueryAggregation aggregation(String function, String field, String alias) {
        MetricQueryAggregation aggregation = new MetricQueryAggregation();
        aggregation.setFunction(function);
        aggregation.setField(field);
        aggregation.setAlias(alias);
        return aggregation;
    }

    private static MetricQueryWhere where(String field, String operator, Object value) {
        MetricQueryWhere where = new MetricQueryWhere();
        where.setField(field);
        where.setOperator(operator);
        where.setValue(value);
        return where;
    }
}
