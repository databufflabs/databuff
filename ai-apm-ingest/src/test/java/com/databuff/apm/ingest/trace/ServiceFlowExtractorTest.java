package com.databuff.apm.ingest.trace;

import com.databuff.apm.common.cluster.cache.CacheRegionPolicy;
import com.databuff.apm.common.cluster.cache.ClusterCacheRegistry;
import com.databuff.apm.common.storage.DorisBatchWriter;
import com.databuff.apm.common.storage.DorisTableNames;
import com.databuff.apm.common.metric.MetricSchemaRegistry;
import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.model.OptimizedMetric;
import com.databuff.apm.common.serde.DCSpanJsonEncoder;
import com.databuff.apm.ingest.meta.VirtualServiceInstanceRegistry;
import com.databuff.apm.ingest.metric.MetricWriteRouter;
import com.databuff.apm.ingest.trace.remote.PeerServerServiceCache;
import com.databuff.apm.ingest.trace.remote.RemoteAssociationStore;
import com.databuff.apm.ingest.trace.remote.RemoteCallProcessor;
import com.databuff.apm.ingest.trace.remote.RemoteServiceSettings;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceFlowExtractorTest {

    @Test
    void extractProducesPathTaggedFlowMetrics() throws Exception {
        DcSpan root = span("trace-1", "root", "", "gateway", "gateway-id");
        DcSpan child = span("trace-1", "child", "root", "checkout", "checkout-id");

        List<DcSpan> filled = FillPathAndRelationUtil.fillBytes(List.of(
                DCSpanJsonEncoder.encode(root),
                DCSpanJsonEncoder.encode(child)));
        List<OptimizedMetric> metrics = ServiceFlowExtractor.extractFromTrace(filled);

        assertThat(metrics).isNotEmpty();
        OptimizedMetric rootMetric = metrics.stream()
                .filter(metric -> tagValue(metric, "service").equals("gateway"))
                .findFirst()
                .orElseThrow();
        assertThat(tagValue(rootMetric, "entryPathId")).isNotBlank();
        assertThat(tagValue(rootMetric, "pathId")).isNotBlank();
        assertThat(tagValue(rootMetric, "parentPathId")).isBlank();
    }

    @Test
    void extractIgnoresDbResourceOnEntryService() throws Exception {
        DcSpan root = span("trace-2", "root", "", "service-a", "service-a-id");
        root.resource = "GET /demo/checkout";
        root.name = "GET /demo/checkout";
        root.type = "SPAN_KIND_SERVER";
        root.metaHttpMethod = "GET";

        DcSpan dbClient = span("trace-2", "db", "root", "service-a", "service-a-id");
        dbClient.type = "SPAN_KIND_CLIENT";
        dbClient.resource = "INSERT INTO demo_order VALUES (?)";
        dbClient.name = "INSERT INTO demo_order VALUES (?)";
        dbClient.meta = "{\"db.system\":\"mysql\",\"db.operation\":\"INSERT\"}";

        List<DcSpan> filled = FillPathAndRelationUtil.fillBytes(List.of(
                DCSpanJsonEncoder.encode(root),
                DCSpanJsonEncoder.encode(dbClient)));
        List<OptimizedMetric> metrics = ServiceFlowExtractor.extractFromTrace(filled);

        assertThat(metrics).hasSize(1);
        OptimizedMetric entry = metrics.get(0);
        assertThat(tagValue(entry, "resource")).isEqualTo("GET /demo/checkout");
        assertThat(metrics.stream().map(metric -> tagValue(metric, "resource")))
                .doesNotContain("INSERT INTO demo_order VALUES (?)");
    }

    @Test
    void extractIncludesVirtualComponentServicesAfterVirtualServiceFill() throws Exception {
        DcSpan root = span("trace-4", "root", "", "service-a", "service-a-id");
        root.resource = "GET /demo/checkout";
        root.name = "GET /demo/checkout";
        root.type = "SPAN_KIND_SERVER";
        root.metaHttpMethod = "GET";

        DcSpan dbClient = span("trace-4", "db", "root", "service-a", "service-a-id");
        dbClient.type = "SPAN_KIND_CLIENT";
        dbClient.meta = "{\"db.system\":\"mysql\",\"db.name\":\"demo_apm\","
                + "\"db.statement\":\"INSERT INTO demo_order_audit(order_id) VALUES (?)\","
                + "\"server.address\":\"mysql\",\"server.port\":\"3306\"}";

        DcSpan redisClient = span("trace-4", "redis", "root", "service-a", "service-a-id");
        redisClient.type = "SPAN_KIND_CLIENT";
        redisClient.meta = "{\"db.system\":\"redis\",\"db.statement\":\"GET cart:10001\","
                + "\"server.address\":\"redis\",\"server.port\":\"6379\"}";

        List<DcSpan> spans = List.of(root, dbClient, redisClient);
        FillPathAndRelationUtil.fillRelations(spans);
        new VirtualServiceExtractor(new VirtualServiceInstanceRegistry(
                new MetricWriteRouter(
                        java.util.Map.of(DorisTableNames.METRIC_SERVICE_INSTANCE,
                                new DorisBatchWriter(16))),
                60_000L))
                .extractFromTrace(spans);

        assertThat(dbClient.service).startsWith("[mysql]");
        assertThat(redisClient.service).startsWith("[redis]");

        List<OptimizedMetric> metrics = ServiceFlowExtractor.extractFromTrace(spans);
        assertThat(metrics.stream().map(metric -> tagValue(metric, "service")).distinct().toList())
                .contains("service-a", "[mysql]demo_apm", "[redis]redis:6379");
    }

    @Test
    void extractIgnoresOutboundRemoteHttpAfterVirtualServiceFill() throws Exception {
        DcSpan root = span("trace-3", "root", "", "service-a", "service-a-id");
        root.resource = "GET /demo/checkout";
        root.name = "GET /demo/checkout";
        root.type = "SPAN_KIND_SERVER";
        root.metaHttpMethod = "GET";
        root.metaHttpStatusCode = 200;

        DcSpan remoteHttp = span("trace-3", "remote-http", "root", "service-a", "service-a-id");
        remoteHttp.type = "SPAN_KIND_CLIENT";
        remoteHttp.name = "HTTP GET payments.example.com /api/risk/check";
        remoteHttp.resource = remoteHttp.name;
        remoteHttp.metaHttpMethod = "GET";
        remoteHttp.metaHttpStatusCode = 200;
        remoteHttp.meta = "{\"http.method\":\"GET\",\"http.status_code\":\"200\","
                + "\"url.full\":\"https://payments.example.com/api/risk/check\","
                + "\"server.address\":\"payments.example.com\",\"server.port\":\"443\"}";

        DcSpan httpClient = span("trace-3", "http-client", "root", "service-a", "service-a-id");
        httpClient.type = "SPAN_KIND_CLIENT";
        httpClient.name = "HTTP GET service-b /api/orders";
        httpClient.resource = httpClient.name;
        httpClient.metaHttpMethod = "GET";
        httpClient.metaHttpStatusCode = 200;
        httpClient.meta = "{\"http.method\":\"GET\",\"http.status_code\":\"200\","
                + "\"url.full\":\"http://service-b:8080/api/orders/10001\","
                + "\"server.address\":\"service-b\",\"server.port\":\"8080\"}";

        DcSpan httpServer = span("trace-3", "http-server", "http-client", "service-b", "service-b-id");
        httpServer.type = "SPAN_KIND_SERVER";
        httpServer.name = "GET /api/orders/{orderId}";
        httpServer.resource = httpServer.name;
        httpServer.metaHttpMethod = "GET";
        httpServer.metaHttpStatusCode = 200;

        List<DcSpan> spans = List.of(root, remoteHttp, httpClient, httpServer);
        FillPathAndRelationUtil.fillRelations(spans);
        RemoteCallProcessor processor = new RemoteCallProcessor(
                new RemoteServiceSettings(true, 0L, List.of()),
                new PeerServerServiceCache(),
                remoteAssociationStore(),
                null,
                new VirtualServiceExtractor(new VirtualServiceInstanceRegistry(
                        new MetricWriteRouter(
                                java.util.Map.of(DorisTableNames.METRIC_SERVICE_INSTANCE,
                                        new DorisBatchWriter(16))),
                        60_000L),
                        null));
        remoteHttp.meta = remoteHttp.meta.replace(
                "\"server.address\":\"payments.example.com\"",
                "\"server.address\":\"payments.example.com\",\"data.source\":\"Databuff\"");
        processor.processAfterFill(spans);
        new VirtualServiceExtractor(new VirtualServiceInstanceRegistry(
                new MetricWriteRouter(
                        java.util.Map.of(DorisTableNames.METRIC_SERVICE_INSTANCE,
                                new DorisBatchWriter(16))),
                60_000L))
                .extractFromTrace(spans);

        assertThat(remoteHttp.isOut).isEqualTo(1);
        assertThat(remoteHttp.isIn).isEqualTo(1);
        assertThat(remoteHttp.service).isEqualTo("[remote]payments.example.com:443");

        List<OptimizedMetric> metrics = ServiceFlowExtractor.extractFromTrace(spans);

        assertThat(metrics.stream().map(metric -> tagValue(metric, "resource")).toList())
                .contains("GET /demo/checkout", "GET /api/orders/{orderId}")
                .doesNotContain("HTTP GET payments.example.com /api/risk/check");
        assertThat(metrics.stream()
                .filter(metric -> "service-a".equals(tagValue(metric, "service")))
                .map(metric -> tagValue(metric, "resource"))
                .toList()).containsExactly("GET /demo/checkout");
        assertThat(metrics.stream().map(metric -> tagValue(metric, "service")).distinct().toList())
                .contains("service-a", "service-b", "[remote]payments.example.com:443");
    }

    private static DcSpan span(String traceId, String spanId, String parentId, String service, String serviceId) {
        DcSpan span = new DcSpan();
        span.trace_id = traceId;
        span.span_id = spanId;
        span.parent_id = parentId;
        span.service = service;
        span.serviceId = serviceId;
        span.serviceInstance = "inst";
        span.resource = "GET /";
        span.name = "GET /";
        span.hostName = "host";
        span.error = 0;
        span.duration = 50;
        span.start = 1_700_000_000_000_000_000L;
        span.end = span.start + span.duration;
        return span;
    }

    private static String tagValue(OptimizedMetric metric, String column) {
        String[] tags = metric.tagValues();
        var schema = MetricSchemaRegistry.schema(metric.measurement()).orElseThrow();
        int index = schema.tagColumns().indexOf(column);
        return index >= 0 && index < tags.length ? tags[index] : "";
    }

    private static RemoteAssociationStore remoteAssociationStore() {
        ClusterCacheRegistry registry = new ClusterCacheRegistry();
        registry.region("ingest.remote", CacheRegionPolicy.REPLICATED, Duration.ofHours(1));
        return new RemoteAssociationStore(registry.get("ingest.remote"));
    }
}
