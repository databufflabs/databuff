package com.databuff.apm.ingest.trace.remote;

import com.databuff.apm.common.cluster.cache.CacheRegionPolicy;
import com.databuff.apm.common.cluster.cache.ClusterCacheRegistry;
import com.databuff.apm.common.meta.OtelAttributeMaps;
import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.model.OptimizedMetric;
import com.databuff.apm.common.serde.DcSpanUtil;
import com.databuff.apm.common.storage.DorisBatchWriter;
import com.databuff.apm.common.storage.DorisTableNames;
import com.databuff.apm.ingest.meta.VirtualServiceInstanceRegistry;
import com.databuff.apm.ingest.metric.MetricWriteRouter;
import com.databuff.apm.ingest.trace.FillPathAndRelationUtil;
import com.databuff.apm.ingest.trace.VirtualServiceExtractor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteCallProcessorTest {

    @Test
    void databuffExternalHttpCreatesRemoteVirtualService() {
        RemoteCallProcessor processor = processor(new RemoteServiceSettings(true, 0L, List.of()));
        DcSpan span = clientHttp("service-a", "api.example.com", "443");
        span.meta = "{\"http.method\":\"GET\",\"data.source\":\"Databuff\","
                + "\"server.address\":\"api.example.com\",\"server.port\":\"443\"}";

        FillPathAndRelationUtil.fillRelations(List.of(span));
        processor.processAfterFill(List.of(span));

        assertThat(span.service).isEqualTo("[remote]api.example.com:443");
        assertThat(OtelAttributeMaps.meta(span)).containsEntry("remote", "true");
        assertThat(DcSpanUtil.parseSpanData(span).stream().map(OptimizedMetric::measurement))
                .contains("service.remote");
    }

    @Test
    void serverServiceMetaPreventsRemoteGeneration() {
        RemoteCallProcessor processor = processor(new RemoteServiceSettings(true, 0L, List.of()));
        DcSpan client = clientHttp("service-a", "service-b", "8080");
        client.meta = "{\"http.method\":\"GET\",\"data.source\":\"Databuff\","
                + "\"server.service\":\"service-b\",\"server.address\":\"service-b\",\"server.port\":\"8080\"}";
        DcSpan server = serverHttp("http-server", "http-client", "service-b");
        List<DcSpan> spans = List.of(client, server);

        FillPathAndRelationUtil.fillRelations(spans);
        processor.processAfterFill(spans);

        assertThat(client.dstService).isEqualTo("service-b");
        assertThat(client.service).isEqualTo("service-a");
        assertThat(client.meta).doesNotContain("\"remote\":\"true\"");
    }

    @Test
    void otelPeerEntersProtectPeriodBeforeRemoteGeneration() {
        RemoteAssociationStore store = associationStore();
        RemoteCallProcessor processor = new RemoteCallProcessor(
                new RemoteServiceSettings(true, 3_600_000L, List.of()),
                new PeerServerServiceCache(),
                store,
                null,
                virtualServiceExtractor());

        DcSpan span = clientHttp("service-a", "api.example.com", "443");
        span.meta = "{\"http.method\":\"GET\",\"data.source\":\"Otel\","
                + "\"server.address\":\"api.example.com\",\"server.port\":\"443\"}";

        FillPathAndRelationUtil.fillRelations(List.of(span));
        processor.processAfterFill(List.of(span));

        assertThat(span.service).isEqualTo("service-a");
        assertThat(OtelAttributeMaps.meta(span)).doesNotContainKey("remote");
    }

    @Test
    void otelPeerCreatesRemoteAfterProtectAndConfirmedExternalAssociation() {
        RemoteAssociationStore store = associationStore();
        RemoteCallProcessor processor = new RemoteCallProcessor(
                new RemoteServiceSettings(true, 0L, List.of()),
                new PeerServerServiceCache(),
                store,
                null,
                virtualServiceExtractor());

        DcSpan span = clientHttp("service-a", "api.example.com", "443");
        span.meta = "{\"http.method\":\"GET\",\"data.source\":\"Otel\","
                + "\"server.address\":\"api.example.com\",\"server.port\":\"443\"}";
        String peerKey = "Otel:Http-api.example.com:443";
        store.recordAssociation(RemoteAssociationStore.defaultApiKey(), peerKey, "");

        FillPathAndRelationUtil.fillRelations(List.of(span));
        processor.processAfterFill(List.of(span));
        processor.processAfterFill(List.of(span));

        assertThat(span.service).isEqualTo("[remote]api.example.com:443");
        assertThat(OtelAttributeMaps.meta(span)).containsEntry("remote", "true");
    }

    @Test
    void cacheFillServerServiceBackfillsErrorSpan() {
        PeerServerServiceCache cache = new PeerServerServiceCache();
        RemoteCallProcessor processor = new RemoteCallProcessor(
                RemoteServiceSettings.defaults(),
                cache,
                associationStore(),
                null,
                null);

        DcSpan success = clientHttp("service-a", "service-b", "8080");
        success.error = 0;
        success.meta = "{\"http.method\":\"GET\",\"data.source\":\"Databuff\","
                + "\"server.service\":\"service-b\",\"server.address\":\"service-b\",\"server.port\":\"8080\"}";
        processor.enrichSpan(success);

        DcSpan error = clientHttp("service-a", "service-b", "8080");
        error.error = 1;
        error.meta = "{\"http.method\":\"GET\",\"data.source\":\"Databuff\","
                + "\"server.address\":\"service-b\",\"server.port\":\"8080\"}";
        processor.enrichSpan(error);

        assertThat(OtelAttributeMaps.meta(error)).containsEntry("server.service", "service-b");
    }

    @Test
    void elasticsearchWithMaterializedHttpFieldsDoesNotCreateRemoteVirtualService() {
        RemoteCallProcessor processor = processor(new RemoteServiceSettings(true, 0L, List.of()));
        VirtualServiceExtractor extractor = virtualServiceExtractor();

        DcSpan esClient = clientHttp("service-a", "es", "9200");
        esClient.name = "elasticsearch.rest.query";
        esClient.metaHttpUrl = "http://es:9200/orders/_search";
        esClient.meta = "{\"db.system\":\"elasticsearch\",\"db.elasticsearch.index\":\"orders\","
                + "\"http.method\":\"GET\",\"http.url\":\"http://es:9200/orders/_search\","
                + "\"data.source\":\"Databuff\",\"server.address\":\"es\",\"server.port\":\"9200\"}";

        FillPathAndRelationUtil.fillRelations(List.of(esClient));
        processor.processAfterFill(List.of(esClient));
        extractor.extractFromSpan(esClient);

        assertThat(esClient.service).isEqualTo("[elasticsearch]es:9200");
        assertThat(esClient.meta).doesNotContain("\"remote\":\"true\"");
        assertThat(DcSpanUtil.parseSpanData(esClient).stream().map(OptimizedMetric::measurement))
                .contains("service.db")
                .doesNotContain("service.remote");
    }

    private static RemoteCallProcessor processor(RemoteServiceSettings settings) {
        return new RemoteCallProcessor(
                settings,
                new PeerServerServiceCache(),
                associationStore(),
                null,
                virtualServiceExtractor());
    }

    private static VirtualServiceExtractor virtualServiceExtractor() {
        return new VirtualServiceExtractor(
                new VirtualServiceInstanceRegistry(
                        new MetricWriteRouter(Map.of(DorisTableNames.METRIC_SERVICE_INSTANCE, new DorisBatchWriter())),
                        60_000L),
                null);
    }

    private static RemoteAssociationStore associationStore() {
        ClusterCacheRegistry registry = new ClusterCacheRegistry();
        registry.region("ingest.remote", CacheRegionPolicy.REPLICATED, Duration.ofHours(1));
        return new RemoteAssociationStore(registry.get("ingest.remote"));
    }

    private static DcSpan clientHttp(String service, String host, String port) {
        DcSpan span = new DcSpan();
        span.trace_id = "trace-remote";
        span.span_id = service + "-client";
        span.parent_id = "root";
        span.service = service;
        span.serviceId = service + "-id";
        span.type = "SPAN_KIND_CLIENT";
        span.isOut = 1;
        span.metaHttpMethod = "GET";
        span.metaPeerHostname = host;
        return span;
    }

    private static DcSpan serverHttp(String spanId, String parentId, String service) {
        DcSpan span = new DcSpan();
        span.trace_id = "trace-remote";
        span.span_id = spanId;
        span.parent_id = parentId;
        span.service = service;
        span.serviceId = service + "-id";
        span.type = "SPAN_KIND_SERVER";
        span.metaHttpMethod = "GET";
        return span;
    }
}
