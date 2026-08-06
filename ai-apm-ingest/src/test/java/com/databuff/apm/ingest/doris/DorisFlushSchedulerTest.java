package com.databuff.apm.ingest.doris;

import com.databuff.apm.ingest.component.AggregateComponent;
import com.databuff.apm.common.storage.DorisStreamLoadSink;
import com.databuff.apm.common.storage.DorisTableNames;
import com.databuff.apm.ingest.meta.MetaServiceCollector;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DorisFlushSchedulerTest {

    @Test
    void timeFallbackFlushesPartialBatches() throws IOException {
        AggregateComponent aggregateComponent = mock(AggregateComponent.class);
        DorisStreamLoadSink metricSink = mock(DorisStreamLoadSink.class);
        DorisStreamLoadSink traceSink = mock(DorisStreamLoadSink.class);
        when(metricSink.flushAll()).thenReturn(2);
        when(traceSink.flushAll()).thenReturn(1);
        when(metricSink.database()).thenReturn("databuff");
        when(metricSink.table()).thenReturn("metric_service_http");
        when(traceSink.table()).thenReturn("trace_dc_span");
        when(traceSink.database()).thenReturn("databuff");

        withScheduler(aggregateComponent, List.of(metricSink, traceSink), DorisFlushScheduler::flush);

        verify(aggregateComponent).flushPendingMetrics();
        verify(metricSink).flushAll();
        verify(metricSink, never()).flushReady();
        verify(traceSink).flushAll();
        verify(traceSink, never()).flushReady();
    }

    @Test
    void flushBySizeOnlyCallsFlushReadyOnSinksWithReadyBatches() throws IOException {
        AggregateComponent aggregateComponent = mock(AggregateComponent.class);
        DorisStreamLoadSink metricSink = mock(DorisStreamLoadSink.class);
        DorisStreamLoadSink traceSink = mock(DorisStreamLoadSink.class);
        when(metricSink.table()).thenReturn("metric_service_http");
        when(metricSink.database()).thenReturn("databuff");
        when(traceSink.table()).thenReturn("trace_dc_span");
        when(traceSink.database()).thenReturn("databuff");
        when(metricSink.hasReady()).thenReturn(true);
        when(traceSink.hasReady()).thenReturn(false);
        when(metricSink.flushReady()).thenReturn(1);

        withScheduler(aggregateComponent, List.of(metricSink, traceSink), DorisFlushScheduler::flushBySize);

        verify(metricSink).hasReady();
        verify(traceSink).hasReady();
        verify(metricSink).flushReady();
        verify(traceSink, never()).flushReady();
        verify(metricSink, never()).flushAll();
        verify(traceSink, never()).flushAll();
        verify(aggregateComponent, never()).flushPendingMetrics();
    }

    @Test
    void flushBySizeSkipsAllIdleSinks() throws IOException {
        AggregateComponent aggregateComponent = mock(AggregateComponent.class);
        DorisStreamLoadSink metricSink = mock(DorisStreamLoadSink.class);
        DorisStreamLoadSink traceSink = mock(DorisStreamLoadSink.class);
        when(metricSink.hasReady()).thenReturn(false);
        when(traceSink.hasReady()).thenReturn(false);

        withScheduler(aggregateComponent, List.of(metricSink, traceSink), DorisFlushScheduler::flushBySize);

        verify(metricSink, never()).flushReady();
        verify(traceSink, never()).flushReady();
        verify(metricSink, never()).flushAll();
        verify(traceSink, never()).flushAll();
    }

    @Test
    void flushMetricsSkipsTraceTable() throws IOException {
        AggregateComponent aggregateComponent = mock(AggregateComponent.class);
        DorisStreamLoadSink metricSink = mock(DorisStreamLoadSink.class);
        DorisStreamLoadSink traceSink = mock(DorisStreamLoadSink.class);
        when(metricSink.table()).thenReturn("metric_service_http");
        when(metricSink.database()).thenReturn("databuff");
        when(traceSink.table()).thenReturn("trace_dc_span");
        when(metricSink.flushAll()).thenReturn(1);

        withScheduler(aggregateComponent, List.of(metricSink, traceSink), DorisFlushScheduler::flushMetrics);

        verify(aggregateComponent).flushPendingMetrics();
        verify(metricSink).flushAll();
        verify(traceSink, never()).flushAll();
        verify(traceSink, never()).flushReady();
    }

    @Test
    void logsWarningWhenFlushFails() throws IOException {
        AggregateComponent aggregateComponent = mock(AggregateComponent.class);
        DorisStreamLoadSink sink = mock(DorisStreamLoadSink.class);
        when(sink.flushAll()).thenThrow(new IOException("connection reset"));
        when(sink.database()).thenReturn("databuff");
        when(sink.table()).thenReturn("dc_span");

        withScheduler(aggregateComponent, List.of(sink), DorisFlushScheduler::flush);

        verify(aggregateComponent).flushPendingMetrics();
        verify(sink).flushAll();
    }

    @Test
    void publicConstructorOwnsDedicatedExecutorAndDestroysCleanly() {
        DorisFlushScheduler scheduler = new DorisFlushScheduler(
                mock(AggregateComponent.class), null, List.of(), 10_000L);
        scheduler.destroy();
    }

    @Test
    void testConstructorDoesNotOwnExecutor() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            DorisFlushScheduler scheduler = new DorisFlushScheduler(
                    mock(AggregateComponent.class), null, List.of(), 45_000L, executor);
            scheduler.destroy();
            assertThat(executor.isShutdown()).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void logsWarningWhenFlushTimesOut() {
        AggregateComponent aggregateComponent = mock(AggregateComponent.class);
        DorisStreamLoadSink sink = mock(DorisStreamLoadSink.class);
        when(sink.hasReady()).thenReturn(true);
        when(sink.table()).thenReturn("dc_span");
        when(sink.database()).thenReturn("databuff");
        ExecutorService stalled = mock(ExecutorService.class);

        DorisFlushScheduler scheduler = new DorisFlushScheduler(
                aggregateComponent, null, List.of(sink), 5_000L, stalled);
        scheduler.flushBySize();

        verify(sink).hasReady();
    }

    @Test
    void restoresInterruptFlagWhenFlushInterrupted() {
        AggregateComponent aggregateComponent = mock(AggregateComponent.class);
        DorisStreamLoadSink sink = mock(DorisStreamLoadSink.class);
        when(sink.hasReady()).thenReturn(true);
        when(sink.table()).thenReturn("dc_span");
        when(sink.database()).thenReturn("databuff");
        ExecutorService stalled = mock(ExecutorService.class);
        DorisFlushScheduler scheduler = new DorisFlushScheduler(
                aggregateComponent, null, List.of(sink), 5_000L, stalled);

        Thread.currentThread().interrupt();
        scheduler.flushBySize();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted();
    }

    @Test
    void flushMetricsStagesMetaServicesAndCompletesMetaSink() throws IOException {
        AggregateComponent aggregateComponent = mock(AggregateComponent.class);
        MetaServiceCollector metaServiceCollector = mock(MetaServiceCollector.class);
        DorisStreamLoadSink metaSink = mock(DorisStreamLoadSink.class);
        when(metaSink.table()).thenReturn(DorisTableNames.META_SERVICE);
        when(metaSink.database()).thenReturn("databuff");
        when(metaSink.flushAll()).thenReturn(3);

        withScheduler(aggregateComponent, List.of(metaSink), metaServiceCollector, DorisFlushScheduler::flushMetrics);

        verify(metaServiceCollector).stagePending();
        verify(aggregateComponent).flushPendingMetrics();
        verify(metaSink).flushAll();
        verify(metaServiceCollector).onFlushComplete();
    }

    @Test
    void updateQueueGaugesSkipsPlatformSelfMetricSink() {
        AggregateComponent aggregateComponent = mock(AggregateComponent.class);
        DorisStreamLoadSink platformSink = mock(DorisStreamLoadSink.class);
        when(platformSink.table()).thenReturn(DorisTableNames.METRIC_PLATFORM);
        when(platformSink.database()).thenReturn("databuff");

        withScheduler(aggregateComponent, List.of(platformSink), DorisFlushScheduler::flushBySize);

        verify(platformSink, never()).pendingCount();
        verify(platformSink, never()).maxReadyBatches();
    }

    private static void withScheduler(
            AggregateComponent aggregateComponent,
            List<DorisStreamLoadSink> sinks,
            SchedulerAction action) {
        withScheduler(aggregateComponent, sinks, null, action);
    }

    private static void withScheduler(
            AggregateComponent aggregateComponent,
            List<DorisStreamLoadSink> sinks,
            MetaServiceCollector metaServiceCollector,
            SchedulerAction action) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            action.run(new DorisFlushScheduler(aggregateComponent, metaServiceCollector, sinks, 45_000L, executor));
        } finally {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface SchedulerAction {
        void run(DorisFlushScheduler scheduler);
    }
}
