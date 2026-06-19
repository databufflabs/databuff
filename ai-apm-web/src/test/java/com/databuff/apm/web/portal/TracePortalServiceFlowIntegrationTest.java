package com.databuff.apm.web.portal;

import com.databuff.apm.common.flow.ServiceFlowPathIds;
import com.databuff.apm.common.query.ApmQueryModels.ServiceFlowEdge;
import com.databuff.apm.common.query.ApmQueryModels.ServiceFlowTreeRow;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Portal integration tests for service-level ({@code /trace/serviceFlow}) and
 * interface-level ({@code /trace/multipleServiceFlow}) service flow trees.
 */
@SpringJUnitConfig(classes = TracePortalServiceFlowIntegrationTest.Config.class)
@ActiveProfiles({"local", "test"})
class TracePortalServiceFlowIntegrationTest {

    private static final String SERVICE_A = "service-a";
    private static final String SERVICE_A_ID = "9bf61532d56eb7b5";
    private static final String SERVICE_B_ID = "5457a0119281bb98";
    private static final String DEMO_CHECKOUT_RESOURCE = "GET /demo/checkout";
    private static final String ENTRY_PATH_ID = ServiceFlowPathIds.entryPathId(SERVICE_A_ID);
    private static final String ENTRY_INTERFACE_PATH_ID =
            ServiceFlowPathIds.entryInterfacePathId(SERVICE_A_ID, DEMO_CHECKOUT_RESOURCE);

    @Autowired
    private ApmReadRepository readRepository;

    @Autowired
    private ServiceFlowService serviceFlowService;

    @Autowired
    private TracePortalService tracePortalService;

    @BeforeEach
    void setUp() {
        reset(readRepository, serviceFlowService);
    }

    @Test
    void serviceFlowBuildsServiceLevelTreeWithVirtualServices() {
        when(serviceFlowService.listFlows(eq(SERVICE_A), anyLong(), anyLong(), anyInt()))
                .thenReturn(demoServiceLevelEdges());

        Map<String, Object> resp = tracePortalService.serviceFlow(Map.of(
                "service", SERVICE_A,
                "serviceId", SERVICE_A,
                "fromTime", "2026-06-18 09:57:00",
                "toTime", "2026-06-18 10:57:00"));

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> flows =
                (Map<String, Map<String, Object>>) resp.get("serviceFlows");
        assertThat(flows).containsKey(SERVICE_A);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children =
                (List<Map<String, Object>>) flows.get(SERVICE_A).get("children");
        Set<String> childServices = children.stream()
                .map(child -> String.valueOf(child.get("service")))
                .collect(Collectors.toSet());

        assertThat(childServices).contains(
                "service-b",
                "[mysql]demo_apm",
                "[redis]redis:6379",
                "[kafka]order-events",
                "[elasticsearch]es:9200",
                "[remote]payments.example.com:443");
    }

    @Test
    void multipleServiceFlowBuildsInterfaceLevelTreeWithVirtualServices() throws Exception {
        when(readRepository.queryDistinctStrings(anyString(), eq("entry_interface_path_id")))
                .thenReturn(List.of(ENTRY_INTERFACE_PATH_ID));
        when(readRepository.queryServiceFlowTreeRows(anyString())).thenReturn(demoInterfaceLevelRows());

        Map<String, Object> resp = tracePortalService.multipleServiceFlow(Map.of(
                "entrypointPathId", ENTRY_PATH_ID,
                "resource", "/demo/checkout",
                "fromTime", "2026-06-18 09:57:00",
                "toTime", "2026-06-18 10:57:00"));

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> flows =
                (Map<String, Map<String, Object>>) resp.get("serviceFlows");
        assertThat(flows).containsKey(SERVICE_A);

        @SuppressWarnings("unchecked")
        Map<String, Object> root = flows.get(SERVICE_A);
        assertThat(root.get("resource")).isEqualTo(DEMO_CHECKOUT_RESOURCE);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) root.get("children");
        Set<String> childServices = children.stream()
                .map(child -> String.valueOf(child.get("service")))
                .collect(Collectors.toSet());
        assertThat(childServices).contains(
                "service-b",
                "[mysql]demo_apm",
                "[redis]redis:6379",
                "[kafka]order-events",
                "[elasticsearch]es:9200",
                "[remote]payments.example.com:443");

        @SuppressWarnings("unchecked")
        Map<String, Object> serviceB = children.stream()
                .filter(child -> "service-b".equals(child.get("service")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> serviceBChildren = (List<Map<String, Object>>) serviceB.get("children");
        Set<String> serviceBChildServices = serviceBChildren.stream()
                .map(child -> String.valueOf(child.get("service")))
                .collect(Collectors.toSet());
        assertThat(serviceBChildServices).contains("[mysql]demo_apm", "[redis]redis:6379");
    }

    private static List<ServiceFlowEdge> demoServiceLevelEdges() {
        List<ServiceFlowEdge> edges = new ArrayList<>();
        edges.add(edge(SERVICE_A, "service-b", SERVICE_A_ID, SERVICE_B_ID, 20, 70.0));
        edges.add(edge(SERVICE_A, "[mysql]demo_apm", SERVICE_A_ID, "c72cc83a8831e407", 10, 20.0));
        edges.add(edge(SERVICE_A, "[redis]redis:6379", SERVICE_A_ID, "94d612e1c2166206", 10, 13.0));
        edges.add(edge(SERVICE_A, "[kafka]order-events", SERVICE_A_ID, "kafka-id", 10, 8.0));
        edges.add(edge(SERVICE_A, "[elasticsearch]es:9200", SERVICE_A_ID, "c89bb188ecff78ec", 10, 18.0));
        edges.add(edge(SERVICE_A, "[remote]payments.example.com:443", SERVICE_A_ID, "remote-id", 10, 7.0));
        edges.add(edge("service-b", "[mysql]demo_apm", SERVICE_B_ID, "c72cc83a8831e407", 20, 31.0));
        edges.add(edge("service-b", "[redis]redis:6379", SERVICE_B_ID, "94d612e1c2166206", 10, 13.0));
        return edges;
    }

    private static List<ServiceFlowTreeRow> demoInterfaceLevelRows() {
        List<ServiceFlowTreeRow> rows = new ArrayList<>();
        rows.add(row("root-a", "", SERVICE_A, SERVICE_A_ID, DEMO_CHECKOUT_RESOURCE, 1, 10, 0, 10, 2_400_000_000L));
        rows.add(row("child-b", "root-a", "service-b", SERVICE_B_ID, "GET /api/orders/{orderId}", 1, 10, 0, 10, 800_000_000L));
        rows.add(row("child-mysql-a", "root-a", "[mysql]demo_apm", "c72cc83a8831e407", "", 1, 10, 0, 10, 200_000_000L));
        rows.add(row("child-redis-a", "root-a", "[redis]redis:6379", "94d612e1c2166206", "", 1, 10, 0, 10, 130_000_000L));
        rows.add(row("child-kafka", "root-a", "[kafka]order-events", "kafka-id", "", 1, 10, 0, 10, 80_000_000L));
        rows.add(row("child-es", "root-a", "[elasticsearch]es:9200", "c89bb188ecff78ec", "", 1, 10, 0, 10, 180_000_000L));
        rows.add(row("child-remote", "root-a", "[remote]payments.example.com:443", "remote-id", "", 1, 10, 0, 10, 70_000_000L));
        rows.add(row("child-mysql-b", "child-b", "[mysql]demo_apm", "c72cc83a8831e407", "", 1, 10, 0, 10, 450_000_000L));
        rows.add(row("child-redis-b", "child-b", "[redis]redis:6379", "94d612e1c2166206", "", 1, 10, 0, 10, 130_000_000L));
        return rows;
    }

    private static ServiceFlowEdge edge(
            String src,
            String dst,
            String srcId,
            String dstId,
            long callCount,
            double avgDuration) {
        return new ServiceFlowEdge(src, dst, callCount, 0, avgDuration, srcId, dstId);
    }

    private static ServiceFlowTreeRow row(
            String pathId,
            String parentPathId,
            String service,
            String serviceId,
            String resource,
            int isIn,
            long callCount,
            long errorCount,
            long srcCall,
            long sumDuration) {
        return new ServiceFlowTreeRow(
                pathId, parentPathId, service, serviceId, resource, isIn, callCount, errorCount, srcCall, sumDuration);
    }

    @Configuration
    static class Config {
        @Bean
        ApmReadRepository apmReadRepository() {
            return mock(ApmReadRepository.class);
        }

        @Bean
        ServiceFlowService serviceFlowService() {
            return mock(ServiceFlowService.class);
        }

        @Bean
        TraceQueryService traceQueryService() {
            return mock(TraceQueryService.class);
        }

        @Bean
        ApmStorageProperties apmStorageProperties() {
            return TestStorageSupport.storage();
        }

        @Bean
        TracePortalService tracePortalService(
                TraceQueryService traceQueryService,
                ServiceFlowService serviceFlowService,
                ApmReadRepository apmReadRepository,
                ApmStorageProperties storageProperties) {
            return new TracePortalService(
                    traceQueryService, serviceFlowService, apmReadRepository, storageProperties);
        }
    }
}
