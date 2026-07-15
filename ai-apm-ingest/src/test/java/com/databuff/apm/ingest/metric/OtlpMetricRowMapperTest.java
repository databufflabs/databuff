package com.databuff.apm.ingest.metric;

import com.databuff.apm.ingest.otel.OtlMetricLine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OtlpMetricRowMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void mapsOtelThreadCountMetric() throws Exception {
        byte[] raw = JSON.writeValueAsBytes(Map.of(
                "ts", 1_700_000_000_000L,
                "service", "billing",
                "service_id", "abc123",
                "service_instance", "inst-1",
                "metric", "jvm.threads.count",
                "value", 42));

        OtlpMetricRowMapper.MappedRow mapped = OtlpMetricRowMapper.map(raw).orElseThrow();
        assertThat(mapped.table()).isEqualTo("metric_jvm");
        assertThat(new String(mapped.rowBytes())).contains("\"thread_count\":42");
    }

    @Test
    void mapsHeapMemoryUsedWithAttributes() throws Exception {
        byte[] raw = JSON.writeValueAsBytes(Map.of(
                "ts", 1_700_000_000_000L,
                "service", "billing",
                "service_id", "abc123",
                "metric", "jvm.memory.used",
                "attributes", Map.of("jvm.memory.type", "heap"),
                "value", 1024));

        OtlpMetricRowMapper.MappedRow mapped = OtlpMetricRowMapper.map(raw).orElseThrow();
        assertThat(new String(mapped.rowBytes())).contains("\"memory_heap_used\":1024");
    }

    @Test
    void mapsJvmGcMetricFromOtlpLine() throws Exception {
        OtlMetricLine line = new OtlMetricLine(
                1_700_000_000_000L,
                "service-a",
                "service-a",
                "jvm.gc.minor_collection_count",
                3,
                "service-a-1",
                "demo-host-a",
                null,
                null,
                null,
                null,
                null,
                null);
        OtlpMetricRowMapper.MappedRow mapped = OtlpMetricRowMapper.map(line).orElseThrow();
        assertThat(mapped.table()).isEqualTo("metric_jvm");
        assertThat(new String(mapped.rowBytes())).contains("\"gc_minor_collection_count\":3");
    }

    @Test
    void mapsJvmGcMetricToJvmTable() throws Exception {
        byte[] raw = JSON.writeValueAsBytes(Map.of(
                "ts", 1_700_000_000_000L,
                "service", "billing",
                "service_id", "abc123",
                "service_instance", "inst-1",
                "metric", "jvm.gc.major_collection_count",
                "value", 9));

        OtlpMetricRowMapper.MappedRow mapped = OtlpMetricRowMapper.map(raw).orElseThrow();
        assertThat(mapped.table()).isEqualTo("metric_jvm");
        assertThat(new String(mapped.rowBytes())).contains("\"gc_major_collection_count\":9");
    }

    @Test
    void mapsJvmMetricToJvmTable() throws Exception {
        byte[] raw = JSON.writeValueAsBytes(Map.of(
                "ts", 1_700_000_000_000L,
                "service", "billing",
                "service_id", "abc123",
                "service_instance", "inst-1",
                "tag_host", "host-1",
                "metric", "jvm.thread_count",
                "value", 42));

        OtlpMetricRowMapper.MappedRow mapped = OtlpMetricRowMapper.map(raw).orElseThrow();
        assertThat(mapped.table()).isEqualTo("metric_jvm");
        assertThat(new String(mapped.rowBytes())).contains("\"thread_count\":42");
    }

    @Test
    void mapsThreadPoolMetric() throws Exception {
        byte[] raw = JSON.writeValueAsBytes(Map.of(
                "ts", 1_700_000_000_000L,
                "service", "billing",
                "service_id", "abc123",
                "metric", "service.thread.pool.poolSize",
                "threadPoolName", "http-worker",
                "value", 16));

        OtlpMetricRowMapper.MappedRow mapped = OtlpMetricRowMapper.map(raw).orElseThrow();
        assertThat(mapped.table()).isEqualTo("metric_service_thread_pool");
        assertThat(new String(mapped.rowBytes())).contains("http-worker");
    }

    @Test
    void mapsPoolMaxSizeMetric() throws Exception {
        byte[] raw = JSON.writeValueAsBytes(Map.of(
                "ts", 1_700_000_000_000L,
                "service", "service-a",
                "service_id", "service-a",
                "metric", "service.http.connection.pool.maxSize",
                "httpConnectionPoolName", "default",
                "value", 100));

        OtlpMetricRowMapper.MappedRow mapped = OtlpMetricRowMapper.map(raw).orElseThrow();
        assertThat(mapped.table()).isEqualTo("metric_service_http_connection_pool");
        assertThat(new String(mapped.rowBytes())).contains("\"maxSize\":100");
    }

    @Test
    void skipsUnknownMetric() throws Exception {
        byte[] raw = JSON.writeValueAsBytes(Map.of(
                "ts", 1_700_000_000_000L,
                "service", "billing",
                "service_id", "abc123",
                "metric", "custom.counter",
                "value", 1));
        assertThat(OtlpMetricRowMapper.map(raw)).isEmpty();
    }
}
