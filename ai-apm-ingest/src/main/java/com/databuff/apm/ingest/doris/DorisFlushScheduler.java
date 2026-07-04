package com.databuff.apm.ingest.doris;

import com.databuff.apm.ingest.meta.MetaServiceCollector;
import com.databuff.apm.ingest.component.AggregateComponent;
import com.databuff.apm.common.storage.DorisStreamLoadSink;
import com.databuff.apm.common.storage.DorisTableNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class DorisFlushScheduler {

    private static final Logger log = LoggerFactory.getLogger(DorisFlushScheduler.class);
    private static final java.util.Set<String> PARTITIONED_TABLES = java.util.Set.of(
            DorisTableNames.TRACE_DC_SPAN,
            DorisTableNames.LOG_DC_RECORD);

    private final AggregateComponent aggregateComponent;
    private final MetaServiceCollector metaServiceCollector;
    private final List<DorisStreamLoadSink> sinks;
    private final long flushTimeoutMs;

    public DorisFlushScheduler(
            AggregateComponent aggregateComponent,
            MetaServiceCollector metaServiceCollector,
            @Qualifier("dorisStreamLoadSinks") List<DorisStreamLoadSink> sinks,
            @Value("${ingest.doris.flush-timeout-ms:45000}") long flushTimeoutMs) {
        this.aggregateComponent = aggregateComponent;
        this.metaServiceCollector = metaServiceCollector;
        this.sinks = List.copyOf(sinks);
        this.flushTimeoutMs = Math.max(5_000L, flushTimeoutMs);
        log.info(
                "Doris flush scheduler ready sinks={} tables={}",
                this.sinks.size(),
                this.sinks.stream().map(DorisStreamLoadSink::table).toList());
    }

    @Scheduled(fixedDelayString = "${ingest.doris.flush-interval-ms:5000}")
    public void flush() {
        flushMetrics();
        for (DorisStreamLoadSink sink : sinks) {
            if (PARTITIONED_TABLES.contains(sink.table())) {
                flushSink(sink);
            }
        }
    }

    /** Flush trace-derived and OTLP metrics without blocking on partitioned trace/log tables. */
    public void flushMetrics() {
        stageMetaServices();
        aggregateComponent.flushPendingMetrics();
        for (DorisStreamLoadSink sink : sinks) {
            if (!PARTITIONED_TABLES.contains(sink.table())) {
                flushSink(sink);
            }
        }
    }

    private void stageMetaServices() {
        if (metaServiceCollector != null) {
            metaServiceCollector.stagePending();
        }
    }

    private void flushSink(DorisStreamLoadSink sink) {
        try {
            int rows = CompletableFuture.supplyAsync(() -> {
                try {
                    int ready = sink.flushReady();
                    return ready + sink.flushAll();
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
            }).get(flushTimeoutMs, TimeUnit.MILLISECONDS);
            if (rows > 0) {
                if (metaServiceCollector != null && DorisTableNames.META_SERVICE.equals(sink.table())) {
                    metaServiceCollector.onFlushComplete();
                }
            }
        } catch (TimeoutException e) {
            log.warn(
                    "Doris flush timed out for {}.{} (>{}ms) — check DORIS_BE_HTTP_HOST / BE port",
                    sink.database(),
                    sink.table(),
                    flushTimeoutMs);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String message = cause != null ? cause.getMessage() : e.getMessage();
            log.warn("Doris flush failed for {}.{}: {}", sink.database(), sink.table(), message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
