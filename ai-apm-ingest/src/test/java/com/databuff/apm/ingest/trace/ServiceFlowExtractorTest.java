package com.databuff.apm.ingest.trace;

import com.databuff.apm.common.cluster.cache.CacheRegionPolicy;
import com.databuff.apm.common.cluster.cache.ClusterCacheRegistry;
import com.databuff.apm.common.storage.DorisBatchWriter;
import com.databuff.apm.common.storage.DorisTableNames;
import com.databuff.apm.common.meta.OtelAttributeMaps;
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
    void extractSkipsSingleSpanOutboundOnlyTrace() throws Exception {
        DcSpan clientRoot = span("trace-single-out", "client-root", "", "service-g", "service-g-id");
        clientRoot.type = "SPAN_KIND_CLIENT";
        clientRoot.resource = "HttpAsyncClient/local";
        clientRoot.name = "HttpAsyncClient/local";
        clientRoot.isIn = 0;
        clientRoot.isOut = 1;

        List<DcSpan> filled = FillPathAndRelationUtil.fillBytes(List.of(
                DCSpanJsonEncoder.encode(clientRoot)));
        assertThat(ServiceFlowExtractor.hasTraceEntryRoot(filled)).isFalse();
        assertThat(ServiceFlowExtractor.extractFromTrace(filled)).isEmpty();
    }

    @Test
    void extractSkipsTraceWithoutIsParentRoot() throws Exception {
        DcSpan downstream = span("trace-7", "downstream", "external-parent", "service-k", "service-k-id");
        downstream.type = "SPAN_KIND_SERVER";
        downstream.is_parent = 0;

        List<DcSpan> filled = FillPathAndRelationUtil.fillBytes(List.of(
                DCSpanJsonEncoder.encode(downstream)));
        assertThat(ServiceFlowExtractor.hasTraceEntryRoot(filled)).isFalse();
        assertThat(ServiceFlowExtractor.extractFromTrace(filled)).isEmpty();
    }

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
        assertThat(tagValue(rootMetric, "service_id")).isEqualTo("gateway-id");
    }

    @Test
    void extractStartsFromIsParentRootEvenWhenOutboundClient() throws Exception {
        DcSpan clientRoot = span("trace-5", "client-root", "", "service-b", "service-b-id");
        clientRoot.type = "SPAN_KIND_CLIENT";
        clientRoot.resource = "/callB";
        clientRoot.name = "/callB";
        clientRoot.isIn = 0;
        clientRoot.isOut = 1;

        DcSpan serverEntry = span("trace-5", "server-entry", "client-root", "service-b", "service-b-id");
        serverEntry.type = "SPAN_KIND_SERVER";
        serverEntry.resource = "GET:/callB";
        serverEntry.name = "GET:/callB";
        serverEntry.isIn = 1;
        serverEntry.isOut = 0;

        DcSpan downstream = span("trace-5", "downstream", "server-entry", "service-c", "service-c-id");
        downstream.type = "SPAN_KIND_SERVER";
        downstream.resource = "GET:/api";
        downstream.name = "GET:/api";

        List<DcSpan> filled = FillPathAndRelationUtil.fillBytes(List.of(
                DCSpanJsonEncoder.encode(clientRoot),
                DCSpanJsonEncoder.encode(serverEntry),
                DCSpanJsonEncoder.encode(downstream)));
        List<OptimizedMetric> metrics = ServiceFlowExtractor.extractFromTrace(filled);

        assertThat(metrics.stream().map(metric -> tagValue(metric, "service")).distinct())
                .contains("service-b", "service-c");

        OptimizedMetric entry = metrics.stream()
                .filter(metric -> "service-b".equals(tagValue(metric, "service")))
                .filter(metric -> tagValue(metric, "parentPathId").isBlank())
                .findFirst()
                .orElseThrow();
        assertThat(tagValue(entry, "resource")).isEqualTo("/callB");
        assertThat(tagValue(entry, "isIn")).isEqualTo("0");
    }

    @Test
    void extractUsesIsParentRootForOutboundTraceWithoutInboundSibling() throws Exception {
        DcSpan clientRoot = span("trace-6", "client-root", "", "service-j", "service-j-id");
        clientRoot.type = "SPAN_KIND_CLIENT";
        clientRoot.resource = "/methodB0";
        clientRoot.name = "/methodB0";
        clientRoot.isIn = 0;
        clientRoot.isOut = 1;

        DcSpan downstream = span("trace-6", "downstream", "client-root", "service-k", "service-k-id");
        downstream.type = "SPAN_KIND_SERVER";
        downstream.resource = "GET:/methodB0";
        downstream.name = "GET:/methodB0";
        downstream.isIn = 1;
        downstream.isOut = 0;

        List<DcSpan> filled = FillPathAndRelationUtil.fillBytes(List.of(
                DCSpanJsonEncoder.encode(clientRoot),
                DCSpanJsonEncoder.encode(downstream)));
        List<OptimizedMetric> metrics = ServiceFlowExtractor.extractFromTrace(filled);

        OptimizedMetric entry = metrics.stream()
                .filter(metric -> "service-j".equals(tagValue(metric, "service")))
                .filter(metric -> tagValue(metric, "parentPathId").isBlank())
                .findFirst()
                .orElseThrow();
        assertThat(tagValue(entry, "isIn")).isEqualTo("0");
        assertThat(tagValue(entry, "service_id")).isEqualTo("service-j-id");

        OptimizedMetric child = metrics.stream()
                .filter(metric -> "service-k".equals(tagValue(metric, "service")))
                .findFirst()
                .orElseThrow();
        assertThat(tagValue(child, "parentService")).isEqualTo("service-j");
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
                                new DorisBatchWriter())),
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
                                        new DorisBatchWriter())),
                        60_000L),
                        null));
        OtelAttributeMaps.put(remoteHttp, "data.source", "Databuff");
        processor.processAfterFill(spans);
        new VirtualServiceExtractor(new VirtualServiceInstanceRegistry(
                new MetricWriteRouter(
                        java.util.Map.of(DorisTableNames.METRIC_SERVICE_INSTANCE,
                                new DorisBatchWriter())),
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
