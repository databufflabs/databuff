package com.databuff.apm.web.monitor;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AlarmTest {

    @Test
    void exposesExpectedStatusConstants() {
        assertThat(Alarm.STATUS_OPEN).isEqualTo("open");
        assertThat(Alarm.STATUS_RESOLVED).isEqualTo("resolved");
    }

    @Test
    void constructsOpenAlarmWithAllComponents() {
        Instant triggeredAt = Instant.parse("2026-07-10T08:00:00Z");
        Alarm alarm = new Alarm(
                "alarm-1",
                42L,
                "checkout",
                "threshold",
                "critical",
                "error rate exceeded",
                Alarm.STATUS_OPEN,
                triggeredAt,
                null);

        assertThat(alarm.id()).isEqualTo("alarm-1");
        assertThat(alarm.policyId()).isEqualTo(42L);
        assertThat(alarm.service()).isEqualTo("checkout");
        assertThat(alarm.detectionWay()).isEqualTo("threshold");
        assertThat(alarm.level()).isEqualTo("critical");
        assertThat(alarm.message()).isEqualTo("error rate exceeded");
        assertThat(alarm.status()).isEqualTo(Alarm.STATUS_OPEN);
        assertThat(alarm.triggeredAt()).isEqualTo(triggeredAt);
        assertThat(alarm.resolvedAt()).isNull();
    }

    @Test
    void constructsAlarmWithDifferentLevelAndStatus() {
        Instant triggeredAt = Instant.parse("2026-07-10T08:00:00Z");
        Instant resolvedAt = Instant.parse("2026-07-10T08:05:00Z");
        Alarm alarm = new Alarm(
                "alarm-2",
                84L,
                "payments",
                "mutation",
                "warning",
                "request count changed",
                Alarm.STATUS_RESOLVED,
                triggeredAt,
                resolvedAt);

        assertThat(alarm.level()).isEqualTo("warning");
        assertThat(alarm.status()).isEqualTo(Alarm.STATUS_RESOLVED);
        assertThat(alarm.detectionWay()).isEqualTo("mutation");
        assertThat(alarm.resolvedAt()).isEqualTo(resolvedAt);
    }

    @Test
    void resolveReturnsResolvedCopyWithResolutionTime() {
        Alarm alarm = sampleAlarm();
        Instant resolvedAt = Instant.parse("2026-07-10T08:05:00Z");

        Alarm resolved = alarm.resolve(resolvedAt);

        assertThat(resolved.id()).isEqualTo(alarm.id());
        assertThat(resolved.policyId()).isEqualTo(alarm.policyId());
        assertThat(resolved.service()).isEqualTo(alarm.service());
        assertThat(resolved.detectionWay()).isEqualTo(alarm.detectionWay());
        assertThat(resolved.level()).isEqualTo(alarm.level());
        assertThat(resolved.message()).isEqualTo(alarm.message());
        assertThat(resolved.triggeredAt()).isEqualTo(alarm.triggeredAt());
        assertThat(resolved.status()).isEqualTo(Alarm.STATUS_RESOLVED);
        assertThat(resolved.resolvedAt()).isEqualTo(resolvedAt);
    }

    @Test
    void resolveDoesNotMutateOriginalAlarm() {
        Alarm alarm = sampleAlarm();

        Alarm resolved = alarm.resolve(Instant.parse("2026-07-10T08:05:00Z"));

        assertThat(alarm.status()).isEqualTo(Alarm.STATUS_OPEN);
        assertThat(alarm.resolvedAt()).isNull();
        assertThat(resolved).isNotSameAs(alarm);
        assertThat(resolved).isNotEqualTo(alarm);
    }

    @Test
    void recordEqualityHashCodeAndStringRepresentation() {
        Alarm left = sampleAlarm();
        Alarm right = sampleAlarm();

        assertThat(left).isEqualTo(right);
        assertThat(left.hashCode()).isEqualTo(right.hashCode());
        assertThat(left.toString()).contains("alarm-1", "checkout", "threshold", "critical", "open");
    }

    private static Alarm sampleAlarm() {
        return new Alarm(
                "alarm-1",
                42L,
                "checkout",
                "threshold",
                "critical",
                "error rate exceeded",
                Alarm.STATUS_OPEN,
                Instant.parse("2026-07-10T08:00:00Z"),
                null);
    }
}
