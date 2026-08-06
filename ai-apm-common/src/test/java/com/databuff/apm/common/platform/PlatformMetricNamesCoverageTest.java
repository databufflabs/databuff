package com.databuff.apm.common.platform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformMetricNamesCoverageTest {

    @Test
    void clusterDrop_buildsRelativeName() {
        assertThat(PlatformMetricNames.clusterDrop("ingest.trace.span"))
                .isEqualTo("cluster.drop.ingest.trace.span");
        assertThat(PlatformMetricNames.clusterDrop(null)).isEqualTo("cluster.drop.unknown");
        assertThat(PlatformMetricNames.clusterDrop("")).isEqualTo("cluster.drop.unknown");
    }

    @Test
    void qualify_handlesBlankComponentAndMetric() {
        assertThat(PlatformMetricNames.qualify("", "trace.req")).isEqualTo("trace.req");
        assertThat(PlatformMetricNames.qualify(null, "trace.req")).isEqualTo("trace.req");
        assertThat(PlatformMetricNames.qualify("ingest", "")).isEqualTo("ingest");
        assertThat(PlatformMetricNames.qualify("ingest", null)).isEqualTo("ingest");
        assertThat(PlatformMetricNames.qualify("", "")).isEmpty();
        assertThat(PlatformMetricNames.qualify("ingest", "  trace.req  ")).isEqualTo("ingest.trace.req");
    }

    @Test
    void qualify_isIdempotentWhenAlreadyPrefixed() {
        assertThat(PlatformMetricNames.qualify("web", "web.query.trace.req"))
                .isEqualTo("web.query.trace.req");
        assertThat(PlatformMetricNames.qualify("ingest", "ingest.write.queue"))
                .isEqualTo("ingest.write.queue");
    }

    @Test
    void token_sanitizesFragments() {
        assertThat(PlatformMetricNames.token("a b|c/d\\e", "x")).isEqualTo("a_b_c.d.e");
        assertThat(PlatformMetricNames.token(".a.b.", "x")).isEqualTo("a.b");
        assertThat(PlatformMetricNames.token("a..b", "x")).isEqualTo("a.b");
        assertThat(PlatformMetricNames.token(null, "unknown")).isEqualTo("unknown");
        assertThat(PlatformMetricNames.token("", "unknown")).isEqualTo("unknown");
        assertThat(PlatformMetricNames.token("  ", "unknown")).isEqualTo("unknown");
        assertThat(PlatformMetricNames.token("/", "unknown")).isEqualTo("unknown");
        assertThat(PlatformMetricNames.token("...", "unknown")).isEqualTo("unknown");
    }

    @Test
    void systemGcAndDorisNameHelpers() {
        assertThat(PlatformMetricNames.systemGc("count", "G1YoungGen"))
                .isEqualTo("system.gc.count.G1YoungGen");
        assertThat(PlatformMetricNames.doris("query_success")).isEqualTo("doris.query_success");
        assertThat(PlatformMetricNames.doris("cpu", "user")).isEqualTo("doris.cpu.user");
        assertThat(PlatformMetricNames.doris("cpu", " ")).isEqualTo("doris.cpu");
        assertThat(PlatformMetricNames.doris("cpu", null)).isEqualTo("doris.cpu");
    }
}
