package com.databuff.apm.common.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Flushes {@link DorisBatchWriter} ready batches via {@link DorisStreamLoader}.
 * <p>
 * Size/time hand-off happens inside the writer (thread-local buffers → ready queue).
 * This sink only drains the ready queue and performs HTTP Stream Load off the offer path.
 * A {@link DorisBatchWriter.ReadyListener} schedules async drain workers when a batch is
 * handed off so full buffers do not wait for the time-fallback scheduler.
 * <p>
 * {@code flushConcurrency} caps how many Stream Loads for this sink may run at once.
 * With {@code 1} (default), one worker drains the ready queue sequentially. With
 * {@code K > 1}, up to {@code K} workers each take one batch and load in parallel.
 * <p>
 * On Stream Load failure the batch is re-queued onto the ready queue; after
 * {@link #DEFAULT_MAX_CONSECUTIVE_FAILURES} consecutive failures for this sink the batch is
 * dropped (fail-soft).
 */
public final class DorisStreamLoadSink {

    private static final Logger log = LoggerFactory.getLogger(DorisStreamLoadSink.class);

    /** Default consecutive Stream Load failures before dropping the current batch. */
    public static final int DEFAULT_MAX_CONSECUTIVE_FAILURES = 3;

    /** Default in-flight Stream Loads per sink (sequential drain). */
    public static final int DEFAULT_FLUSH_CONCURRENCY = 1;

    private static final AtomicInteger FLUSH_THREAD_SEQ = new AtomicInteger();
    /** Shared across sinks; sized for multi-table + multi-batch parallel loads. */
    private static final Executor SHARED_FLUSH_EXECUTOR = Executors.newFixedThreadPool(
            Math.max(8, Runtime.getRuntime().availableProcessors()),
            daemonThreadFactory("doris-stream-load"));

    private final DorisBatchWriter batchWriter;
    private final DorisStreamLoader streamLoader;
    private final String database;
    private final String table;
    private final int maxConsecutiveFailures;
    private final int flushConcurrency;
    private final Executor flushExecutor;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    public DorisStreamLoadSink(
            DorisBatchWriter batchWriter,
            DorisStreamLoader streamLoader,
            String database,
            String table) {
        this(batchWriter, streamLoader, database, table, DEFAULT_MAX_CONSECUTIVE_FAILURES);
    }

    public DorisStreamLoadSink(
            DorisBatchWriter batchWriter,
            DorisStreamLoader streamLoader,
            String database,
            String table,
            int maxConsecutiveFailures) {
        this(batchWriter, streamLoader, database, table, maxConsecutiveFailures, DEFAULT_FLUSH_CONCURRENCY);
    }

    public DorisStreamLoadSink(
            DorisBatchWriter batchWriter,
            DorisStreamLoader streamLoader,
            String database,
            String table,
            int maxConsecutiveFailures,
            int flushConcurrency) {
        this(
                batchWriter,
                streamLoader,
                database,
                table,
                maxConsecutiveFailures,
                SHARED_FLUSH_EXECUTOR,
                flushConcurrency);
    }

    DorisStreamLoadSink(
            DorisBatchWriter batchWriter,
            DorisStreamLoader streamLoader,
            String database,
            String table,
            int maxConsecutiveFailures,
            Executor flushExecutor) {
        this(
                batchWriter,
                streamLoader,
                database,
                table,
                maxConsecutiveFailures,
                flushExecutor,
                DEFAULT_FLUSH_CONCURRENCY);
    }

    DorisStreamLoadSink(
            DorisBatchWriter batchWriter,
            DorisStreamLoader streamLoader,
            String database,
            String table,
            int maxConsecutiveFailures,
            Executor flushExecutor,
            int flushConcurrency) {
        this.batchWriter = Objects.requireNonNull(batchWriter);
        this.streamLoader = Objects.requireNonNull(streamLoader);
        this.database = Objects.requireNonNull(database);
        this.table = Objects.requireNonNull(table);
        this.maxConsecutiveFailures = Math.max(1, maxConsecutiveFailures);
        this.flushConcurrency = Math.max(1, flushConcurrency);
        this.flushExecutor = Objects.requireNonNull(flushExecutor);
        this.batchWriter.setReadyListener(this::scheduleFlushReady);
    }

    /**
     * Drain handed-off batches from the ready queue (size/time already decided by the writer).
     * Loads sequentially; async path uses {@link #flushConcurrency} for parallelism.
     */
    public int flushReady() throws IOException {
        int total = 0;
        List<DorisJsonRow> batch;
        while (!(batch = batchWriter.drainIfReady()).isEmpty()) {
            total += loadBatch(batch);
        }
        return total;
    }

    /**
     * Time fallback: rotate every thread-local buffer into the ready queue, then Stream Load.
     */
    public int flushAll() throws IOException {
        batchWriter.rotateAll();
        return flushReady();
    }

    /**
     * Schedule async Stream Load workers up to {@link #flushConcurrency}.
     * Safe to call from the offer path (ready-listener).
     */
    private void scheduleFlushReady() {
        while (true) {
            if (!batchWriter.hasReady()) {
                return;
            }
            int current = inFlight.get();
            if (current >= flushConcurrency) {
                return;
            }
            if (!inFlight.compareAndSet(current, current + 1)) {
                continue;
            }
            try {
                flushExecutor.execute(this::runFlushWorker);
            } catch (RejectedExecutionException e) {
                inFlight.decrementAndGet();
                log.debug(
                        "Doris async flush rejected for {}.{} (executor shutting down?): {}",
                        database,
                        table,
                        e.toString());
                return;
            }
        }
    }

    private void runFlushWorker() {
        try {
            if (flushConcurrency == 1) {
                // One worker drains the whole ready queue (legacy / default path).
                flushReady();
            } else {
                List<DorisJsonRow> batch = batchWriter.drainIfReady();
                if (!batch.isEmpty()) {
                    loadBatch(batch);
                }
            }
        } catch (IOException e) {
            log.debug("Doris async flush failed for {}.{}: {}", database, table, e.toString());
        } finally {
            inFlight.decrementAndGet();
            if (batchWriter.hasReady()) {
                scheduleFlushReady();
            }
        }
    }

    private int loadBatch(List<DorisJsonRow> batch) throws IOException {
        if (batch.isEmpty()) {
            return 0;
        }
        try {
            DorisStreamLoader.StreamLoadResult result = streamLoader.loadNdjsonRows(database, table, batch);
            if (!result.success()) {
                String sample = sampleRow(batch);
                String hint = DorisTableNames.META_SERVICE.equals(table)
                        ? " (re-run deploy/common/sql/databuff.sql if meta_service schema is outdated)"
                        : "";
                logPipelineStreamLoad(table, batch.size(), sample, false, result.body());
                throw new IOException("Doris stream load failed" + hint + ": " + result.body()
                        + (sample.isEmpty() ? "" : " sampleRow=" + sample));
            }
            consecutiveFailures.set(0);
            logPipelineStreamLoad(table, batch.size(), sampleRow(batch), true, result.body());
            log.debug("Stream loaded {} rows to {}.{}", batch.size(), database, table);
            return batch.size();
        } catch (IOException e) {
            int failures = consecutiveFailures.incrementAndGet();
            if (failures < maxConsecutiveFailures) {
                batchWriter.requeue(batch);
                throw e;
            }
            String sample = sampleRow(batch);
            log.error(
                    "Doris stream load dropped after {} consecutive failures {}.{} rows={} sample={} cause={}",
                    failures,
                    database,
                    table,
                    batch.size(),
                    sample,
                    e.toString());
            consecutiveFailures.set(0);
            return 0;
        }
    }

    /** Visible for tests: materialize NDJSON bytes (join with newlines). */
    static byte[] joinJsonLines(List<? extends DorisJsonRow> rows) throws IOException {
        if (rows.isEmpty()) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, rows.size() * 64));
        try (NdjsonRowsInputStream in = new NdjsonRowsInputStream(rows)) {
            in.transferTo(out);
        }
        return out.toByteArray();
    }

    public String table() {
        return table;
    }

    public String database() {
        return database;
    }

    /** Whether the ready queue has handed-off batches waiting for Stream Load. */
    public boolean hasReady() {
        return batchWriter.hasReady();
    }

    /** Visible for tests / ops. */
    public int flushConcurrency() {
        return flushConcurrency;
    }

    /** Visible for tests. */
    int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + FLUSH_THREAD_SEQ.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static String sampleRow(List<DorisJsonRow> batch) {
        if (batch.isEmpty()) {
            return "";
        }
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(512);
            batch.get(0).writeTo(buf);
            String row = buf.toString(StandardCharsets.UTF_8);
            return row.length() > 500 ? row.substring(0, 500) + "..." : row;
        } catch (IOException e) {
            return "<sample-encode-failed:" + e.getMessage() + ">";
        }
    }

    private static void logPipelineStreamLoad(
            String table,
            int rowCount,
            String sampleRow,
            boolean success,
            String body) {
        if (!DorisTableNames.METRIC_JVM.equals(table)) {
            return;
        }
        String status = truncate(body, 300);
        if (success) {
            log.debug(
                    "[metric-pipeline] STREAM_LOAD table={} rows={} success=true sample={} doris={}",
                    table,
                    rowCount,
                    sampleRow,
                    status);
        } else {
            log.warn(
                    "[metric-pipeline] STREAM_LOAD table={} rows={} success=false sample={} doris={}",
                    table,
                    rowCount,
                    sampleRow,
                    status);
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }
}
