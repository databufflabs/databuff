package com.databuff.apm.ingest.metric;

import com.databuff.apm.common.metric.MetricSchemaRegistry;
import com.databuff.apm.common.model.OptimizedMetric;
import com.databuff.apm.common.serde.OptimizedMetricUtil;
import org.junit.jupiter.api.Test;

import java.util.Map;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OptimizedMetricAccumulatorTest {

    @Test
    void mergesAndEncodesDorisRow() {
        OptimizedMetricAccumulator accumulator = new OptimizedMetricAccumulator();
        OptimizedMetric metric = new OptimizedMetric()
                .withTsId(7)
                .withTimestamp(1_700_000_000_000_000_000L)
                .withMeasurement("service")
                .withTagValues("ok", "checkout", "svc-id", "inst")
                .withFieldValues(1, 0, 99);
        accumulator.merge(OptimizedMetricUtil.serialize(metric));

        List<byte[]> rows = accumulator.drainRows();
        assertThat(rows).hasSize(1);
        assertThat(new String(rows.get(0))).contains("\"ts\":1700000000000");
        assertThat(new String(rows.get(0))).contains("\"service\":\"checkout\"");
        assertThat(new String(rows.get(0))).contains("\"cnt\":1");
        assertThat(new String(rows.get(0))).contains("\"maxDuration\":99");
    }

    @Test
    void mergesLegacyTraceMaxDurationAcrossSamples() throws Exception {
        OptimizedMetricAccumulator accumulator = new OptimizedMetricAccumulator();
        OptimizedMetric first = new OptimizedMetric()
                .withTsId(7)
                .withTimestamp(1_700_000_000_000_000_000L)
                .withMeasurement("service")
                .withTagValues("ok", "checkout", "svc-id", "inst")
                .withFieldValues(1, 0, 80, 80);
        OptimizedMetric second = new OptimizedMetric()
                .withTsId(7)
                .withTimestamp(1_700_000_000_000_000_000L)
                .withMeasurement("service")
                .withTagValues("ok", "checkout", "svc-id", "inst")
                .withFieldValues(1, 0, 120, 120);
        accumulator.merge(first);
        accumulator.merge(second);

        String json = new String(accumulator.drainRows().get(0));
        assertThat(json).contains("\"cnt\":2");
        assertThat(json).contains("\"sumDuration\":200");
        assertThat(json).contains("\"maxDuration\":120");
    }

    @Test
    void encodesServiceTraceMeasurement() throws Exception {
        OptimizedMetricAccumulator accumulator = new OptimizedMetricAccumulator();
        OptimizedMetric metric = new OptimizedMetric()
                .withTsId(8)
                .withTimestamp(1_700_000_000_000_000_000L)
                .withMeasurement("service.trace")
                .withTagValues("ok", "host", "GET", "200", "/api", "checkout", "svc-id", "inst")
                .withFieldValues(2, 1, 50);
        accumulator.merge(OptimizedMetricUtil.serialize(metric));

        assertThat(new String(accumulator.drainRows().get(0))).contains("\"resource\":\"/api\"");
    }

    @Test
    void encodesServiceFlowMeasurement() throws Exception {
        OptimizedMetricAccumulator accumulator = new OptimizedMetricAccumulator();
        OptimizedMetric metric = new OptimizedMetric()
                .withTsId(9)
                .withTimestamp(1_700_000_000_000_000_000L)
                .withMeasurement("service.flow");
        Map<String, String> tags = new java.util.LinkedHashMap<>();
        tags.put("parentService", "gateway");
        tags.put("parentServiceId", "gw-id");
        tags.put("service", "checkout");
        tags.put("service_id", "co-id");
        tags.put("resource", "/api");
        metric = metric.withTagValues(
                MetricSchemaRegistry.tagValuesFromMap("service.flow", tags))
                .withFieldValues(3, 0, 90);
        accumulator.merge(OptimizedMetricUtil.serialize(metric));

        String json = new String(accumulator.drainRows().get(0));
        assertThat(json).contains("\"parentService\":\"gateway\"");
        assertThat(json).contains("\"service\":\"checkout\"");
    }

    @Test
    void encodesServiceHttpMeasurement() throws Exception {
        OptimizedMetricAccumulator accumulator = new OptimizedMetricAccumulator();
        Map<String, String> tags = new java.util.LinkedHashMap<>();
        tags.put("durationRange", "100-200ms");
        tags.put("httpCode", "200");
        tags.put("httpMethod", "GET");
        tags.put("service", "checkout");
        tags.put("service_id", "svc-id");
        tags.put("service_instance", "inst");
        tags.put("url", "/api/cart");
        OptimizedMetric metric = new OptimizedMetric()
                .withTsId(10)
                .withTimestamp(1_700_000_000_000_000_000L)
                .withMeasurement("service.http")
                .withTagValues(
                        MetricSchemaRegistry.tagValuesFromMap("service.http", tags))
                .withFieldValues(4, 0, 150);
        accumulator.merge(OptimizedMetricUtil.serialize(metric));

        String json = new String(accumulator.drainRows().get(0));
        assertThat(json).contains("\"durationRange\":\"100-200ms\"");
        assertThat(json).contains("\"url\":\"/api/cart\"");
    }

    @Test
    void encodesServiceRpcMeasurement() throws Exception {
        OptimizedMetricAccumulator accumulator = new OptimizedMetricAccumulator();
        Map<String, String> tags = new java.util.LinkedHashMap<>();
        tags.put("durationRange", "100-200ms");
        tags.put("type", "dubbo");
        tags.put("statusCode", "0");
        tags.put("service", "checkout");
        tags.put("service_id", "svc-id");
        tags.put("service_instance", "inst");
        tags.put("resource", "com.demo.OrderService/findInventory");
        OptimizedMetric metric = new OptimizedMetric()
                .withTsId(13)
                .withTimestamp(1_700_000_000_000_000_000L)
                .withMeasurement("service.rpc")
                .withTagValues(
                        MetricSchemaRegistry.tagValuesFromMap("service.rpc", tags))
                .withFieldValues(5, 0, 160);
        accumulator.merge(OptimizedMetricUtil.serialize(metric));

        String json = new String(accumulator.drainRows().get(0));
        assertThat(json).contains("\"type\":\"dubbo\"");
        assertThat(json).contains("\"resource\":\"com.demo.OrderService/findInventory\"");
    }

    @Test
    void encodesServiceDbMeasurement() throws Exception {
        OptimizedMetricAccumulator accumulator = new OptimizedMetricAccumulator();
        Map<String, String> tags = new java.util.LinkedHashMap<>();
        tags.put("durationRange", "100-200ms");
        tags.put("dbType", "mysql");
        tags.put("sqlDatabase", "demo_apm");
        tags.put("sqlOperation", "SELECT");
        tags.put("service", "checkout");
        tags.put("service_id", "svc-id");
        tags.put("service_instance", "inst");
        tags.put("sqlContent", "SELECT id FROM demo_order");
        OptimizedMetric metric = new OptimizedMetric()
                .withTsId(14)
                .withTimestamp(1_700_000_000_000_000_000L)
                .withMeasurement("service.db")
                .withTagValues(
                        MetricSchemaRegistry.tagValuesFromMap("service.db", tags))
                .withFieldValues(6, 0, 170);
        accumulator.merge(OptimizedMetricUtil.serialize(metric));

        String json = new String(accumulator.drainRows().get(0));
        assertThat(json).contains("\"dbType\":\"mysql\"");
        assertThat(json).contains("\"sqlDatabase\":\"demo_apm\"");
        assertThat(json).contains("\"sqlOperation\":\"SELECT\"");
    }

    @Test
    void encodesUnknownMeasurementWithGenericTags() throws Exception {
        OptimizedMetricAccumulator accumulator = new OptimizedMetricAccumulator();
        OptimizedMetric metric = new OptimizedMetric()
                .withTsId(11)
                .withTimestamp(1_700_000_000_000_000_000L)
                .withMeasurement("custom.metric")
                .withTagValues("a", "b")
                .withFieldValues(9);
        accumulator.merge(OptimizedMetricUtil.serialize(metric));

        assertThat(new String(accumulator.drainRows().get(0))).contains("\"tag0\":\"a\"");
    }

    @Test
    void encodesServiceExceptionMeasurement() throws Exception {
        OptimizedMetricAccumulator accumulator = new OptimizedMetricAccumulator();
        OptimizedMetric metric = new OptimizedMetric()
                .withTsId(12)
                .withTimestamp(1_700_000_000_000_000_000L)
                .withMeasurement("service.exception");
        Map<String, String> tags = new java.util.LinkedHashMap<>();
        tags.put("exceptionName", "HTTP 500");
        tags.put("service", "checkout");
        tags.put("service_id", "svc-id");
        tags.put("service_instance", "inst");
        metric = metric.withTagValues(
                        MetricSchemaRegistry.tagValuesFromMap(
                                "service.exception", tags))
                .withFieldValues(3);
        accumulator.merge(OptimizedMetricUtil.serialize(metric));

        String json = new String(accumulator.drainRows().get(0));
        assertThat(json).contains("\"exceptionName\":\"HTTP 500\"");
        assertThat(json).contains("\"cnt\":3");
    }

    @Test
    void ignoresEmptyPayload() {
        OptimizedMetricAccumulator accumulator = new OptimizedMetricAccumulator();
        accumulator.merge(new byte[0]);
        assertThat(accumulator.size()).isZero();
    }
}
