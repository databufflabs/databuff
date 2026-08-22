package com.databuff.apm.web.monitor.webhook;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookAlertQueueTest {

    @Test
    void fullQueueDropsImmediatelyWhileConsumerIsBlocked() throws Exception {
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        WebhookAlertQueue queue = new WebhookAlertQueue(2, 1, batch -> {
            sending.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertThat(queue.offer(Map.of("id", 1))).isTrue();
            assertThat(sending.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(queue.offer(Map.of("id", 2))).isTrue();
            assertThat(queue.offer(Map.of("id", 3))).isTrue();

            long started = System.nanoTime();
            assertThat(queue.offer(Map.of("id", 4))).isFalse();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(elapsedMillis).isLessThan(100);
            assertThat(queue.droppedCount()).isEqualTo(1);
            assertThat(queue.queuedCount()).isEqualTo(2);
            release.countDown();
            assertThat(queue.awaitIdle(2_000)).isTrue();
        } finally {
            release.countDown();
            queue.close();
        }
    }
}
