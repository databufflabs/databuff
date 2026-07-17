package com.databuff.apm.common.serde;

import com.databuff.apm.common.metric.MetricSchemaRegistry;
import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.model.OptimizedMetric;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SerdeRoundTripTest {

    @Test
    void spanEncoderDecoderRoundTrip() throws Exception {
        DcSpan span = sampleSpan();
        byte[] encoded = DCSpanJsonEncoder.encode(span);
        DcSpan decoded = DCSpanJsonDecoder.decode(encoded, true);
        assertThat(decoded.trace_id).isEqualTo(span.trace_id);
        assertThat(decoded.service).isEqualTo(span.service);
        assertThat(decoded.duration).isEqualTo(span.duration);
    }

    @Test
    void optimizedMetricRoundTrip() {
        OptimizedMetric metric = new OptimizedMetric()
                .withTsId(42)
                .withTimestamp(1_700_000_000_000_000_000L)
                .withMeasurement("service")
                .withTagValues("ok", "checkout", "svc-key", "inst-1")
                .withFieldValues(1, 0, 120_000_000L);

        byte[] bytes = OptimizedMetricUtil.serialize(metric);
        assertThat(OptimizedMetricUtil.readTsId(bytes)).isEqualTo(42);
        assertThat(OptimizedMetricUtil.readTimestamp(bytes)).isEqualTo(metric.timestamp());

        OptimizedMetric decoded = OptimizedMetricUtil.deserialize(bytes);
        assertThat(decoded.measurement()).isEqualTo("service");
        assertThat(decoded.fieldValues()).containsExactly(1, 0, 120_000_000L);
    }

    @Test
    void optimizedMetricMergeAddsFields() {
        OptimizedMetric left = new OptimizedMetric()
                .withTsId(1)
                .withTimestamp(100L)
                .withMeasurement("service")
                .withTagValues("ok", "a", "id", "i")
                .withFieldValues(2, 1, 50);
        OptimizedMetric right = left.withFieldValues(3, 0, 70);
        OptimizedMetric merged = left.merge(right);
        // Legacy trace merge normalizes 3-field payloads to (cnt, error, sumDuration, maxDuration).
        assertThat(merged.fieldValues()).containsExactly(5, 1, 120, 0);
    }

    @Test
    void traceIdCanBeReadWithoutFullDecode() throws Exception {
        DcSpan span = sampleSpan();
        byte[] encoded = DCSpanJsonEncoder.encode(span);
        assertThat(DCTraceUtil.getTraceId(encoded)).isEqualTo("abc123");
    }

    @Test
    void spanMetricExtraction() {
        DcSpan span = sampleSpan();
        span.type = "SPAN_KIND_SERVER";
        span.isIn = 1;
        List<OptimizedMetric> metrics = DcSpanUtil.parseSpanData(span);
        assertThat(metrics).hasSize(3);
        assertThat(metrics.get(0).measurement()).isEqualTo("service");
        assertThat(metrics.get(0).fieldValues()[0]).isEqualTo(1);
        assertThat(metrics.stream().map(OptimizedMetric::measurement)).contains("service.http");
    }

    @Test
    void spanMetricExtractionIncludesServiceHttpTags() {
        DcSpan span = sampleSpan();
        span.type = "SPAN_KIND_SERVER";
        span.isIn = 1;
        span.metaHttpUrl = "/api/cart";
        OptimizedMetric http = DcSpanUtil.parseSpanData(span).stream()
                .filter(m -> "service.http".equals(m.measurement()))
                .findFirst()
                .orElseThrow();
        assertThat(tagValue(http, "httpCode")).isEqualTo("200");
        assertThat(tagValue(http, "httpMethod")).isEqualTo("GET");
        assertThat(tagValue(http, "url")).isEqualTo("/api/cart");
    }

    private static String tagValue(OptimizedMetric metric, String column) {
        String[] tags = metric.tagValues();
        var schema = MetricSchemaRegistry.schema(metric.measurement()).orElseThrow();
        int index = schema.tagColumns().indexOf(column);
        return index >= 0 && index < tags.length ? tags[index] : "";
    }

    @Test
    void spanMetricExtractionSkipsInternalChildSpan() {
        DcSpan root = sampleSpan();
        DcSpan child = sampleSpan();
        child.parent_id = root.span_id;
        child.is_parent = 0;
        child.isIn = 0;
        child.resource = "SELECT demo_order";
        child.metaHttpMethod = null;
        child.metaHttpStatusCode = null;

        List<OptimizedMetric> metrics = DcSpanUtil.parseSpanData(child);
        assertThat(metrics.stream().map(OptimizedMetric::measurement))
                .doesNotContain("service", "service.http", "service.trace");
    }

    @Test
    void spanMetricExtractionCountsCrossServiceInboundSpan() {
        DcSpan inbound = sampleSpan();
        inbound.parent_id = "parent-span";
        inbound.is_parent = 0;
        inbound.isIn = 1;
        inbound.type = "SPAN_KIND_SERVER";
        inbound.service = "service-b";
        inbound.serviceId = "service-b-id";

        List<OptimizedMetric> metrics = DcSpanUtil.parseSpanData(inbound);
        assertThat(metrics.stream().map(OptimizedMetric::measurement))
                .contains("service", "service.http")
                .doesNotContain("service.trace");
    }

    @Test
    void spanMetricExtractionCountsCrossServiceOutboundHttpSpan() {
        DcSpan outbound = sampleSpan();
        outbound.parent_id = "parent-span";
        outbound.isOut = 1;
        outbound.metaHttpUrl = "http://service-b:8080/api/orders/10001";

        List<OptimizedMetric> metrics = DcSpanUtil.parseSpanData(outbound);
        assertThat(metrics.stream().map(OptimizedMetric::measurement)).contains("service.http");
        assertThat(metrics.stream().map(OptimizedMetric::measurement)).doesNotContain("service");
        OptimizedMetric http = metrics.stream()
                .filter(m -> "service.http".equals(m.measurement()))
                .findFirst()
                .orElseThrow();
        assertThat(tagValue(http, "url")).isEqualTo("/api/orders/10001");
    }

    @Test
    void normalizeHttpUrlStripsAuthority() {
        assertThat(DcSpanUtil.normalizeHttpUrl("http://service-b:8080/api/orders/10001"))
                .isEqualTo("/api/orders/10001");
        assertThat(DcSpanUtil.normalizeHttpUrl("/demo/checkout")).isEqualTo("/demo/checkout");
    }

    @Test
    void spanMetricExtractionIncludesServiceFlow() {
        DcSpan span = sampleSpan();
        span.type = "SPAN_KIND_SERVER";
        span.isIn = 1;
        span.srcService = "gateway";
        span.srcServiceId = "gw";
        span.dstService = "checkout";
        span.dstServiceId = "co";
        List<OptimizedMetric> metrics = DcSpanUtil.parseSpanData(span);
        assertThat(metrics).hasSize(3);
        assertThat(metrics.stream().map(OptimizedMetric::measurement))
                .contains("service", "service.trace", "service.http")
                .doesNotContain("service.flow");
    }

    @Test
    void spanDecoderWithoutIgnoreMap() throws Exception {
        DcSpan span = sampleSpan();
        span.meta = "{\"k\":\"v\"}";
        byte[] encoded = DCSpanJsonEncoder.encode(span);
        DcSpan decoded = DCSpanJsonDecoder.decode(encoded, false);
        assertThat(decoded.meta).isEqualTo("{\"k\":\"v\"}");
    }

    @Test
    void spanDecoderWithIgnoreMapPreservesMetaForClusterForward() throws Exception {
        DcSpan span = sampleSpan();
        span.type = "SPAN_KIND_CLIENT";
        span.metaHttpMethod = null;
        span.metaHttpStatusCode = null;
        span.metaHttpUrl = null;
        span.resource = "SELECT 1";
        span.name = "SELECT 1";
        span.meta = "{\"db.system\":\"mysql\",\"db.statement\":\"SELECT 1\",\"server.address\":\"mysql\"}";
        span.metaPeerHostname = "mysql";
        span.metaErrorType = "SQLException";
        byte[] encoded = DCSpanJsonEncoder.encode(span);
        DcSpan decoded = DCSpanJsonDecoder.decode(encoded, true);
        assertThat(decoded.meta).isEqualTo(span.meta);
        assertThat(decoded.metaPeerHostname).isEqualTo("mysql");
        assertThat(decoded.metaErrorType).isEqualTo("SQLException");
        assertThat(DcSpanUtil.isDbSpan(decoded)).isTrue();
    }

    @Test
    void tracePackageExpandsArray() throws Exception {
        DcSpan span = sampleSpan();
        byte[] packageBytes = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsBytes(java.util.List.of(span));
        assertThat(DCTraceUtil.spansFromPackage(packageBytes)).hasSize(1);
    }

    @Test
    void optimizedMetricMergeHelpersHandleEmpty() {
        byte[] payload = OptimizedMetricUtil.serialize(sampleMetric());
        assertThat(OptimizedMetricUtil.mergeSerializedBytes(new byte[0], payload)).isEqualTo(payload);
        assertThat(OptimizedMetricUtil.readTsId(new byte[0])).isZero();
        assertThat(OptimizedMetricUtil.readTimestamp(new byte[3])).isZero();
    }

    @Test
    void detectsJsonPassthroughBytes() {
        assertThat(OptimizedMetricUtil.isOptimizedFormat("{\"cnt\":1}".getBytes())).isFalse();
        assertThat(OptimizedMetricUtil.isOptimizedFormat("42".getBytes())).isFalse();
        assertThat(OptimizedMetricUtil.isOptimizedFormat(OptimizedMetricUtil.serialize(sampleMetric()))).isTrue();
    }

    @Test
    void mergeSerializedBytesConcatenatesJsonPayloads() {
        byte[] left = "{\"a\":1}".getBytes();
        byte[] right = "{\"b\":2}".getBytes();
        byte[] merged = OptimizedMetricUtil.mergeSerializedBytes(left, right);
        assertThat(new String(merged)).contains("{\"a\":1}{\"b\":2}");
    }

    private static OptimizedMetric sampleMetric() {
        return new OptimizedMetric()
                .withTsId(1)
                .withTimestamp(100L)
                .withMeasurement("service")
                .withTagValues("ok", "a", "id", "i")
                .withFieldValues(1, 0, 10);
    }

    private static DcSpan sampleSpan() {
        DcSpan span = new DcSpan();
        span.trace_id = "abc123";
        span.span_id = "span-1";
        span.parent_id = "";
        span.is_parent = 1;
        span.service = "checkout";
        span.serviceId = "svc-key";
        span.serviceInstance = "inst-1";
        span.resource = "GET /cart";
        span.name = "GET /cart";
        span.hostName = "host-1";
        span.error = 0;
        span.duration = 120_000_000L;
        span.start = 1_700_000_000_000_000_000L;
        span.end = span.start + span.duration;
        span.metaHttpMethod = "GET";
        span.metaHttpStatusCode = 200;
        return span;
    }
}
