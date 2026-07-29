package com.databuff.apm.web.portal;

import com.databuff.apm.common.query.ApmQueryModels.MetaServicePoint;
import com.databuff.apm.common.query.ApmQueryModels.SpanSummary;
import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.web.TestStorageSupport;
import com.databuff.apm.web.config.ApmStorageProperties;
import com.databuff.apm.web.flow.ServiceFlowService;
import com.databuff.apm.web.trace.TraceQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Integration-style test for {@code POST /webapi/trace/errorSpanList}:
 * virtual DB error spans are returned when filtering by caller web service.
 */
@SpringJUnitConfig(classes = TracePortalErrorSpanIntegrationTest.Config.class)
@ActiveProfiles({"local", "test"})
class TracePortalErrorSpanIntegrationTest {

    private static final String SERVICE_B_ID = "5457a0119281bb98";
    private static final String MYSQL_DEMO_APM_ID = "c72cc83a8831e407";

    @Autowired
    private ApmReadRepository readRepository;

    @Autowired
    private TracePortalService tracePortalService;

    @BeforeEach
    void setUp() {
        reset(readRepository);
    }

    @Test
    void errorSpanListFindsVirtualDbErrorsWhenFilteringByCallerWebService() throws Exception {
        when(readRepository.queryMetaServices(anyString())).thenReturn(List.of(
                new MetaServicePoint(
                        SERVICE_B_ID, "service-b", null, null, "web", null, null, null, null, null, null, null,
                        false, null, null, null, null)));
        when(readRepository.querySpanSummaries(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            assertThat(sql).contains("`srcServiceId` = '" + SERVICE_B_ID + "'");
            assertThat(sql).contains("InsufficientStockException");
            assertThat(sql).contains("`error` = 1");
            return List.of(virtualMysqlErrorSpan());
        });
        when(readRepository.queryCallSpanCount(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            assertThat(sql).contains("`srcServiceId` = '" + SERVICE_B_ID + "'");
            assertThat(sql).contains("InsufficientStockException");
            return 35L;
        });

        Map<String, Object> response = tracePortalService.errorSpanList(Map.of(
                "serviceId", SERVICE_B_ID,
                "exception", "InsufficientStockException",
                "fromTime", "2026-06-18 09:57:00",
                "toTime", "2026-06-18 10:57:00",
                "offset", 0,
                "size", 50,
                "sortField", "start",
                "sortOrder", "desc"));

        assertThat(response.get("status")).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertThat(data.get("total")).isEqualTo(35L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("service")).isEqualTo("[mysql]demo_apm");
        assertThat(list.get(0).get("serviceId")).isEqualTo(MYSQL_DEMO_APM_ID);
        assertThat(list.get(0).get("errorType")).isEqualTo("InsufficientStockException");
        assertThat(list.get(0).get("resource")).isEqualTo("SELECT demo_inventory");
    }

    @Test
    void errorSpanListUsesServiceIdOnlyWhenFilteringVirtualService() throws Exception {
        when(readRepository.queryMetaServices(anyString())).thenReturn(List.of(
                new MetaServicePoint(
                        MYSQL_DEMO_APM_ID, "[mysql]demo_apm", null, null, "db", null, null, null, null, null, null, null,
                        true, null, null, null, null)));
        when(readRepository.querySpanSummaries(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            assertThat(sql).contains("`serviceId` = '" + MYSQL_DEMO_APM_ID + "'");
            assertThat(sql).doesNotContain("`srcServiceId` =");
            return List.of(virtualMysqlErrorSpan());
        });
        when(readRepository.queryCallSpanCount(anyString())).thenReturn(1L);

        Map<String, Object> response = tracePortalService.errorSpanList(Map.of(
                "serviceId", MYSQL_DEMO_APM_ID,
                "exception", "InsufficientStockException",
                "fromTime", "2026-06-18 09:57:00",
                "toTime", "2026-06-18 10:57:00",
                "offset", 0,
                "size", 50));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertThat(data.get("total")).isEqualTo(1L);
    }

    private static SpanSummary virtualMysqlErrorSpan() {
        return new SpanSummary(
                "trace-insufficient-stock",
                "db-span",
                "[mysql]demo_apm",
                MYSQL_DEMO_APM_ID,
                "SELECT demo_inventory",
                "2026-06-18 10:30:00",
                30_000_000L,
                1,
                "service-b-1",
                "SELECT demo_inventory",
                "demo-host-b",
                null,
                "InsufficientStockException",
                "dubbo-server",
                0,
                null);
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
        TraceQueryService traceQueryService() {
            return mock(TraceQueryService.class);
        }

        @Bean
        ServiceFlowService serviceFlowService() {
            return mock(ServiceFlowService.class);
        }

        @Bean
        TracePortalService tracePortalService(
                TraceQueryService traceQueryService,
                ServiceFlowService serviceFlowService,
                ApmReadRepository apmReadRepository,
                ApmStorageProperties apmStorageProperties) {
            return new TracePortalService(
                    traceQueryService, serviceFlowService, apmReadRepository, apmStorageProperties);
        }
    }
}
