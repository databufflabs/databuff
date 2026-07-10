package com.databuff.apm.ingest.doris;

import com.databuff.apm.ingest.component.AggregateComponent;
import com.databuff.apm.common.storage.DorisStreamLoadSink;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DorisFlushSchedulerTest {

    @Test
    void flushesAllSinksIncludingPartialBatches() throws IOException {
        AggregateComponent aggregateComponent = mock(AggregateComponent.class);
        DorisStreamLoadSink metricSink = mock(DorisStreamLoadSink.class);
        DorisStreamLoadSink traceSink = mock(DorisStreamLoadSink.class);
        when(metricSink.flushReady()).thenReturn(2);
        when(metricSink.flushAll()).thenReturn(0);
        when(traceSink.flushReady()).thenReturn(0);
        when(traceSink.flushAll()).thenReturn(1);
        when(metricSink.database()).thenReturn("databuff");
        when(metricSink.table()).thenReturn("metric_service_http");
        when(traceSink.table()).thenReturn("trace_dc_span");
        when(traceSink.database()).thenReturn("databuff");

        new DorisFlushScheduler(aggregateComponent, null, List.of(metricSink, traceSink), 45_000L).flush();

        verify(aggregateComponent).flushPendingMetrics();
        verify(metricSink).flushReady();
        verify(metricSink).flushAll();
        verify(traceSink).flushReady();
        verify(traceSink).flushAll();
    }

    @Test
    void flushMetricsSkipsTraceTable() throws IOException {
        AggregateComponent aggregateComponent = mock(AggregateComponent.class);
        DorisStreamLoadSink metricSink = mock(DorisStreamLoadSink.class);
        DorisStreamLoadSink traceSink = mock(DorisStreamLoadSink.class);
        when(metricSink.table()).thenReturn("metric_service_http");
        when(metricSink.database()).thenReturn("databuff");
        when(traceSink.table()).thenReturn("trace_dc_span");
        when(metricSink.flushReady()).thenReturn(1);
        when(metricSink.flushAll()).thenReturn(0);

        new DorisFlushScheduler(aggregateComponent, null, List.of(metricSink, traceSink), 45_000L).flushMetrics();

        verify(aggregateComponent).flushPendingMetrics();
        verify(metricSink).flushReady();
        verify(metricSink).flushAll();
        verify(traceSink, never()).flushReady();
    }

    @Test
    void logsWarningWhenFlushFails() throws IOException {
        AggregateComponent aggregateComponent = mock(AggregateComponent.class);
        DorisStreamLoadSink sink = mock(DorisStreamLoadSink.class);
        when(sink.flushReady()).thenThrow(new IOException("connection reset"));
        when(sink.database()).thenReturn("databuff");
        when(sink.table()).thenReturn("dc_span");

        new DorisFlushScheduler(aggregateComponent, null, List.of(sink), 45_000L).flush();

        verify(aggregateComponent).flushPendingMetrics();
        verify(sink).flushReady();
    }
}
