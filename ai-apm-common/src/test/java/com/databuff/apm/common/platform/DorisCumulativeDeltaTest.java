package com.databuff.apm.common.platform;

import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;

class DorisCumulativeDeltaTest {

    @Test
    void delta_skipsInvalidKeysAndNonFiniteValues() {
        DorisCumulativeDelta delta = new DorisCumulativeDelta();
        assertThat(delta.delta(null, 100.0)).isEmpty();
        assertThat(delta.delta("", 100.0)).isEmpty();
        assertThat(delta.delta("  ", 100.0)).isEmpty();
        assertThat(delta.delta("h\\0cpu", Double.NaN)).isEmpty();
        assertThat(delta.delta("h\\0cpu", Double.POSITIVE_INFINITY)).isEmpty();
        assertThat(delta.delta("h\\0cpu", Double.NEGATIVE_INFINITY)).isEmpty();
    }

    @Test
    void delta_firstObservationSkipped_thenPositiveDiff() {
        DorisCumulativeDelta delta = new DorisCumulativeDelta();
        assertThat(delta.delta("h\\0cpu", 100.0)).isEmpty();
        assertThat(delta.delta("h\\0cpu", 130.0)).hasValue(30.0);
        assertThat(delta.delta("h\\0cpu", 130.0)).hasValue(0.0);
    }

    @Test
    void delta_counterResetYieldsZero() {
        DorisCumulativeDelta delta = new DorisCumulativeDelta();
        assertThat(delta.delta("h\\0bytes", 1000.0)).isEmpty();
        assertThat(delta.delta("h\\0bytes", 90.0)).hasValue(0.0);
        assertThat(delta.delta("h\\0bytes", 150.0)).hasValue(60.0);
    }

    @Test
    void delta_tracksSeriesIndependently() {
        DorisCumulativeDelta delta = new DorisCumulativeDelta();
        assertThat(delta.delta("a\\0m", 1.0)).isEmpty();
        assertThat(delta.delta("b\\0m", 10.0)).isEmpty();
        assertThat(delta.delta("a\\0m", 5.0)).hasValue(4.0);
        assertThat(delta.delta("b\\0m", 25.0)).hasValue(15.0);
    }

    @Test
    void clear_resetsBaselines() {
        DorisCumulativeDelta delta = new DorisCumulativeDelta();
        assertThat(delta.delta("h\\0cpu", 100.0)).isEmpty();
        delta.clear();
        assertThat(delta.delta("h\\0cpu", 100.0)).isEmpty();
        assertThat(delta.delta("h\\0cpu", 110.0)).hasValue(10.0);
    }

    @Test
    void emptyIsReturnedWithoutRecordingForBadKey() {
        DorisCumulativeDelta delta = new DorisCumulativeDelta();
        OptionalDouble ignored = delta.delta("  ", 5.0);
        assertThat(ignored).isEmpty();
        assertThat(delta.delta("h\\0cpu", 1.0)).isEmpty();
    }
}
