package com.databuff.apm.common.storage;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import com.databuff.apm.common.metric.MetricSchemaRegistry;

import static org.assertj.core.api.Assertions.assertThat;

class DorisVarcharLimitsTest {

    @Test
    void truncateLeavesShortValuesUnchanged() {
        assertThat(DorisVarcharLimits.truncate("ok", 10)).isEqualTo("ok");
        assertThat(DorisVarcharLimits.truncate(null, 10)).isNull();
    }

    @Test
    void truncateCutsToMaxLength() {
        assertThat(DorisVarcharLimits.truncate("abcdefghij", 4)).isEqualTo("abcd");
    }

    @Test
    void truncateMetricTagUsesUrlLimit() {
        String longUrl = "https://example.com/" + "x".repeat(DorisVarcharLimits.URL);
        assertThat(DorisVarcharLimits.truncateMetricTag("url", longUrl))
                .hasSize(DorisVarcharLimits.URL);
        assertThat(DorisVarcharLimits.truncateMetricTag("connectionPoolUrl", longUrl))
                .hasSize(DorisVarcharLimits.URL);
    }

    @Test
    void truncateMetricTagUsesResourceLimit() {
        String longSql = "SELECT " + "a,".repeat(DorisVarcharLimits.SQL_CONTENT);
        assertThat(DorisVarcharLimits.truncateMetricTag("sqlContent", longSql))
                .hasSize(DorisVarcharLimits.SQL_CONTENT);
        assertThat(DorisVarcharLimits.truncateMetricTag("resource", longSql))
                .hasSize(DorisVarcharLimits.RESOURCE);
    }

    @Test
    void applyTagValuesTruncatesLongHttpUrl() {
        String longUrl = "https://svc/" + "p".repeat(DorisVarcharLimits.URL);
        String[] tags = MetricSchemaRegistry.tagValuesFromMap("service.http", Map.of(
                "url", longUrl,
                "service", "checkout",
                "service_id", "co"));
        Map<String, Object> row = new HashMap<>();
        MetricSchemaRegistry.applyTagValues(row, "service.http", tags);
        assertThat(row.get("url")).isInstanceOf(String.class);
        assertThat((String) row.get("url")).hasSize(DorisVarcharLimits.URL);
    }
}
