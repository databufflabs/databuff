package com.databuff.apm.web.monitor;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EventRuleTest {

    @Test
    void exposesExpectedConstants() {
        assertThat(EventRule.CLASSIFY_SINGLE).isEqualTo("singleMetric");
        assertThat(EventRule.WAY_THRESHOLD).isEqualTo("threshold");
        assertThat(EventRule.WAY_MUTATION).isEqualTo("mutation");
        assertThat(EventRule.METRIC_ERROR_RATE).isEqualTo("error_rate");
        assertThat(EventRule.METRIC_REQUEST_COUNT).isEqualTo("request_count");
        assertThat(EventRule.COMPARATOR_GT).isEqualTo("gt");
    }

    @Test
    void constructsThresholdRuleWithAllFields() {
        Instant updatedAt = Instant.parse("2026-07-10T08:00:00Z");
        EventRule rule = sampleRule(1L, EventRule.WAY_THRESHOLD, true, updatedAt);

        assertThat(rule.id()).isEqualTo(1L);
        assertThat(rule.ruleName()).isEqualTo("checkout errors");
        assertThat(rule.classify()).isEqualTo(EventRule.CLASSIFY_SINGLE);
        assertThat(rule.detectionWay()).isEqualTo(EventRule.WAY_THRESHOLD);
        assertThat(rule.service()).isEqualTo("checkout");
        assertThat(rule.metric()).isEqualTo(EventRule.METRIC_ERROR_RATE);
        assertThat(rule.threshold()).isEqualTo(0.1);
        assertThat(rule.comparator()).isEqualTo(EventRule.COMPARATOR_GT);
        assertThat(rule.enabled()).isTrue();
        assertThat(rule.queryJson()).isEqualTo("{\"metric\":\"error_rate\"}");
        assertThat(rule.updatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void constructsMutationRuleWithDifferentParameters() {
        EventRule rule = new EventRule(
                2L,
                "checkout mutation",
                EventRule.CLASSIFY_SINGLE,
                EventRule.WAY_MUTATION,
                "checkout",
                EventRule.METRIC_REQUEST_COUNT,
                0.3,
                EventRule.COMPARATOR_GT,
                false,
                null,
                null);

        assertThat(rule.detectionWay()).isEqualTo(EventRule.WAY_MUTATION);
        assertThat(rule.metric()).isEqualTo(EventRule.METRIC_REQUEST_COUNT);
        assertThat(rule.threshold()).isEqualTo(0.3);
        assertThat(rule.enabled()).isFalse();
        assertThat(rule.queryJson()).isNull();
        assertThat(rule.updatedAt()).isNull();
    }

    @Test
    void withEnabledTogglesFlagAndRefreshesUpdatedAt() {
        Instant originalUpdatedAt = Instant.parse("2026-07-10T08:00:00Z");
        EventRule rule = sampleRule(3L, EventRule.WAY_THRESHOLD, true, originalUpdatedAt);
        Instant before = Instant.now();

        EventRule disabled = rule.withEnabled(false);
        Instant after = Instant.now();

        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.updatedAt()).isAfterOrEqualTo(before);
        assertThat(disabled.updatedAt()).isBeforeOrEqualTo(after);
        assertThat(disabled.id()).isEqualTo(rule.id());
        assertThat(disabled.ruleName()).isEqualTo(rule.ruleName());
        assertThat(disabled.classify()).isEqualTo(rule.classify());
        assertThat(disabled.detectionWay()).isEqualTo(rule.detectionWay());
        assertThat(disabled.service()).isEqualTo(rule.service());
        assertThat(disabled.metric()).isEqualTo(rule.metric());
        assertThat(disabled.threshold()).isEqualTo(rule.threshold());
        assertThat(disabled.comparator()).isEqualTo(rule.comparator());
        assertThat(disabled.queryJson()).isEqualTo(rule.queryJson());
    }

    @Test
    void withEnabledReturnsNewInstanceWithoutMutatingOriginal() {
        EventRule rule = sampleRule(4L, EventRule.WAY_THRESHOLD, true, Instant.now());

        EventRule enabled = rule.withEnabled(false);

        assertThat(rule.enabled()).isTrue();
        assertThat(enabled).isNotSameAs(rule);
        assertThat(enabled).isNotEqualTo(rule);
    }

    @Test
    void recordEqualityUsesAllComponents() {
        Instant updatedAt = Instant.parse("2026-07-10T08:00:00Z");
        EventRule left = sampleRule(5L, EventRule.WAY_THRESHOLD, true, updatedAt);
        EventRule right = sampleRule(5L, EventRule.WAY_THRESHOLD, true, updatedAt);

        assertThat(left).isEqualTo(right);
        assertThat(left.hashCode()).isEqualTo(right.hashCode());
        assertThat(left.toString()).contains("checkout errors", "threshold");
    }

    private static EventRule sampleRule(long id, String detectionWay, boolean enabled, Instant updatedAt) {
        return new EventRule(
                id,
                "checkout errors",
                EventRule.CLASSIFY_SINGLE,
                detectionWay,
                "checkout",
                EventRule.METRIC_ERROR_RATE,
                0.1,
                EventRule.COMPARATOR_GT,
                enabled,
                "{\"metric\":\"error_rate\"}",
                updatedAt);
    }
}
