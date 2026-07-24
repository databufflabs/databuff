package com.databuff.apm.ingest.doris;

import com.databuff.apm.ingest.meta.MetaServiceCollector;
import com.databuff.apm.ingest.component.AggregateComponent;
import com.databuff.apm.common.storage.DorisStreamLoadSink;
import com.databuff.apm.common.storage.DorisTableNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.databuff.apm.ingest.support.LogRateLimiter;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class DorisFlushScheduler implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DorisFlushScheduler.class);
    private static final LogRateLimiter FLUSH_FAILURE_LIMITER = new LogRateLimiter(10_000L);
    private static final java.util.Set<String> PARTITIONED_TABLES = java.util.Set.of(
            DorisTableNames.TRACE_DC_SPAN,
            DorisTableNames.LOG_DC_RECORD);
    private static final AtomicInteger FLUSH_THREAD_SEQ = new AtomicInteger();

    private final AggregateComponent aggregateComponent;
    private final MetaServiceCollector metaServiceCollector;
    private final List<DorisStreamLoadSink> sinks;
    private final long flushTimeoutMs;
    private final ExecutorService flushExecutor;
    private final boolean ownsFlushExecutor;

    @Autowired
    public DorisFlushScheduler(
            AggregateComponent aggregateComponent,
            MetaServiceCollector metaServiceCollector,
            @Qualifier("dorisStreamLoadSinks") List<DorisStreamLoadSink> sinks,
            @Value("${ingest.doris.flush-timeout-ms:60000}") long flushTimeoutMs) {
        this(
                aggregateComponent,
                metaServiceCollector,
                sinks,
                flushTimeoutMs,
                newDedicatedFlushExecutor(),
                true);
    }

    /** Visible for tests. */
    DorisFlushScheduler(
            AggregateComponent aggregateComponent,
            MetaServiceCollector metaServiceCollector,
            List<DorisStreamLoadSink> sinks,
            long flushTimeoutMs,
            ExecutorService flushExecutor) {
        this(aggregateComponent, metaServiceCollector, sinks, flushTimeoutMs, flushExecutor, false);
    }

    private DorisFlushScheduler(
            AggregateComponent aggregateComponent,
            MetaServiceCollector metaServiceCollector,
            List<DorisStreamLoadSink> sinks,
            long flushTimeoutMs,
            ExecutorService flushExecutor,
            boolean ownsFlushExecutor) {
        this.aggregateComponent = aggregateComponent;
        this.metaServiceCollector = metaServiceCollector;
        this.sinks = List.copyOf(sinks);
        this.flushTimeoutMs = Math.max(5_000L, flushTimeoutMs);
        this.flushExecutor = flushExecutor;
        this.ownsFlushExecutor = ownsFlushExecutor;
        log.info(
                "Doris flush scheduler ready sinks={} tables={}",
                this.sinks.size(),
                this.sinks.stream().map(DorisStreamLoadSink::table).toList());
    }

    private static ExecutorService newDedicatedFlushExecutor() {
        // Scheduler waits on each Future sequentially; small pool avoids ForkJoin commonPool
        // contention while still covering overlapping @Scheduled ticks.
        int threads = Math.min(4, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "doris-flush-sched-" + FLUSH_THREAD_SEQ.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(threads, factory);
    }

    /**
     * Safety drain of the ready queue. Primary size flush is async via
     * {@link com.databuff.apm.common.storage.DorisBatchWriter.ReadyListener} when a thread-local
     * buffer hands off; this tick only touches sinks that still have ready batches (e.g. async
     * worker was busy / rejected).
     */
    @Scheduled(fixedDelayString = "${ingest.doris.flush-check-interval-ms:5000}")
    public void flushBySize() {
        for (DorisStreamLoadSink sink : sinks) {
            if (!sink.hasReady()) {
                continue;
            }
            flushSink(sink, DorisStreamLoadSink::flushReady);
        }
    }

    /**
     * Time fallback: rotate idle thread-local buffers and Stream Load remaining rows.
     */
    @Scheduled(fixedDelayString = "${ingest.doris.flush-interval-ms:30000}")
    public void flush() {
        flushMetrics();
        for (DorisStreamLoadSink sink : sinks) {
            if (PARTITIONED_TABLES.contains(sink.table())) {
                flushSink(sink, DorisStreamLoadSink::flushAll);
            }
        }
    }

    /** Flush trace-derived and OTLP metrics without blocking on partitioned trace/log tables. */
    public void flushMetrics() {
        stageMetaServices();
        aggregateComponent.flushPendingMetrics();
        for (DorisStreamLoadSink sink : sinks) {
            if (!PARTITIONED_TABLES.contains(sink.table())) {
                flushSink(sink, DorisStreamLoadSink::flushAll);
            }
        }
    }

    private void stageMetaServices() {
        if (metaServiceCollector != null) {
            metaServiceCollector.stagePending();
        }
    }

    private void flushSink(DorisStreamLoadSink sink, FlushAction action) {
        try {
            int rows = CompletableFuture.supplyAsync(() -> {
                try {
                    return action.flush(sink);
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
            }, flushExecutor).get(flushTimeoutMs, TimeUnit.MILLISECONDS);
            if (rows > 0) {
                if (metaServiceCollector != null && DorisTableNames.META_SERVICE.equals(sink.table())) {
                    metaServiceCollector.onFlushComplete();
                }
            }
        } catch (TimeoutException e) {
            warnFlushFailure(
                    "Doris flush timed out for {}.{} (>{}ms) — check DORIS_BE_HTTP_HOST / BE port",
                    sink.database(),
                    sink.table(),
                    flushTimeoutMs);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String message = cause != null ? cause.getMessage() : e.getMessage();
            warnFlushFailure("Doris flush failed for {}.{}: {}", sink.database(), sink.table(), message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void warnFlushFailure(String template, Object... args) {
        if (log.isDebugEnabled()) {
            log.debug(template, args);
        }
        long count = FLUSH_FAILURE_LIMITER.record();
        if (count > 0) {
            log.warn("Doris flush failures: {} in last 10s", count);
        }
    }

    @Override
    public void destroy() {
        if (!ownsFlushExecutor) {
            return;
        }
        flushExecutor.shutdown();
        try {
            if (!flushExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                flushExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            flushExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface FlushAction {
        int flush(DorisStreamLoadSink sink) throws IOException;
    }
}
