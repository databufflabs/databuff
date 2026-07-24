package com.databuff.apm.common.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * Batches {@link DorisJsonRow}s for Doris Stream Load with low offer-path contention.
 * <p>
 * Modeled after Ultra {@code TraceOlapSinkV2Function}: each ingest thread owns a private
 * buffer; size/time flush is a hand-off into a lock-free ready queue. Stream Load consumers
 * only drain the ready queue (and rarely rotate idle thread buffers) — they never hold a
 * global lock while HTTP is in flight.
 */
public class DorisBatchWriter {

    /**
     * Default Stream Load batch size: 32 MiB.
     * Larger batches cut Stream Load frequency so tablet versions stay under Doris's ~2000 cap
     * (compaction cannot keep up with tiny/high-frequency loads on a single BE).
     */
    public static final long DEFAULT_MAX_BATCH_BYTES = 32L * 1024 * 1024;

    /** Default per-thread buffer age before hand-off (time fallback on the offer path). */
    public static final long DEFAULT_FLUSH_INTERVAL_MS = 30_000L;

    @FunctionalInterface
    public interface ReadyListener {
        void onBatchReady();
    }

    private final long maxBatchBytes;
    private final long flushIntervalNanos;
    private final ConcurrentLinkedQueue<List<DorisJsonRow>> ready = new ConcurrentLinkedQueue<>();
    private final Set<ThreadBuffer> activeBuffers = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<ThreadBuffer> localBuffer = ThreadLocal.withInitial(() -> {
        ThreadBuffer buffer = new ThreadBuffer();
        activeBuffers.add(buffer);
        return buffer;
    });

    private volatile ReadyListener readyListener;

    public DorisBatchWriter() {
        this(DEFAULT_MAX_BATCH_BYTES, DEFAULT_FLUSH_INTERVAL_MS);
    }

    public DorisBatchWriter(long maxBatchBytes) {
        this(maxBatchBytes, DEFAULT_FLUSH_INTERVAL_MS);
    }

    public DorisBatchWriter(long maxBatchBytes, long flushIntervalMs) {
        this.maxBatchBytes = Math.max(1L, maxBatchBytes);
        this.flushIntervalNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1L, flushIntervalMs));
    }

    public void setReadyListener(ReadyListener readyListener) {
        this.readyListener = readyListener;
    }

    public void offer(byte[] row) {
        offer(DorisJsonRow.ofBytes(row));
    }

    public void offer(DorisJsonRow row) {
        if (row == null) {
            return;
        }
        List<DorisJsonRow> handedOff = localBuffer.get().appendAndMaybeHandOff(
                row, row.estimatedBytes(), maxBatchBytes, flushIntervalNanos);
        if (handedOff != null) {
            enqueueReady(handedOff);
        }
    }

    public void offerAll(List<? extends DorisJsonRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        ThreadBuffer buffer = localBuffer.get();
        for (DorisJsonRow row : rows) {
            if (row == null) {
                continue;
            }
            List<DorisJsonRow> handedOff = buffer.appendAndMaybeHandOff(
                    row, row.estimatedBytes(), maxBatchBytes, flushIntervalNanos);
            if (handedOff != null) {
                enqueueReady(handedOff);
            }
        }
    }

    /**
     * Re-queue a failed Stream Load batch onto the ready queue (not the hot thread buffer),
     * so retries do not contend with ingest offer threads.
     */
    public void requeue(List<DorisJsonRow> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        enqueueReady(batch);
    }

    /** Poll one handed-off batch; empty list when nothing is ready. */
    public List<DorisJsonRow> drainIfReady() {
        List<DorisJsonRow> batch = ready.poll();
        return batch == null ? List.of() : batch;
    }

    public boolean hasReady() {
        return ready.peek() != null;
    }

    /**
     * Time-fallback / shutdown: rotate every thread buffer into the ready queue, then drain
     * all ready batches into one list (test helper and {@link DorisStreamLoadSink#flushAll()}).
     */
    public List<DorisJsonRow> flushAll() {
        rotateAll();
        List<DorisJsonRow> merged = new ArrayList<>();
        List<DorisJsonRow> batch;
        while ((batch = ready.poll()) != null) {
            merged.addAll(batch);
        }
        return merged;
    }

    /** Force hand-off of all thread-owned buffers into the ready queue (no merge). */
    public void rotateAll() {
        for (ThreadBuffer buffer : activeBuffers) {
            List<DorisJsonRow> handedOff = buffer.forceHandOff();
            if (handedOff != null) {
                // Do not notify per buffer — caller drains / schedules once.
                ready.offer(handedOff);
            }
        }
    }

    public int pendingCount() {
        int count = 0;
        for (List<DorisJsonRow> batch : ready) {
            count += batch.size();
        }
        for (ThreadBuffer buffer : activeBuffers) {
            count += buffer.size();
        }
        return count;
    }

    private void enqueueReady(List<DorisJsonRow> batch) {
        ready.offer(batch);
        ReadyListener listener = readyListener;
        if (listener != null) {
            listener.onBatchReady();
        }
    }

    /**
     * Per-ingest-thread buffer. Appends are almost always uncontended (owning thread only);
     * {@link #forceHandOff()} may race with a time-fallback rotator and uses the same monitor.
     */
    private static final class ThreadBuffer {
        private ArrayList<DorisJsonRow> rows = new ArrayList<>(256);
        private long bytes;
        private long createdNanos = System.nanoTime();

        synchronized ArrayList<DorisJsonRow> appendAndMaybeHandOff(
                DorisJsonRow row,
                long estimatedBytes,
                long maxBatchBytes,
                long flushIntervalNanos) {
            rows.add(row);
            bytes += estimatedBytes;
            long age = System.nanoTime() - createdNanos;
            if (bytes >= maxBatchBytes || age >= flushIntervalNanos) {
                return handOff();
            }
            return null;
        }

        synchronized ArrayList<DorisJsonRow> forceHandOff() {
            if (rows.isEmpty()) {
                return null;
            }
            return handOff();
        }

        synchronized int size() {
            return rows.size();
        }

        private ArrayList<DorisJsonRow> handOff() {
            ArrayList<DorisJsonRow> out = rows;
            rows = new ArrayList<>(Math.max(256, out.size()));
            bytes = 0L;
            createdNanos = System.nanoTime();
            return out;
        }
    }
}
