package com.databuff.apm.web.portal;

import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.common.time.ApmTimeZones;
import com.databuff.apm.web.TestStorageSupport;
import com.databuff.apm.web.config.ApmStorageProperties;
import com.databuff.apm.web.log.LogQueryService;
import com.databuff.apm.web.portal.support.DemoLogFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Portal integration tests for log analysis ({@code POST /webapi/log/*}):
 * trend chart, search list, and facet conditions stay consistent under filters.
 */
@SpringJUnitConfig(classes = LogPortalAnalysisIntegrationTest.Config.class)
@ActiveProfiles({"local", "test"})
class LogPortalAnalysisIntegrationTest {

    private static final int TOTAL_ROWS = 12;
    private static final int SERVICE_A_ROWS = 6;
    private static final int SERVICE_B_ROWS = 6;
    private static final int ERROR_ROWS = 2;

    @Autowired
    private ApmReadRepository readRepository;

    @Autowired
    private LogPortalService logPortalService;

    private final DemoLogFixture fixture = new DemoLogFixture();

    @BeforeEach
    void setUp() throws Exception {
        reset(readRepository);
        when(readRepository.queryRows(anyString(), anyInt())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            int maxRows = invocation.getArgument(1);
            return fixture.queryRows(sql, maxRows);
        });
        when(readRepository.queryCallSpanCount(anyString())).thenAnswer(invocation ->
                fixture.count(invocation.getArgument(0)));
    }

    @Test
    void searchAndTrendAgreeOnUnfilteredWindow() {
        Map<String, Object> body = baseBody();

        Map<String, Object> search = logPortalService.search(body);
        Map<String, Object> trend = logPortalService.trend(body);

        assertThat(search.get("total")).isEqualTo((long) TOTAL_ROWS);
        assertThat(search.get("data")).asList().hasSize(TOTAL_ROWS);
        assertThat(chartTotal(trend)).isEqualTo(TOTAL_ROWS);
        assertThat(severityTotal(trend, "INFO")).isEqualTo(8);
        assertThat(severityTotal(trend, "WARN")).isEqualTo(2);
        assertThat(severityTotal(trend, "ERROR")).isEqualTo(ERROR_ROWS);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) search.get("data");
        assertThat(rows).allMatch(row -> row.get("timeNs") != null && !String.valueOf(row.get("timeNs")).isBlank());
        assertThat(rows).allMatch(row -> !row.containsKey("attributes"));
        assertThat(rows).allMatch(row -> !row.containsKey("resources"));
    }

    @Test
    void detailLoadsAttributesAndResourcesForExpandedRow() {
        Map<String, Object> search = logPortalService.search(baseBody());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) search.get("data");
        Map<String, Object> listRow = rows.stream()
                .filter(row -> "Available stock below threshold (2 units)".equals(row.get("message")))
                .findFirst()
                .orElseThrow();

        Map<String, Object> detailBody = new LinkedHashMap<>();
        detailBody.put("timeNs", listRow.get("timeNs"));
        detailBody.put("serviceId", listRow.get("serviceId"));

        Map<String, Object> detail = logPortalService.detail(detailBody);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) detail.get("data");

        assertThat(data.get("message")).isEqualTo("Available stock below threshold (2 units)");
        assertThat(data.get("status")).isEqualTo("WARN");
        assertThat(data.get("severityNumber")).isEqualTo(13);
        assertThat(data.get("traceId")).isEqualTo(DemoLogFixture.TRACE_CHECKOUT);
        @SuppressWarnings("unchecked")
        Map<String, String> attributes = (Map<String, String>) data.get("attributes");
        assertThat(attributes).containsEntry("order.id", "10001");
        @SuppressWarnings("unchecked")
        Map<String, String> resources = (Map<String, String>) data.get("resources");
        assertThat(resources).containsEntry("k8s.namespace.name", "demo");
        assertThat(resources).containsEntry("host.name", DemoLogFixture.HOST_B);
    }

    @Test
    void serviceIdFilterKeepsSearchAndTrendAligned() {
        Map<String, Object> body = baseBody();
        body.put("serviceIds", List.of(DemoLogFixture.SERVICE_A_ID));

        Map<String, Object> search = logPortalService.search(body);
        Map<String, Object> trend = logPortalService.trend(body);

        assertThat(search.get("total")).isEqualTo((long) SERVICE_A_ROWS);
        assertThat(chartTotal(trend)).isEqualTo(SERVICE_A_ROWS);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) search.get("data");
        assertThat(rows).allMatch(row -> DemoLogFixture.SERVICE_A_ID.equals(row.get("serviceId")));
        assertThat(rows).allMatch(row -> "INFO".equals(row.get("status")));
    }

    @Test
    void severityHostInstanceAndQueryFiltersMatchTrendCounts() {
        Map<String, Object> errorBody = baseBody();
        errorBody.put("severities", List.of("ERROR"));
        assertThat(logPortalService.search(errorBody).get("total")).isEqualTo((long) ERROR_ROWS);
        assertThat(chartTotal(logPortalService.trend(errorBody))).isEqualTo(ERROR_ROWS);

        Map<String, Object> hostBody = baseBody();
        hostBody.put("hosts", List.of(DemoLogFixture.HOST_A));
        assertThat(logPortalService.search(hostBody).get("total")).isEqualTo((long) SERVICE_A_ROWS);
        assertThat(chartTotal(logPortalService.trend(hostBody))).isEqualTo(SERVICE_A_ROWS);

        Map<String, Object> instanceBody = baseBody();
        instanceBody.put("serviceInstances", List.of(DemoLogFixture.INSTANCE_B));
        assertThat(logPortalService.search(instanceBody).get("total")).isEqualTo((long) SERVICE_B_ROWS);
        assertThat(chartTotal(logPortalService.trend(instanceBody))).isEqualTo(SERVICE_B_ROWS);

        Map<String, Object> queryBody = baseBody();
        queryBody.put("query", "InsufficientStock");
        assertThat(logPortalService.search(queryBody).get("total")).isEqualTo((long) ERROR_ROWS);
        assertThat(chartTotal(logPortalService.trend(queryBody))).isEqualTo(ERROR_ROWS);
    }

    @Test
    void traceIdFilterReturnsOnlyMatchingRows() {
        Map<String, Object> body = baseBody();
        body.put("traceId", DemoLogFixture.TRACE_CHECKOUT);

        Map<String, Object> search = logPortalService.search(body);
        assertThat(search.get("total")).isEqualTo((long) TOTAL_ROWS);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) search.get("data");
        assertThat(rows).allMatch(row -> DemoLogFixture.TRACE_CHECKOUT.equals(row.get("traceId")));
        assertThat(chartTotal(logPortalService.trend(body))).isEqualTo(TOTAL_ROWS);
    }

    @Test
    void narrowerListWindowReturnsSubsetMatchingChart() {
        Map<String, Object> full = baseBody();
        Map<String, Object> minuteOnly = new LinkedHashMap<>(full);
        long minuteFromNs = ApmTimeZones.wallClockToEpochMilli("2026-07-04 10:51:00") * 1_000_000L;
        long minuteToNs = ApmTimeZones.wallClockToEpochMilli("2026-07-04 10:52:00") * 1_000_000L;
        minuteOnly.put("fromTimeNs", Long.toString(minuteFromNs));
        minuteOnly.put("toTimeNs", Long.toString(minuteToNs));

        long fullTotal = (long) logPortalService.search(full).get("total");
        long minuteTotal = (long) logPortalService.search(minuteOnly).get("total");
        long minuteChart = chartTotal(logPortalService.trend(minuteOnly));

        assertThat(fullTotal).isEqualTo(TOTAL_ROWS);
        assertThat(minuteTotal).isEqualTo(6L);
        assertThat(minuteChart).isEqualTo(minuteTotal);
    }

    @Test
    void conditionsCrossFilterBySeverityWithoutSelfFilteringSeverityFacet() {
        Map<String, Object> body = baseBody();
        body.put("severities", List.of("ERROR"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) logPortalService.conditions(body).get("data");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services = (List<Map<String, Object>>) data.get("services");
        assertThat(services).extracting(item -> item.get("name")).containsExactly(DemoLogFixture.SERVICE_B);
        assertThat(data.get("serviceInstances")).isEqualTo(List.of(DemoLogFixture.INSTANCE_B));
        assertThat(data.get("hosts")).isEqualTo(List.of(DemoLogFixture.HOST_B));
        @SuppressWarnings("unchecked")
        List<String> severities = (List<String>) data.get("severities");
        assertThat(severities).containsExactlyInAnyOrder("INFO", "WARN", "ERROR");
    }

    @Test
    void conditionsRespectServiceIdFilterForOtherFacets() {
        Map<String, Object> body = baseBody();
        body.put("serviceIds", List.of(DemoLogFixture.SERVICE_A_ID));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) logPortalService.conditions(body).get("data");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services = (List<Map<String, Object>>) data.get("services");
        assertThat(services).hasSize(2);
        assertThat(data.get("serviceInstances")).isEqualTo(List.of(DemoLogFixture.INSTANCE_A));
        assertThat(data.get("hosts")).isEqualTo(List.of(DemoLogFixture.HOST_A));
        @SuppressWarnings("unchecked")
        List<String> severities = (List<String>) data.get("severities");
        assertThat(severities).containsExactly("INFO");
    }

    @Test
    void searchSqlAppliesExpectedFilters() throws Exception {
        Map<String, Object> body = baseBody();
        body.put("serviceIds", List.of(DemoLogFixture.SERVICE_B_ID));
        body.put("severities", List.of("ERROR"));
        body.put("query", "InsufficientStock");
        body.put("traceId", DemoLogFixture.TRACE_CHECKOUT);

        when(readRepository.queryRows(anyString(), anyInt())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            assertThat(sql).contains("service_id IN ('" + DemoLogFixture.SERVICE_B_ID + "')");
            assertThat(sql).contains("severity IN ('ERROR')");
            assertThat(sql).contains("body LIKE '%InsufficientStock%'");
            assertThat(sql).contains("trace_id = '" + DemoLogFixture.TRACE_CHECKOUT + "'");
            return fixture.queryRows(sql, invocation.getArgument(1));
        });

        Map<String, Object> search = logPortalService.search(body);
        assertThat(search.get("total")).isEqualTo((long) ERROR_ROWS);
    }

    private static Map<String, Object> baseBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fromTimeNs", DemoLogFixture.FROM_TIME_NS);
        body.put("toTimeNs", DemoLogFixture.TO_TIME_NS);
        body.put("interval", 60);
        body.put("offset", 0);
        body.put("size", 1000);
        return body;
    }

    @SuppressWarnings("unchecked")
    private static long chartTotal(Map<String, Object> trendResponse) {
        Map<String, Object> data = (Map<String, Object>) trendResponse.get("data");
        Map<String, Number> logCnts = (Map<String, Number>) data.get("logCnts");
        long total = 0L;
        for (Number value : logCnts.values()) {
            if (value != null) {
                total += value.longValue();
            }
        }
        return total;
    }

    @SuppressWarnings("unchecked")
    private static long severityTotal(Map<String, Object> trendResponse, String severity) {
        Map<String, Object> data = (Map<String, Object>) trendResponse.get("data");
        Map<String, Map<String, Number>> severityCnts = (Map<String, Map<String, Number>>) data.get("severityCnts");
        long total = 0L;
        for (Map<String, Number> bucket : severityCnts.values()) {
            if (bucket == null) {
                continue;
            }
            Number value = bucket.get(severity);
            if (value != null) {
                total += value.longValue();
            }
        }
        return total;
    }

    @Configuration
    static class Config {

        @Bean
        ApmReadRepository apmReadRepository() {
            return mock(ApmReadRepository.class);
        }

        @Bean
        ApmStorageProperties apmStorageProperties() {
            return TestStorageSupport.storage();
        }

        @Bean
        LogQueryService logQueryService(
                ApmReadRepository apmReadRepository,
                ApmStorageProperties apmStorageProperties) {
            return new LogQueryService(apmReadRepository, apmStorageProperties);
        }

        @Bean
        LogPortalService logPortalService(LogQueryService logQueryService) {
            return new LogPortalService(logQueryService);
        }
    }
}
