package com.databuff.apm.common.storage;

import com.databuff.apm.common.query.ApmQueryModels;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TraceSpanMetricDrilldownServiceTest {

    @Test
    void queriesDcSpanDirectlyWithoutMetricProbe() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        when(reader.querySpanSummaries(anyString())).thenReturn(java.util.List.of(
                new ApmQueryModels.SpanSummary(
                        "t1", "s1", "checkout", null, "GET", "2026-06-01", 1, 0, "", "GET", "", null, null)));

        TraceSpanMetricDrilldownService service =
                new TraceSpanMetricDrilldownService(reader, "databuff");
        assertThat(service.spanList("checkout", null, 0, 1000, 20, 0, null, null, null, null, null, null))
                .hasSize(1);
        verify(reader, never()).queryErrorRate(anyString());
        verify(reader).querySpanSummaries(anyString());
    }

    @Test
    void queriesDcSpanWhenServiceBlank() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        when(reader.querySpanSummaries(anyString())).thenReturn(java.util.List.of());

        TraceSpanMetricDrilldownService service =
                new TraceSpanMetricDrilldownService(reader, "databuff");
        service.spanList(null, null, 0, 1000, 20, 0, null, null, null, null, null, null);
        verify(reader, never()).queryErrorRate(anyString());
        verify(reader).querySpanSummaries(anyString());
    }

    @Test
    void spanListPassesPortalDatetimeTextToSql() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        when(reader.querySpanSummaries(anyString())).thenReturn(List.of());

        TraceSpanMetricDrilldownService service =
                new TraceSpanMetricDrilldownService(reader, "databuff");
        service.spanList(
                null,
                null,
                0,
                1000,
                20,
                0,
                "2026-06-05 14:00:00",
                "2026-06-05 14:01:00",
                null,
                null,
                null,
                null);

        verify(reader).querySpanSummaries(argThat(sql ->
                sql.contains("(FLOOR(`end` / 1000000 / 60000) * 60000) >= ")
                        && sql.contains("(FLOOR(`end` / 1000000 / 60000) * 60000) < ")
                        && !sql.contains("`startTime` >=")));
    }
}
