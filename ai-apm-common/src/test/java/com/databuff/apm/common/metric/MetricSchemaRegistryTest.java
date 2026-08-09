package com.databuff.apm.common.metric;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricSchemaRegistryTest {

    @Test
    void loadsAllCatalogMeasurements() {
        assertThat(MetricSchemaRegistry.allTableNames()).hasSize(27);
        assertThat(MetricSchemaRegistry.isKnownMeasurement("service.http")).isTrue();
        assertThat(MetricSchemaRegistry.isKnownMeasurement("jvm.memory.heap")).isTrue();
        assertThat(MetricSchemaRegistry.isKnownMeasurement("business.service")).isFalse();
    }

    @Test
    void mapsMeasurementToTableName() {
        assertThat(MetricSchemaRegistry.tableName("service.rpc")).isEqualTo("metric_service_rpc");
        assertThat(MetricSchemaRegistry.tableName("service.remote")).isEqualTo("metric_service_remote");
        assertThat(MetricSchemaRegistry.tableName("jvm.buffer_pool")).isEqualTo("metric_jvm");
        assertThat(MetricSchemaRegistry.tableName("jvm.gc")).isEqualTo("metric_jvm");
    }

    @Test
    void resolvesLongestMeasurementFirst() {
        assertThat(MetricSchemaRegistry.measurementsSortedLongestFirst().get(0))
                .startsWith("service.");
    }

    @Test
    void appliesLegacyTraceFields() {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        MetricSchemaRegistry.applyFieldValues(row, "service.http", new long[] {10, 2, 900});
        assertThat(row.get("cnt")).isEqualTo(10L);
        assertThat(row.get("error")).isEqualTo(2L);
        assertThat(row.get("sumDuration")).isEqualTo(900L);
        assertThat(row.get("maxDuration")).isEqualTo(0);
        // error must not leak into slow
        assertThat(row.get("slow")).isEqualTo(0L);
    }

    @Test
    void errorDoesNotInflateSlowAcrossComponentTypes() {
        for (String measurement : java.util.List.of(
                "service.http",
                "service.rpc",
                "service.db",
                "service.redis",
                "service.mq",
                "service.remote",
                "service.config")) {
            java.util.Map<String, Object> poisonedLegacy = new java.util.LinkedHashMap<>();
            // Pre-fix shape: only (cnt, error, sumDuration) — slow must stay 0, not copy error.
            MetricSchemaRegistry.applyFieldValues(poisonedLegacy, measurement, new long[] {5, 3, 100});
            assertThat(poisonedLegacy.get("error"))
                    .as("%s error", measurement)
                    .isEqualTo(3L);
            assertThat(poisonedLegacy.get("slow"))
                    .as("%s slow must not copy error", measurement)
                    .isEqualTo(0L);

            java.util.Map<String, Object> withSlowField = new java.util.LinkedHashMap<>();
            MetricSchemaRegistry.applyFieldValues(
                    withSlowField, measurement, new long[] {5, 3, 100, 80, 1});
            assertThat(withSlowField.get("error")).as("%s error", measurement).isEqualTo(3L);
            assertThat(withSlowField.get("slow")).as("%s slow from 5th field", measurement).isEqualTo(1L);
        }
    }

    @Test
    void serviceFlowKeepsPositionalFiveFieldLayout() {
        // ServiceFlowExtractor writes (cnt, error, slow, srcCall, sumDuration).
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        MetricSchemaRegistry.applyFieldValues(row, "service.flow", new long[] {1, 0, 1, 2, 100});
        assertThat(row.get("cnt")).isEqualTo(1L);
        assertThat(row.get("error")).isEqualTo(0L);
        assertThat(row.get("slow")).isEqualTo(1L);
        assertThat(row.get("srcCall")).isEqualTo(2L);
        assertThat(row.get("sumDuration")).isEqualTo(100L);
    }

    @Test
    void appliesLegacyTraceSlowFromFifthField() {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        MetricSchemaRegistry.applyFieldValues(row, "service.db", new long[] {3, 2, 900, 400, 1});
        assertThat(row.get("cnt")).isEqualTo(3L);
        assertThat(row.get("error")).isEqualTo(2L);
        assertThat(row.get("slow")).isEqualTo(1L);
        assertThat(row.get("sumDuration")).isEqualTo(900L);
        assertThat(row.get("maxDuration")).isEqualTo(400L);
    }

    @Test
    void appliesLegacyTraceSlowFromIsSlowTagWhenFifthFieldAbsent() {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("isSlow", "1");
        MetricSchemaRegistry.applyFieldValues(row, "service.db", new long[] {4, 1, 800, 200});
        assertThat(row.get("error")).isEqualTo(1L);
        assertThat(row.get("slow")).isEqualTo(4L);
    }

    @Test
    void appliesLegacyTraceMaxDurationFromFourthField() {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        MetricSchemaRegistry.applyFieldValues(row, "service", new long[] {3, 0, 900, 500});
        assertThat(row.get("cnt")).isEqualTo(3L);
        assertThat(row.get("sumDuration")).isEqualTo(900L);
        assertThat(row.get("maxDuration")).isEqualTo(500L);
    }

    @Test
    void appliesLegacyTraceMaxDurationForSingleSample() {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        MetricSchemaRegistry.applyFieldValues(row, "service", new long[] {1, 0, 250, 0});
        assertThat(row.get("maxDuration")).isEqualTo(250L);
    }

    @Test
    void appliesLegacyTraceFieldsForServiceRemote() {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        MetricSchemaRegistry.applyFieldValues(row, "service.remote", new long[] {14, 7, 98_000_000L});
        assertThat(row.get("cnt")).isEqualTo(14L);
        assertThat(row.get("error")).isEqualTo(7L);
        assertThat(row.get("sumDuration")).isEqualTo(98_000_000L);
        assertThat(row.get("cpuTime")).isEqualTo(0);
    }

    @Test
    void exposesOtlpAndTraceDerivedFlags() {
        assertThat(MetricSchemaRegistry.isTraceDerived("service.trace")).isTrue();
        assertThat(MetricSchemaRegistry.isOtlpMeasurement("jvm.memory")).isTrue();
        assertThat(MetricSchemaRegistry.isOtlpMeasurement("service.http")).isFalse();
        assertThat(MetricSchemaRegistry.isOtlpMeasurement(null)).isFalse();
        assertThat(MetricSchemaRegistry.allMeasurements()).contains("service.http");
    }

    @Test
    void mapsColumnsTagsAndUnknownMeasurements() {
        assertThat(MetricSchemaRegistry.toColumnName("serviceId")).isEqualTo("service_id");
        assertThat(MetricSchemaRegistry.streamLoadJsonKey("service.instance")).isEqualTo("service_instance");
        java.util.Map<String, Object> unknownTags = new java.util.LinkedHashMap<>();
        MetricSchemaRegistry.applyTagValues(unknownTags, "unknown.measurement", new String[] {"a", "b"});
        assertThat(unknownTags).containsEntry("tag0", "a");
        java.util.Map<String, Object> unknownFields = new java.util.LinkedHashMap<>();
        MetricSchemaRegistry.applyFieldValues(unknownFields, "unknown.measurement", new long[] {1, 2});
        assertThat(unknownFields).containsEntry("field0", 1L);
        assertThat(MetricSchemaRegistry.tagValuesFromMap(
                "service.http", java.util.Map.of("service", "checkout", "url", "/api")))
                .isNotEmpty();
    }
}
