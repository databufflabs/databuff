package com.databuff.apm.web.monitor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThresholdAlarmCheckTest {

    @Test
    void detectsGreaterThanThreshold() {
        assertThat(ThresholdAlarmCheck.breached(0.06, 0.05, "gt")).isTrue();
        assertThat(ThresholdAlarmCheck.breached(0.04, 0.05, "gt")).isFalse();
        assertThat(ThresholdAlarmCheck.breached(0.05, 0.05, ">")).isFalse();
    }

    @Test
    void detectsGreaterOrEqualThreshold() {
        assertThat(ThresholdAlarmCheck.breached(0.05, 0.05, "gte")).isTrue();
        assertThat(ThresholdAlarmCheck.breached(0.05, 0.05, ">=")).isTrue();
        assertThat(ThresholdAlarmCheck.breached(0.04, 0.05, "gte")).isFalse();
    }

    @Test
    void detectsLessThanThreshold() {
        assertThat(ThresholdAlarmCheck.breached(0, 1, "lt")).isTrue();
        assertThat(ThresholdAlarmCheck.breached(0, 1, "<")).isTrue();
        assertThat(ThresholdAlarmCheck.breached(1, 1, "lt")).isFalse();
        assertThat(ThresholdAlarmCheck.breached(2, 1, "<")).isFalse();
    }

    @Test
    void detectsLessOrEqualThreshold() {
        assertThat(ThresholdAlarmCheck.breached(1, 1, "lte")).isTrue();
        assertThat(ThresholdAlarmCheck.breached(1, 1, "<=")).isTrue();
        assertThat(ThresholdAlarmCheck.breached(0, 1, "lte")).isTrue();
        assertThat(ThresholdAlarmCheck.breached(2, 1, "<=")).isFalse();
    }

    @Test
    void blankComparatorDefaultsToGreaterThan() {
        assertThat(ThresholdAlarmCheck.breached(0.06, 0.05, null)).isTrue();
        assertThat(ThresholdAlarmCheck.breached(0.05, 0.05, "")).isFalse();
    }

    @Test
    void resolveLevelPrefersCriticalOverWarning() {
        // warning > 5, critical > 10; value 12 breaches both -> critical
        assertThat(ThresholdAlarmCheck.resolveLevel(12, ">", 10, 5))
                .isEqualTo(EventRule.LEVEL_CRITICAL);
        // value 7 breaches only warning
        assertThat(ThresholdAlarmCheck.resolveLevel(7, ">", 10, 5))
                .isEqualTo(EventRule.LEVEL_WARNING);
        // value 3 breaches neither
        assertThat(ThresholdAlarmCheck.resolveLevel(3, ">", 10, 5)).isNull();
    }

    @Test
    void resolveLevelSupportsLessThanBands() {
        // warning < 10, critical < 1; value 0 -> critical; value 5 -> warning; value 20 -> none
        assertThat(ThresholdAlarmCheck.resolveLevel(0, "<", 1, 10))
                .isEqualTo(EventRule.LEVEL_CRITICAL);
        assertThat(ThresholdAlarmCheck.resolveLevel(5, "<", 1, 10))
                .isEqualTo(EventRule.LEVEL_WARNING);
        assertThat(ThresholdAlarmCheck.resolveLevel(20, "<", 1, 10)).isNull();
    }

    @Test
    void resolveLevelAllowsWarningOnly() {
        assertThat(ThresholdAlarmCheck.resolveLevel(8, ">", Double.NaN, 5))
                .isEqualTo(EventRule.LEVEL_WARNING);
        assertThat(ThresholdAlarmCheck.resolveLevel(3, ">", Double.NaN, 5)).isNull();
    }

    @Test
    void resolveBreachedThresholdPicksMatchingBand() {
        assertThat(ThresholdAlarmCheck.resolveBreachedThreshold(
                EventRule.LEVEL_WARNING, 10, 5)).isEqualTo(5);
        assertThat(ThresholdAlarmCheck.resolveBreachedThreshold(
                EventRule.LEVEL_CRITICAL, 10, 5)).isEqualTo(10);
    }
}
