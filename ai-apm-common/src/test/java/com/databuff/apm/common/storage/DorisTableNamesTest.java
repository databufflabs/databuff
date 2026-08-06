package com.databuff.apm.common.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DorisTableNamesTest {

    @Test
    void metricTable_delegatesToSchemaRegistry() {
        assertThat(DorisTableNames.metricTable("service.flow")).isEqualTo("metric_service_flow");
        assertThat(DorisTableNames.metricTable("jvm")).isEqualTo("metric_jvm");
        assertThat(DorisTableNames.metricTable("service.http")).isEqualTo("metric_service_http");
    }

    @Test
    void exposesCoreTableConstants() {
        assertThat(DorisTableNames.METRIC_PLATFORM).isEqualTo("metric_platform");
        assertThat(DorisTableNames.TRACE_DC_SPAN).isEqualTo("trace_dc_span");
        assertThat(DorisTableNames.META_SERVICE).isEqualTo("meta_service");
    }
}
