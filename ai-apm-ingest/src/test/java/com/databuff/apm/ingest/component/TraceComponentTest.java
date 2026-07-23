package com.databuff.apm.ingest.component;

import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.cluster.cache.CacheRegionPolicy;
import com.databuff.apm.common.cluster.cache.ClusterCacheRegistry;
import com.databuff.apm.ingest.event.MetricEvent;
import com.databuff.apm.ingest.event.TraceEvent;
import com.databuff.apm.ingest.meta.IngestMetaCache;
import com.databuff.apm.ingest.meta.MetaServiceRegistry;
import com.databuff.apm.ingest.otel.OtlMetricLine;
import com.databuff.apm.ingest.support.IngestTestComponents;
import com.databuff.apm.ingest.trace.SpanResourceIgnoreFilter;
import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.common.storage.DorisBatchWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceComponentTest {

    private AggregateComponent aggregateComponent;
    private TraceComponent traceComponent;

    @AfterEach
    void tearDown() {
        if (traceComponent != null) {
            traceComponent.close();
        }
        if (aggregateComponent != null) {
            aggregateComponent.close();
        }
    }

    @Test
    void ignoresNonTraceEvents() {
        aggregateComponent = IngestTestComponents.aggregate(new DorisBatchWriter(10));
        traceComponent = IngestTestComponents.trace(aggregateComponent, new DorisBatchWriter(10));
        aggregateComponent.start(1);
        traceComponent.start(1);

        assertThat(traceComponent.emit("k", MetricEvent.fromOtlp(
                new OtlMetricLine(1_700_000_000_000L, "id", "svc", "m", 1,
                        null, null, null, null, null, null, null, null)))).isTrue();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(traceComponent.receivedCount()).isZero());
    }

    @Test
    void countsTraceEvents() throws Exception {
        aggregateComponent = IngestTestComponents.aggregate(new DorisBatchWriter(10));
        traceComponent = IngestTestComponents.trace(aggregateComponent, new DorisBatchWriter(10));
        aggregateComponent.start(1);
        traceComponent.start(1);

        DcSpan span = new DcSpan();
        span.trace_id = "trace-x";
        span.span_id = "span-x";
        span.parent_id = "";
        span.service = "demo";
        span.serviceId = "demo-id";
        span.resource = "ping";
        span.name = "ping";
        span.hostName = "host";
        span.error = 0;
        span.duration = 1;
        span.start = 1_700_000_000_000_000_000L;
        span.end = span.start + 1;

        assertThat(traceComponent.emit("k", new TraceEvent(span))).isTrue();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(traceComponent.receivedCount()).isEqualTo(1));
    }

    @Test
    void flushesPendingSpansOnClose() throws Exception {
        DorisBatchWriter traceWriter = new DorisBatchWriter(10);
        aggregateComponent = IngestTestComponents.aggregate(new DorisBatchWriter(10));
        traceComponent = IngestTestComponents.trace(aggregateComponent, traceWriter, 200L);
        aggregateComponent.start(1);
        traceComponent.start(1);

        DcSpan span = new DcSpan();
        span.trace_id = "trace-close";
        span.span_id = "span-close";
        span.parent_id = "";
        span.service = "demo";
        span.serviceId = "demo-id";
        span.resource = "ping";
        span.name = "ping";
        span.hostName = "host";
        span.error = 0;
        span.duration = 1;
        span.start = 1_700_000_000_000_000_000L;
        span.end = span.start + 1;

        traceComponent.emit("k", new TraceEvent(span));
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(traceComponent.receivedCount()).isEqualTo(1));
        traceComponent.close();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(traceWriter.pendingCount()).isEqualTo(1));
    }

    @Test
    void enrichesWithConfiguredMetaCache() throws Exception {
        ClusterCacheRegistry registry = new ClusterCacheRegistry();
        registry.region("ingest.meta", CacheRegionPolicy.REPLICATED, Duration.ofHours(1));
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryMetaServices(anyString())).thenReturn(List.of());
        MetaServiceRegistry serviceRegistry = new MetaServiceRegistry(reader, "databuff", 60_000L);
        serviceRegistry.start();
        IngestMetaCache metaCache = new IngestMetaCache(registry, serviceRegistry);

        aggregateComponent = IngestTestComponents.aggregate(new DorisBatchWriter(10));
        traceComponent = IngestTestComponents.trace(aggregateComponent, new DorisBatchWriter(10), metaCache, 200L);
        aggregateComponent.start(1);
        traceComponent.start(1);

        DcSpan seed = new DcSpan();
        seed.trace_id = "trace-seed";
        seed.span_id = "seed";
        seed.parent_id = "";
        seed.service = "checkout";
        seed.serviceId = "svc-seed";
        seed.serviceInstance = "inst";
        seed.resource = "GET /";
        seed.name = "GET /";
        seed.hostName = "host";
        seed.error = 0;
        seed.duration = 1;
        seed.start = 1_700_000_000_000_000_000L;
        seed.end = seed.start + 1;
        traceComponent.emit("k", new TraceEvent(seed));

        DcSpan child = new DcSpan();
        child.trace_id = "trace-seed";
        child.span_id = "child";
        child.parent_id = "seed";
        child.serviceId = "svc-seed";
        child.resource = "GET /";
        child.name = "GET /";
        child.error = 0;
        child.duration = 1;
        child.start = seed.start + 1;
        child.end = child.start + 1;

        assertThat(traceComponent.emit("k", new TraceEvent(child))).isTrue();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(traceComponent.receivedCount()).isEqualTo(2));
    }

    @Test
    void dropsSpansMatchingResourceIgnoreFilter() throws Exception {
        DorisBatchWriter traceWriter = new DorisBatchWriter(10);
        SpanResourceIgnoreFilter filter = new SpanResourceIgnoreFilter(
                List.of("/actuator/prometheus"),
                List.of("^/health$"));
        aggregateComponent = IngestTestComponents.aggregate(new DorisBatchWriter(10));
        traceComponent = IngestTestComponents.trace(
                aggregateComponent, traceWriter, null, filter, 200L);
        aggregateComponent.start(1);
        traceComponent.start(1);

        DcSpan ignoredExact = sampleSpan("trace-ignore-1", "s1", "/actuator/prometheus");
        DcSpan ignoredRegex = sampleSpan("trace-ignore-2", "s2", "/health");
        DcSpan kept = sampleSpan("trace-keep", "s3", "/api/orders");

        assertThat(traceComponent.emit("k", new TraceEvent(ignoredExact))).isTrue();
        assertThat(traceComponent.emit("k", new TraceEvent(ignoredRegex))).isTrue();
        assertThat(traceComponent.emit("k", new TraceEvent(kept))).isTrue();

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(traceComponent.receivedCount()).isEqualTo(3);
            assertThat(traceComponent.ignoredCount()).isEqualTo(2);
        });

        traceComponent.close();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(traceWriter.pendingCount()).isEqualTo(1));
    }

    private static DcSpan sampleSpan(String traceId, String spanId, String resource) {
        DcSpan span = new DcSpan();
        span.trace_id = traceId;
        span.span_id = spanId;
        span.parent_id = "";
        span.service = "demo";
        span.serviceId = "demo-id";
        span.resource = resource;
        span.name = resource;
        span.hostName = "host";
        span.error = 0;
        span.duration = 1;
        span.start = 1_700_000_000_000_000_000L;
        span.end = span.start + 1;
        return span;
    }
}
