package com.databuff.apm.common.storage;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DorisBatchWriterTest {

    @Test
    void handsOffToReadyQueueWhenEstimatedBytesReachThreshold() {
        DorisBatchWriter writer = new DorisBatchWriter(10);
        writer.offer(new byte[]{1, 2, 3, 4});
        assertThat(writer.drainIfReady()).isEmpty();
        assertThat(writer.pendingCount()).isEqualTo(1);
        writer.offer(new byte[]{5, 6, 7, 8, 9});
        // Hand-off happens on offer; ready queue holds the batch.
        assertThat(writer.drainIfReady()).hasSize(2);
        assertThat(writer.pendingCount()).isZero();
    }

    @Test
    void flushAllRotatesPartialThreadBuffer() {
        DorisBatchWriter writer = new DorisBatchWriter(10_000);
        writer.offer(new byte[]{3});
        List<DorisJsonRow> flushed = writer.flushAll();
        assertThat(flushed).hasSize(1);
        assertThat(DorisJsonRow.toByteArray(flushed.get(0))).containsExactly((byte) 3);
        assertThat(writer.flushAll()).isEmpty();
    }

    @Test
    void concurrentOffersDoNotLoseRows() throws Exception {
        DorisBatchWriter writer = new DorisBatchWriter(Long.MAX_VALUE / 4, TimeUnit.HOURS.toMillis(1));
        int threads = 8;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger offered = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            pool.execute(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        writer.offer(new byte[]{(byte) i});
                        offered.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        List<DorisJsonRow> all = writer.flushAll();
        assertThat(all).hasSize(offered.get());
        assertThat(all).hasSize(threads * perThread);
    }

    @Test
    void requeueDoesNotTouchThreadLocalBuffer() {
        DorisBatchWriter writer = new DorisBatchWriter(10_000);
        List<DorisJsonRow> batch = new ArrayList<>();
        batch.add(DorisJsonRow.ofBytes(new byte[]{9}));
        writer.requeue(batch);
        assertThat(writer.drainIfReady()).hasSize(1);
        assertThat(writer.flushAll()).isEmpty();
    }
}
