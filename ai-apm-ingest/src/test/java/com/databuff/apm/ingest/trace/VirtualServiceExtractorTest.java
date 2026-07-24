package com.databuff.apm.ingest.trace;

import com.databuff.apm.common.meta.OtelAttributeMaps;
import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.model.OptimizedMetric;
import com.databuff.apm.common.serde.DcSpanUtil;
import com.databuff.apm.ingest.meta.VirtualServiceInstanceRegistry;
import com.databuff.apm.ingest.metric.MetricWriteRouter;
import com.databuff.apm.ingest.trace.remote.PeerServerServiceCache;
import com.databuff.apm.ingest.trace.remote.RemoteAssociationStore;
import com.databuff.apm.ingest.trace.remote.RemoteCallProcessor;
import com.databuff.apm.ingest.trace.remote.RemoteServiceSettings;
import com.databuff.apm.common.cluster.cache.CacheRegionPolicy;
import com.databuff.apm.common.cluster.cache.ClusterCacheRegistry;
import com.databuff.apm.common.storage.DorisBatchWriter;
import com.databuff.apm.common.storage.DorisTableNames;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class VirtualServiceExtractorTest {

    @Test
    void extractMarksRemoteMetaForExternalHttpVirtualService() {
        DorisBatchWriter writer = new DorisBatchWriter();
        VirtualServiceInstanceRegistry registry = new VirtualServiceInstanceRegistry(
                new MetricWriteRouter(Map.of(DorisTableNames.METRIC_SERVICE_INSTANCE, writer)), 60_000L);
        VirtualServiceExtractor extractor = new VirtualServiceExtractor(registry);
        RemoteCallProcessor remoteProcessor = new RemoteCallProcessor(
                new RemoteServiceSettings(true, 0L, List.of()),
                new PeerServerServiceCache(),
                remoteAssociationStore(),
                null,
                extractor);

        DcSpan span = new DcSpan();
        span.service = "service-a";
        span.serviceId = "service-a-id";
        span.type = "SPAN_KIND_CLIENT";
        span.isOut = 1;
        span.metaHttpMethod = "GET";
        span.meta = "{\"http.method\":\"GET\",\"data.source\":\"Databuff\","
                + "\"server.address\":\"api.example.com\",\"server.port\":\"443\"}";

        FillPathAndRelationUtil.fillRelations(List.of(span));
        remoteProcessor.processAfterFill(List.of(span));
        assertThat(span.service).isEqualTo("[remote]api.example.com:443");
        assertThat(span.dstService).isEqualTo("[remote]api.example.com:443");
        assertThat(OtelAttributeMaps.meta(span)).containsEntry("remote", "true");

        List<OptimizedMetric> metrics = DcSpanUtil.parseSpanData(span);
        assertThat(metrics.stream().map(OptimizedMetric::measurement)).contains("service.remote");
    }

    @Test
    void extractThenParseSpanDataEmitsInboundVirtualMetrics() throws Exception {
        DorisBatchWriter writer = new DorisBatchWriter();
        VirtualServiceInstanceRegistry registry = new VirtualServiceInstanceRegistry(
                new MetricWriteRouter(Map.of(DorisTableNames.METRIC_SERVICE_INSTANCE, writer)), 60_000L);
        VirtualServiceExtractor extractor = new VirtualServiceExtractor(registry);

        DcSpan span = new DcSpan();
        span.service = "checkout";
        span.serviceId = "checkout-id";
        span.serviceInstance = "inst-1";
        span.type = "SPAN_KIND_CLIENT";
        span.isOut = 1;
        span.parent_id = "parent";
        span.srcService = "checkout";
        span.srcServiceId = "checkout-id";
        span.error = 0;
        span.duration = 50_000_000L;
        span.start = 1_700_000_000_000_000_000L;
        span.meta = "{\"db.system\":\"mysql\",\"db.name\":\"orders\",\"server.address\":\"10.0.0.8\"}";

        extractor.extractFromSpan(span);
        assertThat(span.isIn).isEqualTo(1);
        assertThat(span.service).isEqualTo("[mysql]orders");
        assertThat(span.dstService).isEqualTo("[mysql]orders");

        List<OptimizedMetric> metrics = DcSpanUtil.parseSpanData(span);
        assertThat(metrics.stream().map(OptimizedMetric::measurement)).contains("service.db", "service");
        assertThat(metrics.stream().anyMatch(m -> "service.db".equals(m.measurement()))).isTrue();
    }

    @Test
    void extractClearsPeerHostnameForMqAndEsVirtualServices() {
        DorisBatchWriter writer = new DorisBatchWriter();
        VirtualServiceInstanceRegistry registry = new VirtualServiceInstanceRegistry(
                new MetricWriteRouter(Map.of(DorisTableNames.METRIC_SERVICE_INSTANCE, writer)), 60_000L);
        VirtualServiceExtractor extractor = new VirtualServiceExtractor(registry);

        DcSpan kafka = new DcSpan();
        kafka.service = "service-a";
        kafka.type = "SPAN_KIND_CLIENT";
        kafka.isOut = 1;
        kafka.metaPeerHostname = "kafka";
        kafka.meta = "{\"messaging.system\":\"kafka\",\"messaging.destination.name\":\"order-events\","
                + "\"net.peer.name\":\"broker-1\",\"messaging.operation\":\"publish\"}";
        extractor.extractFromSpan(kafka);
        assertThat(kafka.service).isEqualTo("[kafka]order-events");
        assertThat(kafka.metaPeerHostname).isNull();

        DcSpan es = new DcSpan();
        es.service = "service-a";
        es.type = "SPAN_KIND_CLIENT";
        es.isOut = 1;
        es.metaPeerHostname = "es";
        es.meta = "{\"db.system\":\"elasticsearch\",\"server.address\":\"es\",\"server.port\":\"9200\","
                + "\"http.method\":\"GET\",\"http.url\":\"http://es:9200/orders/_search\"}";
        extractor.extractFromSpan(es);
        assertThat(es.service).isEqualTo("[elasticsearch]es:9200");
        assertThat(es.metaPeerHostname).isNull();
    }

    @Test
    void extractKeepsPeerHostnameForDbVirtualService() {
        DorisBatchWriter writer = new DorisBatchWriter();
        VirtualServiceInstanceRegistry registry = new VirtualServiceInstanceRegistry(
                new MetricWriteRouter(Map.of(DorisTableNames.METRIC_SERVICE_INSTANCE, writer)), 60_000L);
        VirtualServiceExtractor extractor = new VirtualServiceExtractor(registry);

        DcSpan span = new DcSpan();
        span.service = "checkout";
        span.type = "SPAN_KIND_CLIENT";
        span.isOut = 1;
        span.metaPeerHostname = "mysql";
        span.meta = "{\"db.system\":\"mysql\",\"db.name\":\"orders\",\"server.address\":\"10.0.0.8\"}";

        extractor.extractFromSpan(span);
        assertThat(span.service).isEqualTo("[mysql]orders");
        assertThat(span.metaPeerHostname).isEqualTo("mysql");
    }

    private static RemoteAssociationStore remoteAssociationStore() {
        ClusterCacheRegistry cacheRegistry = new ClusterCacheRegistry();
        cacheRegistry.region("ingest.remote", CacheRegionPolicy.REPLICATED, Duration.ofHours(1));
        return new RemoteAssociationStore(cacheRegistry.get("ingest.remote"));
    }
}
