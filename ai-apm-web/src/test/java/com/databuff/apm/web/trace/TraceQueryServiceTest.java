package com.databuff.apm.web.trace;

import com.databuff.apm.web.TestStorageSupport;

import com.databuff.apm.common.query.ApmQueryModels.SpanSummary;
import com.databuff.apm.common.query.ApmQueryModels.SpanDetail;
import com.databuff.apm.common.query.ApmQueryModels.ErrorRateSnapshot;

import com.databuff.apm.common.storage.ApmReadRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceQueryServiceTest {

    @Test
    void returnsSpansFromDoris() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryErrorRate(anyString())).thenReturn(new ErrorRateSnapshot(0, 5));
        when(reader.querySpanSummaries(anyString())).thenReturn(List.of(
                new SpanSummary("t1", "s1", "checkout", null, "GET /", "2026-06-01 12:00:00", 10, 0, "", "GET /", "", null, null)));

        TraceQueryService service = new TraceQueryService(reader, TestStorageSupport.storage());
        assertThat(service.spanList(new TraceQueryService.SpanListRequest("checkout", 0, 1000, 20))).hasSize(1);
    }

    @Test
    void propagatesWhenDorisFails() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.querySpanSummaries(anyString())).thenThrow(new RuntimeException("down"));
        TraceQueryService service = new TraceQueryService(reader, TestStorageSupport.storage());
        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> service.spanList(new TraceQueryService.SpanListRequest(null, 0, 1000, 20)));
    }

    @Test
    void appliesDefaultLimit() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryErrorRate(anyString())).thenReturn(new ErrorRateSnapshot(0, 0));
        when(reader.querySpanSummaries(anyString())).thenReturn(List.of());
        TraceQueryService service = new TraceQueryService(reader, TestStorageSupport.storage());
        assertThat(service.spanList(new TraceQueryService.SpanListRequest("svc", 0, 1000, 0))).isEmpty();
    }

    @Test
    void returnsTraceDetailFromDoris() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.querySpanDetails(anyString())).thenReturn(List.of(
                new SpanDetail("t1", "s1", "0", "checkout", null, "GET /", "2026-06-01 12:00:00", 1_748_784_000_000_000_000L, 10, 0, "host-a")));

        TraceQueryService service = new TraceQueryService(reader, TestStorageSupport.storage());
        assertThat(service.traceDetail(new TraceQueryService.TraceDetailRequest("t1"))).hasSize(1);
    }

    @Test
    void propagatesTraceDetailWhenStoreFails() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.querySpanDetails(anyString())).thenThrow(new RuntimeException("down"));
        TraceQueryService service = new TraceQueryService(reader, TestStorageSupport.storage());
        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> service.traceDetail(new TraceQueryService.TraceDetailRequest("t1")));
    }

    @Test
    void returnsServiceInstancesFromDoris() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryTopGroups(anyString())).thenReturn(List.of("inst-a", "inst-b"));

        TraceQueryService service = new TraceQueryService(reader, TestStorageSupport.storage());
        assertThat(service.serviceInstances(new TraceQueryService.SpanListRequest("checkout", 0, 1000, 20)))
                .containsExactly("inst-a", "inst-b");
    }

    @Test
    void returnsEmptyServiceInstancesWhenServiceBlank() {
        TraceQueryService service = new TraceQueryService(mock(ApmReadRepository.class), TestStorageSupport.storage());
        assertThat(service.serviceInstances(new TraceQueryService.SpanListRequest(" ", 0, 1000, 20))).isEmpty();
    }

    @Test
    void returnsK8sNamespacesFromDoris() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryTopGroups(anyString())).thenReturn(List.of("demo", "prod"));

        TraceQueryService service = new TraceQueryService(reader, TestStorageSupport.storage());
        assertThat(service.k8sNamespaces(new TraceQueryService.SpanListRequest(null, 0, 1000, 20)))
                .containsExactly("demo", "prod");
    }

    @Test
    void returnsServiceK8sNamespaceMapFromDoris() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryStringMap(anyString(), eq("map_key"), eq("map_value")))
                .thenReturn(Map.of("demo-order", "demo"));

        TraceQueryService service = new TraceQueryService(reader, TestStorageSupport.storage());
        assertThat(service.serviceK8sNamespaces(new TraceQueryService.SpanListRequest(null, 0, 1000, 20)))
                .containsEntry("demo-order", "demo");
    }

    @Test
    void returnsServiceInstanceCountsFromDoris() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryIntMap(anyString(), eq("map_key"), eq("map_value")))
                .thenReturn(Map.of("demo-order", 2, "demo-pay", 1));

        TraceQueryService service = new TraceQueryService(reader, TestStorageSupport.storage());
        assertThat(service.serviceInstanceCounts(new TraceQueryService.SpanListRequest(null, 0, 1000, 20)))
                .containsEntry("demo-order", 2)
                .containsEntry("demo-pay", 1);
    }

    @Test
    void returnsEmptyTraceDetailWhenBlankId() {
        TraceQueryService service = new TraceQueryService(mock(ApmReadRepository.class), TestStorageSupport.storage());
        assertThat(service.traceDetail(new TraceQueryService.TraceDetailRequest("  "))).isEmpty();
    }
}
