package com.databuff.apm.ingest.otel;

import com.databuff.apm.common.meta.OtelAttributeMaps;
import com.databuff.apm.common.metric.MetricSchemaRegistry;
import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.model.OptimizedMetric;
import com.databuff.apm.common.serde.DCSpanJsonEncoder;
import com.databuff.apm.common.serde.DcSpanUtil;
import com.databuff.apm.common.trace.TraceSpanNames;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OtelConverterTest {

    private final OtelConverter converter = new OtelConverter();

    @Test
    void convertsTraceExportToDcSpan() throws Exception {
        ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(kv("service.name", "checkout"))
                                .addAttributes(kv("host.name", "host-1")))
                        .addScopeSpans(ScopeSpans.newBuilder()
                                .addSpans(Span.newBuilder()
                                        .setTraceId(ByteString.fromHex("0102030405060708090a0b0c0d0e0f10"))
                                        .setSpanId(ByteString.fromHex("0102030405060708"))
                                        .setName("GET /api")
                                        .setStartTimeUnixNano(1_700_000_000_000_000_000L)
                                        .setEndTimeUnixNano(1_700_000_100_000_000L))))
                .build();

        List<OtelConverter.ConvertedTrace> converted = converter.convertTraces(request);
        assertThat(converted).hasSize(1);
        DcSpan span = converted.get(0).span();
        assertThat(span.service).isEqualTo("checkout");
        assertThat(span.name).isEqualTo("GET /api");
        assertThat(converted.get(0).serviceKey()).hasSize(16);
        assertThat(new String(DCSpanJsonEncoder.encode(span))).contains("checkout");
    }

    @Test
    void convertsSpanWithHttpAttributesAndParent() throws Exception {
        ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(kv("service.name", "api")))
                        .addScopeSpans(ScopeSpans.newBuilder()
                                .addSpans(Span.newBuilder()
                                        .setTraceId(ByteString.fromHex("0102030405060708090a0b0c0d0e0f10"))
                                        .setSpanId(ByteString.fromHex("0102030405060708"))
                                        .setParentSpanId(ByteString.fromHex("0102030405060709"))
                                        .setName("POST /orders")
                                        .setStartTimeUnixNano(1_700_000_000_000_000_000L)
                                        .setEndTimeUnixNano(1_700_000_000_600_000_000L)
                                        .setStatus(Status.newBuilder()
                                                .setCode(Status.StatusCode.STATUS_CODE_ERROR))
                                        .addAttributes(kv("http.status_code", "500"))
                                        .addAttributes(kv("http.method", "POST"))
                                        .addAttributes(kv("url.full", "https://example/orders"))
                                        .addAttributes(kv("service.instance.id", "inst-1")))))
                .build();

        DcSpan span = converter.convertTraces(request).get(0).span();
        String json = new String(DCSpanJsonEncoder.encode(span));
        assertThat(json).contains("\"error\":1");
        assertThat(json).contains("\"slow\":1");
        assertThat(json).contains("\"is_parent\":0");
        assertThat(json).contains("POST");
        assertThat(json).contains("https://example/orders");
        assertThat(json).contains("inst-1");
    }

    @Test
    void fallsBackToHttpUrlWhenUrlFullMissing() throws Exception {
        ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(kv("service.name", "api")))
                        .addScopeSpans(ScopeSpans.newBuilder()
                                .addSpans(Span.newBuilder()
                                        .setName("GET")
                                        .setStartTimeUnixNano(1_700_000_000_000_000_000L)
                                        .setEndTimeUnixNano(1_700_000_010_000_000L)
                                        .addAttributes(kv("http.url", "/legacy")))))
                .build();

        assertThat(converter.convertTraces(request).get(0).span().metaHttpUrl).isEqualTo("/legacy");
    }

    @Test
    void usesUnknownHostWhenHostNameMissing() {
        ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(kv("service.name", "solo")))
                        .addScopeSpans(ScopeSpans.newBuilder()
                                .addSpans(Span.newBuilder()
                                        .setName("ping")
                                        .setStartTimeUnixNano(1_700_000_000_000_000_000L)
                                        .setEndTimeUnixNano(1_700_000_010_000_000L))))
                .build();

        assertThat(converter.convertTraces(request).get(0).span().hostName).isEqualTo("unknown");
    }

    @Test
    void resolvesServiceInstanceFromResourceAttributes() throws Exception {
        ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(kv("service.name", "api"))
                                .addAttributes(kv("service.instance.id", "resource-inst"))
                                .addAttributes(kv("k8s.namespace.name", "prod")))
                        .addScopeSpans(ScopeSpans.newBuilder()
                                .addSpans(Span.newBuilder()
                                        .setTraceId(ByteString.fromHex("0102030405060708090a0b0c0d0e0f10"))
                                        .setSpanId(ByteString.fromHex("0102030405060708"))
                                        .setName("GET /health")
                                        .setStartTimeUnixNano(1_700_000_000_000_000_000L)
                                        .setEndTimeUnixNano(1_700_000_010_000_000L))))
                .build();

        DcSpan span = converter.convertTraces(request).get(0).span();
        String json = new String(DCSpanJsonEncoder.encode(span));
        assertThat(span.serviceInstance).isEqualTo("resource-inst");
        assertThat(json).contains("k8s.namespace.name");
        assertThat(json).contains("prod");
    }

    @Test
    void convertsMetricSumExport() {
        ExportMetricsServiceRequest request = ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(io.opentelemetry.proto.metrics.v1.ResourceMetrics.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(kv("service.name", "billing")))
                        .addScopeMetrics(io.opentelemetry.proto.metrics.v1.ScopeMetrics.newBuilder()
                                .addMetrics(io.opentelemetry.proto.metrics.v1.Metric.newBuilder()
                                        .setName("requests")
                                        .setSum(io.opentelemetry.proto.metrics.v1.Sum.newBuilder()
                                                .addDataPoints(io.opentelemetry.proto.metrics.v1.NumberDataPoint.newBuilder()
                                                        .setAsInt(42))))))
                .build();

        List<OtelConverter.ConvertedMetric> converted = converter.convertMetrics(request);
        assertThat(converted).hasSize(1);
        assertThat(converted.get(0).line().value().doubleValue()).isEqualTo(42.0);
    }

    @Test
    void convertsMetricGaugeWithDoubleValue() {
        ExportMetricsServiceRequest request = ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(io.opentelemetry.proto.metrics.v1.ResourceMetrics.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(kv("service.name", "billing")))
                        .addScopeMetrics(io.opentelemetry.proto.metrics.v1.ScopeMetrics.newBuilder()
                                .addMetrics(io.opentelemetry.proto.metrics.v1.Metric.newBuilder()
                                        .setName("latency")
                                        .setGauge(io.opentelemetry.proto.metrics.v1.Gauge.newBuilder()
                                                .addDataPoints(io.opentelemetry.proto.metrics.v1.NumberDataPoint.newBuilder()
                                                        .setAsDouble(1.5))))))
                .build();

        assertThat(converter.convertMetrics(request).get(0).line().value()).isEqualTo(1.5);
    }

    @Test
    void convertsJvmMetricWithAttributes() {
        ExportMetricsServiceRequest request = ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(io.opentelemetry.proto.metrics.v1.ResourceMetrics.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(kv("service.name", "billing"))
                                .addAttributes(kv("host.name", "host-1"))
                                .addAttributes(kv("service.instance.id", "inst-1")))
                        .addScopeMetrics(io.opentelemetry.proto.metrics.v1.ScopeMetrics.newBuilder()
                                .addMetrics(io.opentelemetry.proto.metrics.v1.Metric.newBuilder()
                                        .setName("jvm.thread_count")
                                        .setGauge(io.opentelemetry.proto.metrics.v1.Gauge.newBuilder()
                                                .addDataPoints(io.opentelemetry.proto.metrics.v1.NumberDataPoint.newBuilder()
                                                        .setTimeUnixNano(1_700_000_000_000_000_000L)
                                                        .setAsInt(21)
                                                        .addAttributes(kv("thread.pool.name", "worker")))))))
                .build();

        OtlMetricLine line = converter.convertMetrics(request).get(0).line();
        assertThat(line.metric()).isEqualTo("jvm.thread_count");
        assertThat(line.tsMillis()).isEqualTo(1_700_000_000_000L);
        assertThat(line.serviceInstance()).isEqualTo("inst-1");
        assertThat(line.tagHost()).isEqualTo("host-1");
        assertThat(line.threadPoolName()).isEqualTo("worker");
    }

    @Test
    void convertsJvmGcDurationHistogram() {
        ExportMetricsServiceRequest request = ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(io.opentelemetry.proto.metrics.v1.ResourceMetrics.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(kv("service.name", "billing"))
                                .addAttributes(kv("host.name", "host-1")))
                        .addScopeMetrics(io.opentelemetry.proto.metrics.v1.ScopeMetrics.newBuilder()
                                .addMetrics(io.opentelemetry.proto.metrics.v1.Metric.newBuilder()
                                        .setName("jvm.gc.duration")
                                        .setHistogram(io.opentelemetry.proto.metrics.v1.Histogram.newBuilder()
                                                .addDataPoints(io.opentelemetry.proto.metrics.v1.HistogramDataPoint.newBuilder()
                                                        .setTimeUnixNano(1_700_000_000_000_000_000L)
                                                        .setSum(0.15)
                                                        .setCount(3)
                                                        .addAttributes(kv("jvm.gc.action", "end of minor GC")))))))
                .build();

        List<OtelConverter.ConvertedMetric> converted = converter.convertMetrics(request);
        assertThat(converted).hasSize(2);
        assertThat(converted).extracting(m -> m.line().metric())
                .containsExactly("jvm.gc.minor_collection_count", "jvm.gc.minor_collection_time");
        assertThat(converted.get(0).line().value()).isEqualTo(3L);
        assertThat(converted.get(1).line().value()).isEqualTo(0.15);
    }

    @Test
    void normalizesElasticsearchRestSpanNameForDatabuffProcessing() {
        ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(kv("service.name", "service-a")))
                        .addScopeSpans(ScopeSpans.newBuilder()
                                .addSpans(Span.newBuilder()
                                        .setTraceId(ByteString.fromHex("0102030405060708090a0b0c0d0e0f10"))
                                        .setSpanId(ByteString.fromHex("0102030405060708"))
                                        .setParentSpanId(ByteString.fromHex("0102030405060709"))
                                        .setName("orders/_search")
                                        .setKind(Span.SpanKind.SPAN_KIND_CLIENT)
                                        .setStartTimeUnixNano(1_700_000_000_000_000_000L)
                                        .setEndTimeUnixNano(1_700_000_050_000_000L)
                                        .addAttributes(kv("db.system", "elasticsearch"))
                                        .addAttributes(kv("db.elasticsearch.index", "orders"))
                                        .addAttributes(kv("http.method", "GET"))
                                        .addAttributes(kv("url.full", "http://es:9200/orders/_search"))
                                        .addAttributes(kv("server.address", "es")))))
                .build();

        DcSpan span = converter.convertTraces(request).get(0).span();
        assertThat(span.name).isEqualTo(TraceSpanNames.ES_REST_QUERY);
        assertThat(span.resource).isEqualTo("orders/_search");
        assertThat(span.metaHttpMethod).isNull();
        assertThat(span.metaHttpUrl).isNull();
        assertThat(OtelAttributeMaps.meta(span)).containsKey("http.method");
        assertThat(DcSpanUtil.isEsSpan(span)).isTrue();
        assertThat(DcSpanUtil.parseSpanData(span).stream().map(m -> m.measurement()))
                .contains("service.db")
                .doesNotContain("service.http", "service.es");
    }

    @Test
    void normalizesElasticsearchTransportSpanNameWhenHttpAttributesMissing() {
        ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(kv("service.name", "service-a")))
                        .addScopeSpans(ScopeSpans.newBuilder()
                                .addSpans(Span.newBuilder()
                                        .setName("search orders")
                                        .setKind(Span.SpanKind.SPAN_KIND_CLIENT)
                                        .setStartTimeUnixNano(1_700_000_000_000_000_000L)
                                        .setEndTimeUnixNano(1_700_000_010_000_000L)
                                        .addAttributes(kv("db.system", "elasticsearch"))
                                        .addAttributes(kv("db.operation", "search")))))
                .build();

        DcSpan span = converter.convertTraces(request).get(0).span();
        assertThat(span.name).isEqualTo(TraceSpanNames.ES_QUERY);
        assertThat(span.resource).isEqualTo("search orders");
        assertThat(DcSpanUtil.isEsSpan(span)).isTrue();
    }

    @Test
    void capturesDbAttributesAndPeerHostname() {
        ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(kv("service.name", "service-a")))
                        .addScopeSpans(ScopeSpans.newBuilder()
                                .addSpans(Span.newBuilder()
                                        .setTraceId(ByteString.fromHex("0102030405060708090a0b0c0d0e0f10"))
                                        .setSpanId(ByteString.fromHex("0102030405060708"))
                                        .setName("SELECT demo_order")
                                        .setKind(Span.SpanKind.SPAN_KIND_CLIENT)
                                        .setStartTimeUnixNano(1_700_000_000_000_000_000L)
                                        .setEndTimeUnixNano(1_700_000_050_000_000L)
                                        .addAttributes(kv("db.system", "mysql"))
                                        .addAttributes(kv("db.name", "demo_apm"))
                                        .addAttributes(kv("db.statement", "SELECT id FROM demo_order WHERE id = ?"))
                                        .addAttributes(kv("server.address", "mysql")))))
                .build();

        DcSpan span = converter.convertTraces(request).get(0).span();
        assertThat(span.metaPeerHostname).isEqualTo("mysql");
        assertThat(OtelAttributeMaps.meta(span)).containsKey("db.system");
        assertThat(OtelAttributeMaps.meta(span)).containsKey("db.statement");
        assertThat(span.type).isEqualTo("SPAN_KIND_CLIENT");
    }

    @Test
    void skipsResourcesWithoutServiceName() {
        ExportTraceServiceRequest traceRequest = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .setResource(Resource.newBuilder()))
                .build();
        assertThat(converter.convertTraces(traceRequest)).isEmpty();

        ExportMetricsServiceRequest metricRequest = ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(io.opentelemetry.proto.metrics.v1.ResourceMetrics.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(kv("service.name", "  "))))
                .build();
        assertThat(converter.convertMetrics(metricRequest)).isEmpty();
    }

    @Test
    void mapsStableHttpSemconvForServerInboundSpan() throws Exception {
        ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(kv("service.name", "service-c")))
                        .addScopeSpans(ScopeSpans.newBuilder()
                                .addSpans(Span.newBuilder()
                                        .setTraceId(ByteString.fromHex("0102030405060708090a0b0c0d0e0f10"))
                                        .setSpanId(ByteString.fromHex("0102030405060708"))
                                        .setParentSpanId(ByteString.fromHex("0102030405060709"))
                                        .setName("GET /callDB")
                                        .setKind(Span.SpanKind.SPAN_KIND_SERVER)
                                        .setStartTimeUnixNano(1_700_000_000_000_000_000L)
                                        .setEndTimeUnixNano(1_700_000_050_000_000L)
                                        .addAttributes(kv("http.request.method", "GET"))
                                        .addAttributes(kv("http.route", "/callDB"))
                                        .addAttributes(kv("http.response.status_code", "200")))))
                .build();

        DcSpan span = converter.convertTraces(request).get(0).span();
        assertThat(span.metaHttpMethod).isEqualTo("GET");
        assertThat(span.metaHttpUrl).isEqualTo("/callDB");
        assertThat(span.metaHttpStatusCode).isEqualTo(200);
        assertThat(DcSpanUtil.isHttpSpan(span)).isTrue();
    }

    @Test
    void normalizesAbsoluteHttpUrlToPathOnly() throws Exception {
        ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(kv("service.name", "frontend")))
                        .addScopeSpans(ScopeSpans.newBuilder()
                                .addSpans(Span.newBuilder()
                                        .setTraceId(ByteString.fromHex("0102030405060708090a0b0c0d0e0f10"))
                                        .setSpanId(ByteString.fromHex("0102030405060708"))
                                        .setParentSpanId(ByteString.fromHex("0102030405060709"))
                                        .setName("GET")
                                        .setKind(Span.SpanKind.SPAN_KIND_CLIENT)
                                        .setStartTimeUnixNano(1_700_000_000_000_000_000L)
                                        .setEndTimeUnixNano(1_700_000_050_000_000L)
                                        .addAttributes(kv("http.method", "GET"))
                                        .addAttributes(kv("url.full",
                                                "http://frontend-proxy:8080/api/recommendations?productIds=HQTGWGPNH4"))
                                        .addAttributes(kv("http.response.status_code", "200")))))
                .build();

        DcSpan span = converter.convertTraces(request).get(0).span();
        assertThat(span.metaHttpUrl).isEqualTo("/api/recommendations?productIds=HQTGWGPNH4");
    }

    @Test
    void stableHttpSemconvProducesInboundServiceHttpMetric() throws Exception {
        ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(kv("service.name", "service-c")))
                        .addScopeSpans(ScopeSpans.newBuilder()
                                .addSpans(Span.newBuilder()
                                        .setTraceId(ByteString.fromHex("0102030405060708090a0b0c0d0e0f10"))
                                        .setSpanId(ByteString.fromHex("0102030405060708"))
                                        .setParentSpanId(ByteString.fromHex("0102030405060709"))
                                        .setName("GET /callDB")
                                        .setKind(Span.SpanKind.SPAN_KIND_SERVER)
                                        .setStartTimeUnixNano(1_700_000_000_000_000_000L)
                                        .setEndTimeUnixNano(1_700_000_050_000_000L)
                                        .addAttributes(kv("http.request.method", "GET"))
                                        .addAttributes(kv("http.route", "/callDB"))
                                        .addAttributes(kv("http.response.status_code", "200")))))
                .build();

        DcSpan span = converter.convertTraces(request).get(0).span();
        OptimizedMetric httpMetric = DcSpanUtil.parseSpanData(span).stream()
                .filter(metric -> "service.http".equals(metric.measurement()))
                .findFirst()
                .orElseThrow();
        assertThat(tagValue(httpMetric, "isIn")).isEqualTo("1");
        assertThat(tagValue(httpMetric, "isOut")).isEqualTo("0");
        assertThat(tagValue(httpMetric, "url")).isEqualTo("/callDB");
    }

    @Test
    void readsIntAndBoolAttributes() {
        ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(intKv("service.name", 99))
                                .addAttributes(boolKv("host.name", true)))
                        .addScopeSpans(ScopeSpans.newBuilder()
                                .addSpans(Span.newBuilder()
                                        .setName("x")
                                        .setStartTimeUnixNano(1_700_000_000_000_000_000L)
                                        .setEndTimeUnixNano(1_700_000_010_000_000L)
                                        .addAttributes(intKv("http.status_code", 200)))
                                .addSpans(Span.newBuilder()
                                        .setName("y")
                                        .setStartTimeUnixNano(1_700_000_000_000_000_000L)
                                        .setEndTimeUnixNano(1_700_000_010_000_000L)
                                        .addAttributes(kv("http.status_code", "not-a-number")))))
                .build();

        assertThat(converter.convertTraces(request)).hasSize(2);
    }

    @Test
    void convertsLogExportToOtlLogLine() {
        ExportLogsServiceRequest request = ExportLogsServiceRequest.newBuilder()
                .addResourceLogs(io.opentelemetry.proto.logs.v1.ResourceLogs.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(kv("service.name", "checkout"))
                                .addAttributes(kv("host.name", "host-1"))
                                .addAttributes(kv("service.instance.id", "inst-1")))
                        .addScopeLogs(io.opentelemetry.proto.logs.v1.ScopeLogs.newBuilder()
                                .addLogRecords(io.opentelemetry.proto.logs.v1.LogRecord.newBuilder()
                                        .setTimeUnixNano(1_700_000_000_000_000_000L)
                                        .setSeverityNumber(io.opentelemetry.proto.logs.v1.SeverityNumber.SEVERITY_NUMBER_INFO)
                                        .setSeverityText("INFO")
                                        .setTraceId(ByteString.fromHex("0102030405060708090a0b0c0d0e0f10"))
                                        .setSpanId(ByteString.fromHex("0102030405060708"))
                                        .setBody(AnyValue.newBuilder().setStringValue("order created")))))
                .build();

        List<OtelConverter.ConvertedLog> converted = converter.convertLogs(request);
        assertThat(converted).hasSize(1);
        OtlLogLine line = converted.get(0).line();
        assertThat(line.service()).isEqualTo("checkout");
        assertThat(line.serviceInstance()).isEqualTo("inst-1");
        assertThat(line.hostname()).isEqualTo("host-1");
        assertThat(line.body()).isEqualTo("order created");
        assertThat(line.traceId()).isEqualTo("0102030405060708090a0b0c0d0e0f10");
        assertThat(line.spanId()).isEqualTo("0102030405060708");
        assertThat(line.severity()).isEqualTo("INFO");
    }

    @Test
    void skipsLogsWithoutServiceName() {
        ExportLogsServiceRequest request = ExportLogsServiceRequest.newBuilder()
                .addResourceLogs(io.opentelemetry.proto.logs.v1.ResourceLogs.newBuilder()
                        .setResource(Resource.newBuilder())
                        .addScopeLogs(io.opentelemetry.proto.logs.v1.ScopeLogs.newBuilder()
                                .addLogRecords(io.opentelemetry.proto.logs.v1.LogRecord.newBuilder()
                                        .setTimeUnixNano(1_700_000_000_000_000_000L)
                                        .setBody(AnyValue.newBuilder().setStringValue("orphan")))))
                .build();

        assertThat(converter.convertLogs(request)).isEmpty();
    }

    private static KeyValue kv(String key, String value) {
        return KeyValue.newBuilder()
                .setKey(key)
                .setValue(AnyValue.newBuilder().setStringValue(value))
                .build();
    }

    private static KeyValue intKv(String key, long value) {
        return KeyValue.newBuilder()
                .setKey(key)
                .setValue(AnyValue.newBuilder().setIntValue(value))
                .build();
    }

    private static KeyValue boolKv(String key, boolean value) {
        return KeyValue.newBuilder()
                .setKey(key)
                .setValue(AnyValue.newBuilder().setBoolValue(value))
                .build();
    }

    private static String tagValue(OptimizedMetric metric, String column) {
        String[] tags = metric.tagValues();
        var schema = MetricSchemaRegistry.schema(metric.measurement()).orElseThrow();
        int index = schema.tagColumns().indexOf(column);
        return index >= 0 && index < tags.length ? tags[index] : "";
    }
}
