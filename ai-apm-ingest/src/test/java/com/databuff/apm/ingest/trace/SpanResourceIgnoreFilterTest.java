package com.databuff.apm.ingest.trace;

import com.databuff.apm.common.model.DcSpan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpanResourceIgnoreFilterTest {

    @Test
    void noopIgnoresNothing() {
        SpanResourceIgnoreFilter filter = SpanResourceIgnoreFilter.NOOP;
        assertThat(filter.isEmpty()).isTrue();
        assertThat(filter.shouldIgnore(span("/actuator/prometheus"))).isFalse();
    }

    @Test
    void exactMatchDropsConfiguredResource() {
        SpanResourceIgnoreFilter filter = new SpanResourceIgnoreFilter(
                List.of("/actuator/prometheus", "PING"),
                List.of());

        assertThat(filter.shouldIgnore(span("/actuator/prometheus"))).isTrue();
        assertThat(filter.shouldIgnore(span("PING"))).isTrue();
        assertThat(filter.shouldIgnore(span("/api/orders"))).isFalse();
        assertThat(filter.shouldIgnore(span("/actuator/health"))).isFalse();
    }

    @Test
    void regexMatchUsesFullStringSemantics() {
        SpanResourceIgnoreFilter filter = new SpanResourceIgnoreFilter(
                List.of(),
                List.of("^/actuator(/.*)?$", "^PING$"));

        assertThat(filter.shouldIgnore(span("/actuator"))).isTrue();
        assertThat(filter.shouldIgnore(span("/actuator/prometheus"))).isTrue();
        assertThat(filter.shouldIgnore(span("PING"))).isTrue();
        assertThat(filter.shouldIgnore(span("/api/actuator/prometheus"))).isFalse();
        assertThat(filter.shouldIgnore(span("PING_EXTRA"))).isFalse();
    }

    @Test
    void exactAndRegexCanCombine() {
        SpanResourceIgnoreFilter filter = new SpanResourceIgnoreFilter(
                List.of("health"),
                List.of("^/metrics$"));

        assertThat(filter.shouldIgnore(span("health"))).isTrue();
        assertThat(filter.shouldIgnore(span("/metrics"))).isTrue();
        assertThat(filter.shouldIgnore(span("/health"))).isFalse();
    }

    @Test
    void matchesMetaHttpUrlWhenResourceDiffers() {
        SpanResourceIgnoreFilter filter = new SpanResourceIgnoreFilter(
                List.of("/actuator/prometheus"),
                List.of("^/actuator(/.*)?$"));

        DcSpan span = new DcSpan();
        span.resource = "GET";
        span.metaHttpUrl = "/actuator/prometheus";
        assertThat(filter.shouldIgnore(span)).isTrue();

        DcSpan other = new DcSpan();
        other.resource = "GET";
        other.metaHttpUrl = "/api/orders";
        assertThat(filter.shouldIgnore(other)).isFalse();
    }

    @Test
    void blankResourceOrNullSpanNotIgnored() {
        SpanResourceIgnoreFilter filter = new SpanResourceIgnoreFilter(
                List.of("/actuator/prometheus"),
                List.of("^/actuator(/.*)?$"));

        assertThat(filter.shouldIgnore(null)).isFalse();
        assertThat(filter.shouldIgnore(span(null))).isFalse();
        assertThat(filter.shouldIgnore(span("  "))).isFalse();
    }

    @Test
    void invalidRegexIsSkipped() {
        SpanResourceIgnoreFilter filter = new SpanResourceIgnoreFilter(
                List.of(),
                List.of("[invalid", "^/ok$"));

        assertThat(filter.shouldIgnore(span("/ok"))).isTrue();
        assertThat(filter.shouldIgnore(span("/other"))).isFalse();
    }

    @Test
    void regexIsPrecompiledAndReusableAcrossCalls() {
        SpanResourceIgnoreFilter filter = new SpanResourceIgnoreFilter(
                List.of(),
                List.of("^/actuator(/.*)?$"));

        // Hot path must stay stable across many matches (ThreadLocal Matcher reset).
        for (int i = 0; i < 1000; i++) {
            assertThat(filter.shouldIgnore(span("/actuator/prometheus"))).isTrue();
            assertThat(filter.shouldIgnore(span("/api/orders"))).isFalse();
        }
    }

    @Test
    void blankConfigEntriesAreIgnored() {
        SpanResourceIgnoreFilter filter = new SpanResourceIgnoreFilter(
                List.of(" ", "/keep"),
                List.of("", "  ", "^/drop$"));

        assertThat(filter.shouldIgnore(span("/keep"))).isTrue();
        assertThat(filter.shouldIgnore(span("/drop"))).isTrue();
        assertThat(filter.isEmpty()).isFalse();
    }

    private static DcSpan span(String resource) {
        DcSpan span = new DcSpan();
        span.resource = resource;
        return span;
    }
}
