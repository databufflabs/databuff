package com.databuff.apm.demo.fault;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DemoFaultControllerTest {

    @Test
    void startsOnlyAfterAppliedEventAckAndReusesTheActiveRun() throws Exception {
        RecordingPublisher publisher = new RecordingPublisher();
        AtomicReference<DemoFaultController> reference = new AtomicReference<>();
        publisher.beforePublish = change -> {
            if (change.action() == DemoConfigurationChange.Action.APPLIED) {
                assertThat(reference.get().snapshot().active())
                        .as("fault must remain NORMAL while waiting for Kafka ACK")
                        .isFalse();
            }
        };
        DemoFaultController controller = controller(publisher, Duration.ofSeconds(10));
        reference.set(controller);
        try {
            DemoFaultController.TriggerResult first = controller.trigger();
            DemoFaultController.TriggerResult duplicate = controller.trigger();

            assertThat(first.reused()).isFalse();
            assertThat(duplicate.reused()).isTrue();
            assertThat(duplicate.run().runId()).isEqualTo(first.run().runId());
            assertThat(first.run().durationSeconds()).isEqualTo(10);
            assertThat(controller.snapshot().active()).isTrue();
            assertThat(controller.snapshot().cacheTtlSeconds()).isZero();
            assertThat(publisher.events)
                    .extracting(DemoConfigurationChange::action)
                    .containsExactly(DemoConfigurationChange.Action.APPLIED);
            assertThat(first.toJson())
                    .contains("\"state\":\"ACTIVE\"")
                    .contains("\"published\":true")
                    .contains("\"reused\":false");
        } finally {
            controller.close();
        }
    }

    @Test
    void publishFailureReturnsWithoutStartingTheFault() {
        RecordingPublisher publisher = new RecordingPublisher();
        publisher.failApplied = true;
        DemoFaultController controller = controller(publisher, Duration.ofSeconds(10));
        try {
            assertThatThrownBy(controller::trigger)
                    .isInstanceOf(DemoFaultController.FaultStartException.class)
                    .hasMessageContaining("fault was not started");
            assertThat(controller.snapshot()).isEqualTo(DemoFaultSnapshot.normal());
            assertThat(controller.currentJson()).contains("\"state\":\"IDLE\"");
        } finally {
            controller.close();
        }
    }

    @Test
    void restoresStateBeforePublishingAndRetriesTheStableRecoveryId() throws Exception {
        RecordingPublisher publisher = new RecordingPublisher();
        publisher.recoveryFailures.set(1);
        AtomicReference<DemoFaultController> reference = new AtomicReference<>();
        publisher.beforePublish = change -> {
            if (change.action() == DemoConfigurationChange.Action.AUTO_REVERTED) {
                assertThat(reference.get().snapshot().active())
                        .as("NORMAL state must be restored before recovery publish/retry")
                        .isFalse();
            }
        };
        DemoFaultController controller = controller(
                publisher, Duration.ofMillis(50), Duration.ofMillis(20));
        reference.set(controller);
        try {
            String runId = controller.trigger().run().runId();
            assertThat(publisher.recoveryPublished.await(2, TimeUnit.SECONDS)).isTrue();

            assertThat(controller.snapshot()).isEqualTo(DemoFaultSnapshot.normal());
            List<DemoConfigurationChange> recoveries = publisher.events.stream()
                    .filter(change -> change.action()
                            == DemoConfigurationChange.Action.AUTO_REVERTED)
                    .toList();
            assertThat(recoveries).hasSize(2);
            assertThat(recoveries)
                    .extracting(DemoConfigurationChange::eventId)
                    .containsOnly("change-" + runId + "-reverted");
            assertThat(recoveries)
                    .extracting(DemoConfigurationChange::fingerprint)
                    .containsOnly("fp:demo-change:" + runId + ":service-b:order-cache-ttl");

            DemoConfigurationChange appliedLog = controller.pendingConfigurationLog();
            assertThat(appliedLog.action()).isEqualTo(DemoConfigurationChange.Action.APPLIED);
            controller.acknowledgeConfigurationLog(appliedLog);
            assertThat(controller.pendingConfigurationLog().action())
                    .isEqualTo(DemoConfigurationChange.Action.AUTO_REVERTED);
        } finally {
            controller.close();
        }
    }

    @Test
    void refusesFaultWhenKafkaIsNotConfigured() {
        DemoFaultController controller = new DemoFaultController(
                new UnavailableChangeEventPublisher("buffops.demo.alerts"));
        try {
            assertThatThrownBy(controller::trigger)
                    .isInstanceOf(DemoFaultController.FaultStartException.class)
                    .hasMessageContaining("unavailable");
            assertThat(controller.snapshot().active()).isFalse();
        } finally {
            controller.close();
        }
    }

    private static DemoFaultController controller(
            RecordingPublisher publisher, Duration duration) {
        return controller(publisher, duration, Duration.ofMillis(20));
    }

    private static DemoFaultController controller(
            RecordingPublisher publisher, Duration duration, Duration retryDelay) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        return new DemoFaultController(
                publisher,
                Clock.fixed(Instant.parse("2026-09-11T02:30:00Z"), ZoneOffset.UTC),
                duration,
                retryDelay,
                executor);
    }

    private static final class RecordingPublisher implements ChangeEventPublisher {
        private final List<DemoConfigurationChange> events = new CopyOnWriteArrayList<>();
        private final AtomicInteger recoveryFailures = new AtomicInteger();
        private final CountDownLatch recoveryPublished = new CountDownLatch(1);
        private volatile boolean failApplied;
        private volatile java.util.function.Consumer<DemoConfigurationChange> beforePublish =
                ignored -> { };

        @Override
        public void publish(DemoConfigurationChange change) {
            beforePublish.accept(change);
            events.add(change);
            if (change.action() == DemoConfigurationChange.Action.APPLIED && failApplied) {
                throw new IllegalStateException("broker down");
            }
            if (change.action() == DemoConfigurationChange.Action.AUTO_REVERTED
                    && recoveryFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                throw new IllegalStateException("temporary timeout");
            }
            if (change.action() == DemoConfigurationChange.Action.AUTO_REVERTED) {
                recoveryPublished.countDown();
            }
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String topic() {
            return "buffops.demo.alerts";
        }
    }
}
