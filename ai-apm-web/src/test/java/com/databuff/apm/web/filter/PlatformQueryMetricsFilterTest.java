package com.databuff.apm.web.filter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformQueryMetricsFilterTest {

    @ParameterizedTest
    @CsvSource({
            "/webapi/trace/spans, trace",
            "/webapi/metric/query, metric",
            "/webapi/metrics/catalog, metric",
            "/webapi/alarm/list, alarm",
            "/webapi/monitor/events, alarm",
            "/webapi/api/v1/ai/chat, ai",
            "/webapi/log/search, log",
            "/webapi/service/list, portal",
            "/webapi/cockpit/trafficLight, portal",
            "/webapi/business/overview, portal",
            "/webapi/globalTopology/graph, portal",
            "/webapi/user/login, other",
            "/mcp, ai",
            "/mcp/sse, ai"
    })
    void resolveDomainMapsPathPrefix(String path, String expected) {
        assertThat(PlatformQueryMetricsFilter.resolveDomain(path)).isEqualTo(expected);
    }

    @Test
    void resolveDomainHandlesNullAndNonWebapi() {
        assertThat(PlatformQueryMetricsFilter.resolveDomain(null)).isEqualTo("other");
        assertThat(PlatformQueryMetricsFilter.resolveDomain("/assets/app.js")).isEqualTo("other");
    }
}
