package com.databuff.apm.web.monitor.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded, non-blocking producer queue with one continuously running batch consumer.
 * Network failures can occupy only the consumer thread; producers never wait for them.
 */
public class WebhookAlertQueue implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WebhookAlertQueue.class);
    private static final long BATCH_COLLECT_MILLIS = 100L;

    @FunctionalInterface
    public interface BatchSender {
        void send(List<Map<String, Object>> alertPayloads);
    }

    private final ArrayBlockingQueue<Map<String, Object>> queue;
    private final int batchSize;
    private final BatchSender sender;
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong dropped = new AtomicLong();
    private final Object lifecycleLock = new Object();
    private final Thread worker;
    private volatile boolean running = true;

    public WebhookAlertQueue(int capacity, int batchSize, BatchSender sender) {
        this.queue = new ArrayBlockingQueue<>(Math.max(1, capacity));
        this.batchSize = Math.max(1, Math.min(batchSize, queue.remainingCapacity()));
        this.sender = sender;
        this.worker = new Thread(this::consume, "alarm-webhook-consumer");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /** Returns immediately. False means the queue was full (or is shutting down). */
    public boolean offer(Map<String, Object> payload) {
        if (payload == null) {
            return false;
        }
        synchronized (lifecycleLock) {
            if (!running) {
                return false;
            }
            pending.incrementAndGet();
            if (queue.offer(payload)) {
                return true;
            }
            pending.decrementAndGet();
            dropped.incrementAndGet();
            return false;
        }
    }

    public long droppedCount() {
        return dropped.get();
    }

    public int queuedCount() {
        return queue.size();
    }

    public boolean awaitIdle(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
        while (System.nanoTime() < deadline) {
            if (pending.get() == 0 && inFlight.get() == 0) {
                return true;
            }
            Thread.sleep(20L);
        }
        return pending.get() == 0 && inFlight.get() == 0;
    }

    private void consume() {
        while (running) {
            Map<String, Object> first;
            try {
                first = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            List<Map<String, Object>> batch = collectBatch(first);
            if (!running) {
                dropped.addAndGet(batch.size());
                pending.addAndGet(-batch.size());
                break;
            }

            inFlight.addAndGet(batch.size());
            try {
                sender.send(List.copyOf(batch));
            } catch (RuntimeException e) {
                log.warn("Unexpected alarm webhook sender failure: {}", e.toString());
            } finally {
                inFlight.addAndGet(-batch.size());
                pending.addAndGet(-batch.size());
            }
        }
    }

    private List<Map<String, Object>> collectBatch(Map<String, Object> first) {
        List<Map<String, Object>> batch = new ArrayList<>(batchSize);
        batch.add(first);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BATCH_COLLECT_MILLIS);
        while (running && batch.size() < batchSize) {
            queue.drainTo(batch, batchSize - batch.size());
            if (batch.size() >= batchSize) {
                break;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                Map<String, Object> next = queue.poll(remaining, TimeUnit.NANOSECONDS);
                if (next == null) {
                    break;
                }
                batch.add(next);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return batch;
    }

    @Override
    public void close() {
        List<Map<String, Object>> discarded = new ArrayList<>();
        synchronized (lifecycleLock) {
            running = false;
            worker.interrupt();
            queue.drainTo(discarded);
        }
        if (!discarded.isEmpty()) {
            dropped.addAndGet(discarded.size());
            pending.addAndGet(-discarded.size());
        }
    }
}
