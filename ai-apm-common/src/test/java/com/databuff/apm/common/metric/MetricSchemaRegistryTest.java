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
