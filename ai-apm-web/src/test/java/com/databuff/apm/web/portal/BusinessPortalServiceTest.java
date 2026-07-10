package com.databuff.apm.web.portal;

import com.databuff.apm.common.query.ApmQueryModels.ServiceSummaryPoint;
import com.databuff.apm.common.query.ApmQueryModels.TopologyEdge;
import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.web.TestStorageSupport;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BusinessPortalServiceTest {

    @Test
    void aggregatesBusinessCallInfo() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        GlobalTopologyQueryService topology = Mockito.mock(GlobalTopologyQueryService.class);
        when(topology.listTopologyEdges(anyLong(), anyLong(), anyInt())).thenReturn(List.of(
                new TopologyEdge("demo-order", "demo-pay", 10, 1)));
        when(reader.queryServiceSummaries(anyString())).thenReturn(List.of(
                new ServiceSummaryPoint("demo-order", "demo-order", 10, 0, 50_000_000, 0),
                new ServiceSummaryPoint("demo-pay", "demo-pay", 8, 0, 40_000_000, 0)));

        BusinessPortalService service = new BusinessPortalService(reader, TestStorageSupport.storage(), topology);
        Map<String, Object> info = service.callInfo(Map.of(
                "srcServiceId", "demo-order",
                "dstServiceId", "demo-pay",
                "fromTime", "2026-06-01 11:00:00",
                "toTime", "2026-06-01 13:00:00"));

        assertThat(info.get("reqOutCnt")).isEqualTo(10L);
        assertThat(info.get("reqOutErrCnt")).isEqualTo(1L);
        assertThat(info.get("reqOutAvgLatency")).isEqualTo(5_000_000L);
    }

    @Test
    void paginatesBusinessCallEndpoints() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        GlobalTopologyQueryService topology = Mockito.mock(GlobalTopologyQueryService.class);
        when(topology.listTopologyEdges(anyLong(), anyLong(), anyInt())).thenReturn(List.of(
                new TopologyEdge("demo-order", "demo-pay", 10, 1),
                new TopologyEdge("demo-order", "demo-inventory", 5, 0)));

        BusinessPortalService service = new BusinessPortalService(reader, TestStorageSupport.storage(), topology);
        Map<String, Object> resp = service.callEndpoints(Map.of(
                "srcServiceId", "demo-order",
                "fromTime", "2026-06-01 11:00:00",
                "toTime", "2026-06-01 13:00:00",
                "offset", 0,
                "size", 1));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("data");
        assertThat(rows).hasSize(1);
        assertThat(resp.get("total")).isEqualTo(2);
    }
}
