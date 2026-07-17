package com.databuff.apm.ingest.component;

import com.databuff.apm.common.cluster.aggregate.ClusterAggregator;
import com.databuff.apm.ingest.gateway.PipelineGateway;
import com.databuff.apm.ingest.metric.MetricTableWriterRegistry;
import com.databuff.apm.ingest.metric.MetricWriteRouter;
import com.databuff.apm.ingest.log.OtlpLogDirectWriter;
import com.databuff.apm.ingest.metric.OtlpMetricDirectWriter;
import com.databuff.apm.ingest.otel.OtelConverter;
import com.databuff.apm.ingest.otel.OtlpIngestService;
import com.databuff.apm.ingest.trace.TraceFillProcessor;
import com.databuff.apm.ingest.support.IngestTestComponents;
import com.databuff.apm.ingest.support.TestClusterMembership;
import com.databuff.apm.common.metric.TraceMetricMinuteBucket;
import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.model.OptimizedMetric;
import com.databuff.apm.common.storage.DorisBatchWriter;
import com.databuff.apm.common.storage.DorisStreamLoader;
import com.databuff.apm.common.storage.DorisTableNames;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExtractedMetricRoutingTest {

    private AggregateComponent aggregateComponent;
    private TraceComponent traceComponent;
    private MetricComponent metricComponent;

    @AfterEach
    void tearDown() {
        if (traceComponent != null) {
            traceComponent.close();
        }
        if (metricComponent != null) {
            metricComponent.close();
        }
        if (aggregateComponent != null) {
            aggregateComponent.close();
        }
    }

    @Test
    void httpSpanMetricsRouteToMetricServiceHttpWriter() throws Exception {
        DorisStreamLoader loader = mock(DorisStreamLoader.class);
        when(loader.loadJsonLines(any(), any(), any()))
                .thenReturn(new DorisStreamLoader.StreamLoadResult(true, 200, "Success"));

        MetricTableWriterRegistry registry = MetricTableWriterRegistry.create(loader, "databuff");
        aggregateComponent = new AggregateComponent(
                new ClusterAggregator("n1"),
                TestClusterMembership.standalone("n1"),
                new MetricWriteRouter(registry.writersByTable()));
        aggregateComponent.start(1);

        DcSpan span = httpSpan();
        List<OptimizedMetric> metrics = new TraceFillProcessor().processTrace(List.of(span)).metrics();
        assertThat(metrics.stream().map(OptimizedMetric::measurement))
                .contains("service", "service.trace")
                .doesNotContain("service.instance");

        aggregateComponent.acceptExtractedMetrics("demo-key", metrics);

        long windowMs = TraceMetricMinuteBucket.minuteBucketEpochMsFromEndNanos(span.end);
        aggregateComponent.flushTraceMinuteWindowForTest(windowMs);

        assertThat(registry.writer(DorisTableNames.METRIC_SERVICE_TRACE).pendingCount()).isGreaterThan(0);
        assertThat(registry.writer(DorisTableNames.METRIC_SERVICE).pendingCount()).isGreaterThan(0);

        registry.sinks().stream()
                .filter(s -> DorisTableNames.METRIC_SERVICE_TRACE.equals(s.table()))
                .findFirst()
                .orElseThrow()
                .flushAll();

        verify(loader).loadJsonLines(eq("databuff"), eq(DorisTableNames.METRIC_SERVICE_TRACE), any());
    }

    @Test
    void otlpHttpTraceRoutesExtractedMetricsToWriters() throws Exception {
        DorisStreamLoader loader = mock(DorisStreamLoader.class);
        when(loader.loadJsonLines(any(), any(), any()))
                .thenReturn(new DorisStreamLoader.StreamLoadResult(true, 200, "Success"));

        MetricTableWriterRegistry registry = MetricTableWriterRegistry.create(loader, "databuff");
        aggregateComponent = new AggregateComponent(
                new ClusterAggregator("n1"),
                TestClusterMembership.standalone("n1"),
                new MetricWriteRouter(registry.writersByTable()));
        metricComponent = new MetricComponent(aggregateComponent);
        traceComponent = IngestTestComponents.trace(aggregateComponent, new DorisBatchWriter(128), 200L);
        aggregateComponent.start(1);
        metricComponent.start(1);
        traceComponent.start(1);

        OtlpIngestService service = new OtlpIngestService(
                new OtelConverter(),
                new PipelineGateway(traceComponent, metricComponent),
                new OtlpMetricDirectWriter(new MetricWriteRouter(registry.writersByTable())),
                new OtlpLogDirectWriter(new DorisBatchWriter(128), null));
        long end = System.currentTimeMillis() * 1_000_000L;
        long start = end - 50_000_000L;
        ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(kv("service.name", "demo-order"))
                                .addAttributes(kv("service.instance.id", "it-instance-1")))
                        .addScopeSpans(ScopeSpans.newBuilder()
                                .addSpans(Span.newBuilder()
                                        .setTraceId(ByteString.fromHex("0102030405060708090a0b0c0d0e0f10"))
                                        .setSpanId(ByteString.fromHex("0102030405060708"))
                                        .setName("GET /orders")
                                        .setKind(Span.SpanKind.SPAN_KIND_SERVER)
                                        .addAttributes(kv("http.method", "GET"))
                                        .addAttributes(kv("http.status_code", "200"))
                                        .addAttributes(kv("url.full", "/orders"))
                                        .setStartTimeUnixNano(start)
                                        .setEndTimeUnixNano(end))))
                .build();

        assertThat(service.ingestTraces(request)).isEqualTo(1);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(traceComponent.receivedCount()).isEqualTo(1));
        long windowMs = TraceMetricMinuteBucket.minuteBucketEpochMsFromEndNanos(end);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            aggregateComponent.flushTraceMinuteWindowForTest(windowMs);
            assertThat(registry.writer(DorisTableNames.METRIC_SERVICE).pendingCount()).isGreaterThan(0);
        });
    }

    private static KeyValue kv(String key, String value) {
        return KeyValue.newBuilder()
                .setKey(key)
                .setValue(AnyValue.newBuilder().setStringValue(value))
                .build();
    }

    private static DcSpan httpSpan() {
        DcSpan span = new DcSpan();
        span.trace_id = "trace-http";
        span.span_id = "span-http";
        span.parent_id = "";
        span.type = "SPAN_KIND_SERVER";
        span.service = "demo-order";
        span.serviceId = "demo-order-id";
        span.serviceInstance = "it-instance-1";
        span.resource = "GET /orders";
        span.name = "GET /orders";
        span.hostName = "it-demo";
        span.error = 0;
        span.duration = 50_000_000L;
        span.start = System.currentTimeMillis() * 1_000_000L;
        span.end = span.start + span.duration;
        span.metaHttpMethod = "GET";
        span.metaHttpStatusCode = 200;
        span.metaHttpUrl = "/orders";
        return span;
    }
}
