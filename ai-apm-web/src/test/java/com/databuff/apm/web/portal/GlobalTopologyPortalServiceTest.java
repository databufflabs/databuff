package com.databuff.apm.web.portal;

import com.databuff.apm.common.query.ApmQueryModels.ServiceFlowEdge;
import com.databuff.apm.common.util.PortalServiceIdResolver;
import com.databuff.apm.web.monitor.Alarm;
import com.databuff.apm.web.monitor.AlarmStore;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalTopologyPortalServiceTest {

    private static GlobalTopologyPortalService service(
            GlobalTopologyQueryService queryService,
            AlarmStore alarmStore) {
        return new GlobalTopologyPortalService(queryService, new ServiceAlarmCounter(alarmStore));
    }

    private static AlarmStore emptyAlarmStore() {
        AlarmStore alarmStore = mock(AlarmStore.class);
        when(alarmStore.listInTimeRange(any(), any())).thenReturn(List.of());
        return alarmStore;
    }

    @Test
    void buildsServiceGraphFromMergedMetricEdges() {
        GlobalTopologyQueryService queryService = Mockito.mock(GlobalTopologyQueryService.class);
        when(queryService.listEdges(anyLong(), anyLong(), anyInt())).thenReturn(List.of(
                new ServiceFlowEdge("order", "pay", 100, 5, 12.5, "order-id", "pay-id"),
                new ServiceFlowEdge("gateway", "order", 200, 0, 8.0, "gw-id", "order-id")));

        GlobalTopologyPortalService service = service(queryService, emptyAlarmStore());
        Map<String, Object> data = service.graph(Map.of("fromTime", 0L, "toTime", 3_600_000L));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services = (List<Map<String, Object>>) data.get("services");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) data.get("serviceEdges");

        assertThat(services).hasSize(3);
        assertThat(edges).hasSize(2);
        assertThat(edges.get(0)).containsEntry("cnt", 100L).containsEntry("avgDuration", 12.5);
        assertThat(edges.get(0)).containsEntry("errRate", 0.05);
    }

    @Test
    void infersVirtualServiceTypeFromMetricName() {
        GlobalTopologyQueryService queryService = Mockito.mock(GlobalTopologyQueryService.class);
        when(queryService.listEdges(anyLong(), anyLong(), anyInt())).thenReturn(List.of(
                new ServiceFlowEdge("order", "[db]mysql", 10, 0, 5.0, "order-id", null)));

        GlobalTopologyPortalService service = service(queryService, emptyAlarmStore());
        Map<String, Object> data = service.graph(Map.of("fromTime", 0L, "toTime", 3_600_000L));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services = (List<Map<String, Object>>) data.get("services");
        Map<String, Object> dbNode = services.stream()
                .filter(node -> "[db]mysql".equals(node.get("name")))
                .findFirst()
                .orElseThrow();
        assertThat(dbNode).containsEntry("service_type", "db").containsEntry("type", "mysql");
    }

    @Test
    void buildsVerticalTreeForSelectedService() {
        GlobalTopologyQueryService queryService = Mockito.mock(GlobalTopologyQueryService.class);
        when(queryService.listEdges(anyLong(), anyLong(), anyInt())).thenReturn(List.of(
                new ServiceFlowEdge("order", "pay", 100, 5, 12.5, "order-id", "pay-id"),
                new ServiceFlowEdge("gateway", "order", 200, 0, 8.0, "gw-id", "order-id")));

        GlobalTopologyPortalService service = service(queryService, emptyAlarmStore());
        String orderId = PortalServiceIdResolver.normalize("order-id");
        String payId = PortalServiceIdResolver.normalize("pay-id");
        String gwId = PortalServiceIdResolver.normalize("gw-id");
        Map<String, Object> data = service.verticalTree(Map.of(
                "serviceId", "order-id",
                "fromTime", 0L,
                "toTime", 3_600_000L));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services = (List<Map<String, Object>>) data.get("services");
        @SuppressWarnings("unchecked")
        Map<String, List<String>> serviceToServices = (Map<String, List<String>>) data.get("serviceToServices");

        assertThat(services).extracting(node -> node.get("id")).contains(orderId, payId, gwId);
        assertThat(serviceToServices.get(orderId)).contains(payId);
        assertThat(serviceToServices.get(gwId)).contains(orderId);
    }

    @Test
    void marksNodesWithAlarmsInQueryRange() {
        GlobalTopologyQueryService queryService = Mockito.mock(GlobalTopologyQueryService.class);
        when(queryService.listEdges(anyLong(), anyLong(), anyInt())).thenReturn(List.of(
                new ServiceFlowEdge("order", "pay", 100, 0, 12.5, "order-id", "pay-id")));

        AlarmStore alarmStore = mock(AlarmStore.class);
        Instant triggeredAt = Instant.ofEpochMilli(1_800_000L);
        when(alarmStore.listInTimeRange(any(), any())).thenReturn(List.of(
                new Alarm("a1", 0L, "order", "threshold", "critical", "alarm", "open", triggeredAt, null),
                new Alarm("a2", 0L, "order", "threshold", "warning", "alarm", "resolved", triggeredAt, triggeredAt)));

        GlobalTopologyPortalService service = service(queryService, alarmStore);
        Map<String, Object> data = service.graph(Map.of("fromTime", 0L, "toTime", 3_600_000L));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services = (List<Map<String, Object>>) data.get("services");
        String orderId = PortalServiceIdResolver.normalize("order-id");
        Map<String, Object> orderNode = services.stream()
                .filter(node -> orderId.equals(node.get("id")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> payNode = services.stream()
                .filter(node -> PortalServiceIdResolver.normalize("pay-id").equals(node.get("id")))
                .findFirst()
                .orElseThrow();

        assertThat(orderNode).containsEntry("alarmCount", 2L).containsEntry("errType", 1);
        assertThat(payNode).containsEntry("alarmCount", 0L).containsEntry("errType", 0);
    }

    @Test
    void doesNotMarkNodesWithHighErrorRateWhenNoAlarm() {
        GlobalTopologyQueryService queryService = Mockito.mock(GlobalTopologyQueryService.class);
        when(queryService.listEdges(anyLong(), anyLong(), anyInt())).thenReturn(List.of(
                new ServiceFlowEdge("order", "pay", 100, 10, 12.5, "order-id", "pay-id")));

        GlobalTopologyPortalService service = service(queryService, emptyAlarmStore());
        Map<String, Object> data = service.graph(Map.of("fromTime", 0L, "toTime", 3_600_000L));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services = (List<Map<String, Object>>) data.get("services");
        String orderId = PortalServiceIdResolver.normalize("order-id");
        Map<String, Object> orderNode = services.stream()
                .filter(node -> orderId.equals(node.get("id")))
                .findFirst()
                .orElseThrow();

        assertThat(orderNode).containsEntry("errRate", 0.1);
        assertThat(orderNode).containsEntry("alarmCount", 0L).containsEntry("errType", 0);
    }
}
