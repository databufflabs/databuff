package com.databuff.apm.ingest.otel;

import com.databuff.apm.ingest.gateway.PipelineGateway;
import com.databuff.apm.ingest.metric.MetricWriteRouter;
import com.databuff.apm.ingest.log.OtlpLogDirectWriter;
import com.databuff.apm.ingest.metric.OtlpMetricDirectWriter;
import com.databuff.apm.ingest.support.IngestTestComponents;
import com.databuff.apm.ingest.component.AggregateComponent;
import com.databuff.apm.ingest.component.MetricComponent;
import com.databuff.apm.ingest.component.TraceComponent;
import com.databuff.apm.common.cluster.aggregate.ClusterAggregator;
import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.serde.DCSpanJsonDecoder;
import com.databuff.apm.common.storage.DorisBatchWriter;
import com.databuff.apm.common.storage.DorisTableNames;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class OtlpIngestServiceTest {

    private AggregateComponent aggregateComponent;
    private MetricComponent metricComponent;
    private TraceComponent traceComponent;

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
    void ingestsTracesThroughGateway() {
        ClusterAggregator aggregator = new ClusterAggregator("n1");
        DorisBatchWriter metricWriter = new DorisBatchWriter(10_000);
        DorisBatchWriter traceWriter = new DorisBatchWriter(10_000);
        aggregateComponent = IngestTestComponents.aggregate(aggregator, metricWriter);
        metricComponent = new MetricComponent(aggregateComponent);
        traceComponent = IngestTestComponents.trace(aggregateComponent, traceWriter, 200L);
        aggregateComponent.start(1);
        metricComponent.start(1);
        traceComponent.start(1);

        OtlpIngestService service = new OtlpIngestService(new OtelConverter(),
                new PipelineGateway(traceComponent, metricComponent),
                noopMetricWriter(),
                noopLogWriter());

        ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(KeyValue.newBuilder()
                                        .setKey("service.name")
                                        .setValue(AnyValue.newBuilder().setStringValue("demo"))))
                        .addScopeSpans(ScopeSpans.newBuilder()
                                .addSpans(Span.newBuilder()
                                        .setTraceId(ByteString.fromHex("0102030405060708090a0b0c0d0e0f10"))
                                        .setSpanId(ByteString.fromHex("0102030405060708"))
                                        .setName("demo-span")
                                        .setStartTimeUnixNano(1_700_000_000_000_000_000L)
                                        .setEndTimeUnixNano(1_700_000_050_000_000L))))
                .build();

        assertThat(service.ingestTraces(request)).isEqualTo(1);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(traceComponent.receivedCount()).isEqualTo(1));
        assertThat(service.tracesIngested()).isEqualTo(1);
    }

    @Test
    void ingestsMultiServiceTraceAsOneBatch() {
        ClusterAggregator aggregator = new ClusterAggregator("n1");
        DorisBatchWriter metricWriter = new DorisBatchWriter(10_000);
        DorisBatchWriter traceWriter = new DorisBatchWriter(10_000);
        aggregateComponent = IngestTestComponents.aggregate(aggregator, metricWriter);
        metricComponent = new MetricComponent(aggregateComponent);
        traceComponent = IngestTestComponents.trace(aggregateComponent, traceWriter, 200L);
        aggregateComponent.start(1);
        metricComponent.start(1);
        traceComponent.start(1);

        OtlpIngestService service = new OtlpIngestService(new OtelConverter(),
                new PipelineGateway(traceComponent, metricComponent),
                noopMetricWriter(),
                noopLogWriter());

        ByteString traceId = ByteString.fromHex("0102030405060708090a0b0c0d0e0f10");
        ByteString rootSpanId = ByteString.fromHex("0102030405060708");
        ByteString childSpanId = ByteString.fromHex("1112131415161718");
        long start = 1_700_000_000_000_000_000L;
        ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(kv("service.name", "service-a")))
                        .addScopeSpans(ScopeSpans.newBuilder()
                                .addSpans(Span.newBuilder()
                                        .setTraceId(traceId)
                                        .setSpanId(rootSpanId)
                                        .setName("GET /demo/checkout")
                                        .setStartTimeUnixNano(start)
                                        .setEndTimeUnixNano(start + 100))))
                .addResourceSpans(ResourceSpans.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(kv("service.name", "service-b")))
                        .addScopeSpans(ScopeSpans.newBuilder()
                                .addSpans(Span.newBuilder()
                                        .setTraceId(traceId)
                                        .setSpanId(childSpanId)
                                        .setParentSpanId(rootSpanId)
                                        .setName("GET /api/orders/{orderId}")
                                        .setStartTimeUnixNano(start + 10)
                                        .setEndTimeUnixNano(start + 80))))
                .build();

        assertThat(service.ingestTraces(request)).isEqualTo(2);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(traceWriter.pendingCount()).isEqualTo(2));
        DcSpan child = traceWriter.flushAll().stream()
                .map(bytes -> {
                    try {
                        return DCSpanJsonDecoder.decode(bytes, true);
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                })
                .filter(span -> "service-b".equals(span.service))
                .findFirst()
                .orElseThrow();
        assertThat(child.srcService).isEqualTo("service-a");
        assertThat(child.dstService).isEqualTo("service-b");
    }

    @Test
    void ingestsMetricsDirectlyToDoris() {
        DorisBatchWriter jvm = new DorisBatchWriter(10_000);
        MetricWriteRouter router = new MetricWriteRouter(Map.of(DorisTableNames.METRIC_JVM, jvm));
        OtlpMetricDirectWriter metricWriter = new OtlpMetricDirectWriter(router);

        ClusterAggregator aggregator = new ClusterAggregator("n1");
        DorisBatchWriter metricWriterBuffer = new DorisBatchWriter(10_000);
        DorisBatchWriter traceWriter = new DorisBatchWriter(10_000);
        aggregateComponent = IngestTestComponents.aggregate(aggregator, metricWriterBuffer);
        metricComponent = new MetricComponent(aggregateComponent);
        traceComponent = IngestTestComponents.trace(aggregateComponent, traceWriter);
        aggregateComponent.start(1);
        metricComponent.start(1);
        traceComponent.start(1);

        OtlpIngestService service = new OtlpIngestService(new OtelConverter(),
                new PipelineGateway(traceComponent, metricComponent), metricWriter, noopLogWriter());

        ExportMetricsServiceRequest request = ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(io.opentelemetry.proto.metrics.v1.ResourceMetrics.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(KeyValue.newBuilder()
                                        .setKey("service.name")
                                        .setValue(AnyValue.newBuilder().setStringValue("demo"))))
                        .addScopeMetrics(io.opentelemetry.proto.metrics.v1.ScopeMetrics.newBuilder()
                                .addMetrics(io.opentelemetry.proto.metrics.v1.Metric.newBuilder()
                                        .setName("jvm.threads.count")
                                        .setGauge(io.opentelemetry.proto.metrics.v1.Gauge.newBuilder()
                                                .addDataPoints(io.opentelemetry.proto.metrics.v1.NumberDataPoint.newBuilder()
                                                        .setAsInt(7))))))
                .build();

        assertThat(service.ingestMetrics(request)).isEqualTo(1);
        assertThat(service.metricsIngested()).isEqualTo(1);
        assertThat(metricComponent.receivedCount()).isZero();
        assertThat(jvm.pendingCount()).isEqualTo(1);
    }

    private static OtlpMetricDirectWriter noopMetricWriter() {
        return new OtlpMetricDirectWriter(MetricWriteRouter.singleTable(new DorisBatchWriter(1)));
    }

    private static OtlpLogDirectWriter noopLogWriter() {
        return new OtlpLogDirectWriter(new DorisBatchWriter(1), null);
    }

    private static KeyValue kv(String key, String value) {
        return KeyValue.newBuilder()
                .setKey(key)
                .setValue(AnyValue.newBuilder().setStringValue(value))
                .build();
    }
}
