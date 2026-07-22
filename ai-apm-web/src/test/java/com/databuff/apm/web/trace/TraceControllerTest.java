package com.databuff.apm.web.trace;

import com.databuff.apm.web.TestStorageSupport;

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

class TraceControllerTest {

    @Test
    void delegatesSpanList() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryErrorRate(anyString())).thenReturn(new ErrorRateSnapshot(0, 0));
        when(reader.querySpanSummaries(anyString())).thenReturn(List.of());
        TraceController controller = new TraceController(new TraceQueryService(reader, TestStorageSupport.storage()));
        assertThat(controller.spanList(new TraceQueryService.SpanListRequest("demo", 0, 1, 10))).isEmpty();
    }

    @Test
    void delegatesTraceDetail() {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        TraceController controller = new TraceController(new TraceQueryService(reader, TestStorageSupport.storage()));
        assertThat(controller.traceDetail(new TraceQueryService.TraceDetailRequest("t1"))).isEmpty();
    }

    @Test
    void delegatesServiceInstanceCounts() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryIntMap(anyString(), eq("map_key"), eq("map_value")))
                .thenReturn(Map.of("demo-order", 2));
        TraceController controller = new TraceController(new TraceQueryService(reader, TestStorageSupport.storage()));
        Map<String, Integer> counts = controller.serviceInstanceCounts(
                new TraceQueryService.SpanListRequest(null, 0, 1000, 20));
        assertThat(counts).containsEntry("demo-order", 2);
    }
}
