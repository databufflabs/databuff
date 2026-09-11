package com.databuff.apm.demo.fault;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Owns the single service-b cache TTL fault run and its automatic recovery. */
public final class DemoFaultController implements AutoCloseable {

    public static final String SCENARIO = "service-b-order-cache-ttl-zero";
    public static final Duration DEFAULT_DURATION = Duration.ofSeconds(180);
    private static final Duration DEFAULT_RETRY_DELAY = Duration.ofSeconds(5);
    private static final DateTimeFormatter RUN_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private final Object monitor = new Object();
    private final Clock clock;
    private final Duration duration;
    private final Duration retryDelay;
    private final ChangeEventPublisher publisher;
    private final ScheduledExecutorService executor;
    private final ConcurrentLinkedQueue<DemoConfigurationChange> pendingLogs =
            new ConcurrentLinkedQueue<>();

    private volatile DemoFaultSnapshot snapshot = DemoFaultSnapshot.normal();
    private FaultRun activeRun;
    private volatile boolean closed;

    public DemoFaultController(ChangeEventPublisher publisher) {
        this(
                publisher,
                Clock.systemUTC(),
                DEFAULT_DURATION,
                DEFAULT_RETRY_DELAY,
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "demo-fault-recovery");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    DemoFaultController(
            ChangeEventPublisher publisher,
            Clock clock,
            Duration duration,
            Duration retryDelay,
            ScheduledExecutorService executor) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.duration = requirePositive(duration, "duration");
        this.retryDelay = requirePositive(retryDelay, "retryDelay");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public TriggerResult trigger() throws FaultStartException {
        synchronized (monitor) {
            if (closed) {
                throw new FaultStartException("fault controller is closed");
            }
            if (activeRun != null) {
                return new TriggerResult(activeRun, true, publisher.topic());
            }
            if (!publisher.available()) {
                throw new FaultStartException(
                        "Kafka change-event publisher is unavailable; fault was not started");
            }

            Instant startedAt = clock.instant();
            FaultRun run = new FaultRun(
                    newRunId(startedAt), startedAt, startedAt.plus(duration), duration.toSeconds());
            DemoConfigurationChange applied = new DemoConfigurationChange(
                    run.runId(), DemoConfigurationChange.Action.APPLIED, startedAt);
            try {
                // The fault is deliberately still NORMAL until this call returns with broker ACK.
                publisher.publish(applied);
            } catch (Exception e) {
                throw new FaultStartException(
                        "Kafka did not acknowledge the configuration change; fault was not started", e);
            }

            activeRun = run;
            snapshot = DemoFaultSnapshot.active(run.runId(), run.startedAt(), run.autoRecoverAt());
            pendingLogs.add(applied);
            try {
                executor.schedule(
                        () -> autoRecover(run.runId()), duration.toMillis(), TimeUnit.MILLISECONDS);
            } catch (RuntimeException scheduleFailure) {
                DemoConfigurationChange reverted = restoreLocked(run.runId());
                if (reverted != null) {
                    publishRecoveryWithRetry(reverted);
                }
                throw new FaultStartException(
                        "automatic recovery could not be scheduled; fault was reverted", scheduleFailure);
            }
            System.out.println("[demo-fault] ACTIVE run=" + run.runId()
                    + " ttl=0 autoRecoverAt=" + run.autoRecoverAt());
            return new TriggerResult(run, false, publisher.topic());
        }
    }

    public DemoFaultSnapshot snapshot() {
        return snapshot;
    }

    public String currentJson() {
        FaultRun run;
        synchronized (monitor) {
            run = activeRun;
        }
        if (run == null) {
            return "{\"scenario\":\"" + SCENARIO
                    + "\",\"state\":\"IDLE\",\"cacheTtlSeconds\":300,"
                    + "\"configVersion\":\"" + DemoFaultSnapshot.NORMAL_CONFIG_VERSION + "\"}";
        }
        return runJson(run, false, publisher.topic());
    }

    /** Returns, but does not remove, the oldest configuration log awaiting APM export. */
    public DemoConfigurationChange pendingConfigurationLog() {
        return pendingLogs.peek();
    }

    /** Removes an exported log only if it is still the head item. */
    public void acknowledgeConfigurationLog(DemoConfigurationChange change) {
        pendingLogs.remove(change);
    }

    private void autoRecover(String runId) {
        DemoConfigurationChange reverted;
        synchronized (monitor) {
            reverted = restoreLocked(runId);
        }
        if (reverted != null) {
            System.out.println("[demo-fault] IDLE run=" + runId + " ttl=300 (automatic recovery)");
            publishRecoveryWithRetry(reverted);
        }
    }

    /** Must be called under monitor. State is restored before the event is published. */
    private DemoConfigurationChange restoreLocked(String runId) {
        if (activeRun == null || !activeRun.runId().equals(runId)) {
            return null;
        }
        Instant recoveredAt = clock.instant();
        activeRun = null;
        snapshot = DemoFaultSnapshot.normal();
        DemoConfigurationChange reverted = new DemoConfigurationChange(
                runId, DemoConfigurationChange.Action.AUTO_REVERTED, recoveredAt);
        pendingLogs.add(reverted);
        return reverted;
    }

    private void publishRecoveryWithRetry(DemoConfigurationChange reverted) {
        try {
            publisher.publish(reverted);
            System.out.println("[demo-fault] Kafka acknowledged " + reverted.eventId());
        } catch (Exception e) {
            if (closed) {
                System.err.println("[demo-fault] recovery event could not be published during shutdown: "
                        + e.getMessage());
                return;
            }
            System.err.println("[demo-fault] recovery event publish pending; retrying stable id="
                    + reverted.eventId() + ": " + e.getMessage());
            try {
                executor.schedule(
                        () -> publishRecoveryWithRetry(reverted),
                        retryDelay.toMillis(),
                        TimeUnit.MILLISECONDS);
            } catch (RuntimeException scheduleFailure) {
                System.err.println("[demo-fault] recovery event retry could not be scheduled: "
                        + scheduleFailure.getMessage());
            }
        }
    }

    @Override
    public void close() {
        DemoConfigurationChange reverted = null;
        synchronized (monitor) {
            if (closed) {
                return;
            }
            if (activeRun != null) {
                reverted = restoreLocked(activeRun.runId());
            }
            closed = true;
        }
        if (reverted != null) {
            try {
                publisher.publish(reverted);
            } catch (Exception e) {
                System.err.println("[demo-fault] shutdown recovery event publish failed: " + e.getMessage());
            }
        }
        executor.shutdownNow();
        publisher.close();
    }

    private String newRunId(Instant startedAt) {
        return "fault-" + RUN_TIME.format(startedAt) + "-"
                + UUID.randomUUID().toString().substring(0, 8);
    }

    static String runJson(FaultRun run, boolean reused, String topic) {
        StringBuilder json = new StringBuilder(480);
        json.append('{');
        jsonField(json, "runId", run.runId());
        jsonField(json, "scenario", SCENARIO);
        jsonField(json, "state", "ACTIVE");
        jsonField(json, "startedAt", run.startedAt().toString());
        jsonField(json, "autoRecoverAt", run.autoRecoverAt().toString());
        json.append(",\"durationSeconds\":").append(run.durationSeconds());
        json.append(",\"cacheTtlSeconds\":0");
        jsonField(json, "configVersion", DemoFaultSnapshot.FAULT_CONFIG_VERSION);
        json.append(",\"reused\":").append(reused);
        json.append(",\"changeEvent\":{");
        json.append("\"transport\":\"kafka\",");
        json.append("\"topic\":");
        DemoConfigurationChange.quoted(json, topic);
        json.append(",\"published\":true}}");
        return json.toString();
    }

    private static void jsonField(StringBuilder json, String key, String value) {
        if (json.length() > 1) {
            json.append(',');
        }
        DemoConfigurationChange.quoted(json, key);
        json.append(':');
        DemoConfigurationChange.quoted(json, value);
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    public record FaultRun(
            String runId,
            Instant startedAt,
            Instant autoRecoverAt,
            long durationSeconds) {
    }

    public record TriggerResult(FaultRun run, boolean reused, String topic) {
        public String toJson() {
            return runJson(run, reused, topic);
        }
    }

    public static final class FaultStartException extends Exception {
        public FaultStartException(String message) {
            super(message);
        }

        public FaultStartException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
