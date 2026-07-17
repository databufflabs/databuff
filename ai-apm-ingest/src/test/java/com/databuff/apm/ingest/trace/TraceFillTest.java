package com.databuff.apm.ingest.trace;

import com.databuff.apm.common.meta.OtelAttributeMaps;
import com.databuff.apm.common.metric.MetricSchemaRegistry;
import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.model.OptimizedMetric;
import com.databuff.apm.common.serde.DCSpanJsonEncoder;
import com.databuff.apm.common.serde.DcSpanUtil;
import com.databuff.apm.common.storage.DorisBatchWriter;
import com.databuff.apm.common.storage.DorisTableNames;
import com.databuff.apm.ingest.meta.VirtualServiceInstanceRegistry;
import com.databuff.apm.ingest.metric.MetricWriteRouter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceFillTest {

    @Test
    void fillsParentChildRelations() throws Exception {
        DcSpan root = span("trace-1", "root", "", "gateway");
        DcSpan child = span("trace-1", "child", "root", "checkout");

        List<DcSpan> filled = FillPathAndRelationUtil.fillBytes(List.of(
                DCSpanJsonEncoder.encode(root),
                DCSpanJsonEncoder.encode(child)));

        assertThat(filled).hasSize(2);
        DcSpan filledChild = filled.stream().filter(s -> "child".equals(s.span_id)).findFirst().orElseThrow();
        assertThat(filledChild.srcService).isEqualTo("gateway");
        assertThat(filledChild.dstService).isEqualTo("checkout");
        assertThat(filledChild.isIn).isEqualTo(1);
    }

    @Test
    void assemblyBufferBuffersTraceUntilFlushAll() {
        TraceAssemblyBuffer buffer = new TraceAssemblyBuffer(60_000L, (traceId, spans) -> { });
        try {
            DcSpan span = span("trace-2", "only", "", "api");
            assertThat(buffer.offer(span)).isEmpty();
            assertThat(buffer.pendingTraces()).isEqualTo(1);
            assertThat(buffer.flushAll()).hasSize(1);
        } finally {
            buffer.close();
        }
    }

    @Test
    void fillProcessorProducesMetrics() throws Exception {
        DcSpan span = span("trace-3", "s1", "", "billing");
        TraceFillProcessor.FillResult result = new TraceFillProcessor().processTrace(List.of(span));
        assertThat(result.filledSpanBytes()).hasSize(1);
        assertThat(result.metrics()).isNotEmpty();
    }

    @Test
    void fillTreatsParentIdZeroAsRoot() throws Exception {
        DcSpan root = span("trace-zero-parent", "root", "0", "gateway");
        root.is_parent = 0;

        List<DcSpan> filled = FillPathAndRelationUtil.fillBytes(List.of(
                DCSpanJsonEncoder.encode(root)));
        assertThat(filled.get(0).is_parent).isEqualTo(1);
    }

    @Test
    void fillProcessorSkipsServiceFlowWithoutIsParentRoot() throws Exception {
        DcSpan downstream = span("trace-8", "downstream", "external-parent", "service-k");
        downstream.type = "SPAN_KIND_SERVER";
        downstream.is_parent = 0;

        TraceFillProcessor.FillResult result = new TraceFillProcessor().processTrace(List.of(downstream));
        assertThat(result.metrics().stream().map(OptimizedMetric::measurement))
                .doesNotContain("service.flow");
    }

    @Test
    void fillProcessorProducesServiceFlowMetric() throws Exception {
        DcSpan root = span("trace-5", "root", "", "gateway");
        DcSpan child = span("trace-5", "child", "root", "checkout");
        TraceFillProcessor.FillResult result = new TraceFillProcessor().processTrace(List.of(root, child));
        assertThat(result.metrics().stream().map(m -> m.measurement()))
                .contains("service.flow");
    }

    @Test
    void sameServiceChildDoesNotMarkInbound() throws Exception {
        DcSpan root = span("trace-7", "root", "", "billing");
        root.type = "SPAN_KIND_SERVER";
        root.metaHttpMethod = "GET";
        root.metaHttpStatusCode = 200;
        DcSpan child = span("trace-7", "child", "root", "billing");
        child.metaHttpMethod = "GET";
        child.metaHttpStatusCode = 200;

        List<DcSpan> filled = FillPathAndRelationUtil.fillBytes(List.of(
                DCSpanJsonEncoder.encode(root),
                DCSpanJsonEncoder.encode(child)));

        DcSpan filledChild = filled.stream().filter(s -> "child".equals(s.span_id)).findFirst().orElseThrow();
        assertThat(filledChild.isIn).isZero();

        TraceFillProcessor.FillResult result = new TraceFillProcessor().processTrace(filled);
        long serviceCnt = result.metrics().stream()
                .filter(metric -> "service".equals(metric.measurement()))
                .count();
        assertThat(serviceCnt).isEqualTo(1);
    }

    @Test
    void rootSpanMarksInboundForServiceHttpMetric() throws Exception {
        DcSpan root = span("trace-root-http", "root", "", "service-a");
        root.type = "SPAN_KIND_SERVER";
        root.name = "GET /demo/checkout";
        root.resource = "GET /demo/checkout";
        root.metaHttpMethod = "GET";
        root.metaHttpStatusCode = 200;
        root.metaHttpUrl = "/demo/checkout";

        FillPathAndRelationUtil.fillRelations(List.of(root));
        assertThat(root.isIn).isEqualTo(1);
        assertThat(root.srcService).isNull();
        assertThat(root.srcServiceId).isNull();

        TraceFillProcessor.FillResult result = new TraceFillProcessor().processTrace(List.of(root));
        OptimizedMetric httpMetric = result.metrics().stream()
                .filter(metric -> "service.http".equals(metric.measurement()))
                .findFirst()
                .orElseThrow();
        assertThat(tagValue(httpMetric, "isIn")).isEqualTo("1");
        assertThat(tagValue(httpMetric, "srcService")).isEmpty();
        assertThat(tagValue(httpMetric, "url")).isEqualTo("/demo/checkout");
    }

    @Test
    void demoTraceProducesExpectedServiceCounts() throws Exception {
        DcSpan rootA = span("trace-8", "root-a", "", "service-a");
        rootA.type = "SPAN_KIND_SERVER";
        rootA.name = "GET /demo/checkout";
        rootA.resource = "GET /demo/checkout";
        rootA.metaHttpMethod = "GET";
        rootA.metaHttpStatusCode = 200;
        rootA.metaHttpUrl = "/demo/checkout";
        DcSpan httpClient = span("trace-8", "http-client", "root-a", "service-a");
        httpClient.type = "SPAN_KIND_CLIENT";
        httpClient.name = "HTTP GET service-b /api/orders";
        httpClient.resource = httpClient.name;
        httpClient.metaHttpMethod = "GET";
        httpClient.metaHttpStatusCode = 200;
        httpClient.metaHttpUrl = "http://service-b:8080/api/orders/10001";
        DcSpan httpServer = span("trace-8", "http-server", "http-client", "service-b");
        httpServer.type = "SPAN_KIND_SERVER";
        httpServer.name = "GET /api/orders/{orderId}";
        httpServer.resource = httpServer.name;
        httpServer.metaHttpMethod = "GET";
        httpServer.metaHttpStatusCode = 200;
        httpServer.metaHttpUrl = "/api/orders/10001";
        DcSpan dubboServer = span("trace-8", "dubbo-server", "dubbo-client", "service-b");
        dubboServer.type = "SPAN_KIND_SERVER";
        dubboServer.meta = "{\"rpc.system\":\"dubbo\",\"rpc.method\":\"findInventory\"}";
        DcSpan dubboClient = span("trace-8", "dubbo-client", "root-a", "service-a");
        dubboClient.type = "SPAN_KIND_CLIENT";
        dubboClient.meta = "{\"rpc.system\":\"dubbo\",\"rpc.method\":\"findInventory\"}";

        List<DcSpan> spans = List.of(rootA, httpClient, dubboClient, httpServer, dubboServer);
        FillPathAndRelationUtil.fillRelations(spans);
        assertThat(httpClient.dstService).isEqualTo("service-b");
        assertThat(dubboClient.dstService).isEqualTo("service-b");
        TraceFillProcessor.FillResult result = new TraceFillProcessor().processTrace(spans);

        long serviceACnt = result.metrics().stream()
                .filter(metric -> "service".equals(metric.measurement()))
                .filter(metric -> "service-a".equals(metric.tagValues()[1]))
                .count();
        long serviceBCnt = result.metrics().stream()
                .filter(metric -> "service".equals(metric.measurement()))
                .filter(metric -> "service-b".equals(metric.tagValues()[1]))
                .count();
        assertThat(serviceACnt).isEqualTo(1);
        assertThat(serviceBCnt).isEqualTo(2);

        long serviceAHttpCnt = result.metrics().stream()
                .filter(metric -> "service.http".equals(metric.measurement()))
                .filter(metric -> "service-a".equals(tagValue(metric, "service")))
                .count();
        long serviceBHttpCnt = result.metrics().stream()
                .filter(metric -> "service.http".equals(metric.measurement()))
                .filter(metric -> "service-b".equals(tagValue(metric, "service")))
                .count();
        assertThat(serviceAHttpCnt).isEqualTo(1);
        assertThat(serviceBHttpCnt).isEqualTo(2);
        assertThat(result.metrics().stream()
                .filter(metric -> "service.http".equals(metric.measurement()))
                .filter(metric -> "service-b".equals(tagValue(metric, "service")))
                .filter(metric -> "1".equals(tagValue(metric, "isOut")))
                .map(metric -> tagValue(metric, "url"))
                .toList()).contains("/api/orders/10001");

        long rpcClientCnt = result.metrics().stream()
                .filter(metric -> "service.rpc".equals(metric.measurement()))
                .filter(metric -> "service-b".equals(tagValue(metric, "service")))
                .filter(metric -> "1".equals(tagValue(metric, "isOut")))
                .count();
        long rpcServerCnt = result.metrics().stream()
                .filter(metric -> "service.rpc".equals(metric.measurement()))
                .filter(metric -> "service-b".equals(tagValue(metric, "service")))
                .filter(metric -> "1".equals(tagValue(metric, "isIn")))
                .count();
        assertThat(rpcClientCnt).isEqualTo(1);
        assertThat(rpcServerCnt).isEqualTo(1);
        assertThat(result.metrics().stream().map(OptimizedMetric::measurement))
                .doesNotContain("service.remote");
    }

    @Test
    void clientDbSpanMarksOutboundAndProducesServiceDbMetric() throws Exception {
        DcSpan root = span("trace-9", "root", "", "service-a");
        DcSpan dbClient = span("trace-9", "db-client", "root", "service-a");
        dbClient.type = "SPAN_KIND_CLIENT";
        dbClient.metaPeerHostname = "mysql";
        dbClient.meta = "{\"db.system\":\"mysql\",\"db.name\":\"demo_apm\","
                + "\"db.statement\":\"INSERT INTO demo_order_audit(order_id) VALUES (?)\","
                + "\"server.address\":\"mysql\"}";

        List<DcSpan> spans = List.of(root, dbClient);
        FillPathAndRelationUtil.fillRelations(spans);
        assertThat(dbClient.isOut).isEqualTo(1);
        assertThat(dbClient.isIn).isEqualTo(1);
        assertThat(dbClient.dstService).isEqualTo("mysql");
        assertThat(dbClient.dstServiceId).isEqualTo("dad537de7e10e098");
        assertThat(dbClient.srcService).isEqualTo("service-a");

        TraceFillProcessor.FillResult result = new TraceFillProcessor().processTrace(spans);
        assertThat(result.metrics().stream().map(OptimizedMetric::measurement))
                .contains("service.db");
        OptimizedMetric dbMetric = result.metrics().stream()
                .filter(metric -> "service.db".equals(metric.measurement()))
                .findFirst()
                .orElseThrow();
        assertThat(tagValue(dbMetric, "service")).isEqualTo("mysql");
        assertThat(tagValue(dbMetric, "srcService")).isEqualTo("service-a");
        assertThat(tagValue(dbMetric, "isIn")).isEqualTo("1");
        assertThat(tagValue(dbMetric, "isOut")).isEqualTo("1");
        assertThat(result.metrics().stream().map(OptimizedMetric::measurement))
                .contains("service.flow");
    }

    @Test
    void fillProcessorProducesServiceHttpMetric() throws Exception {
        DcSpan span = span("trace-6", "s1", "", "billing");
        span.type = "SPAN_KIND_SERVER";
        span.name = "GET /billing";
        span.resource = "GET /billing";
        span.metaHttpMethod = "GET";
        span.metaHttpStatusCode = 200;
        TraceFillProcessor.FillResult result = new TraceFillProcessor().processTrace(List.of(span));
        assertThat(result.metrics().stream().map(m -> m.measurement()))
                .contains("service.http");
    }

    @Test
    void assemblyBufferFlushAllDrainsPending() {
        TraceAssemblyBuffer buffer = new TraceAssemblyBuffer(60_000L, (traceId, spans) -> { });
        try {
            DcSpan span = span("trace-4", "s1", "", "api");
            buffer.offer(span);
            assertThat(buffer.flushAll()).hasSize(1);
            assertThat(buffer.pendingTraces()).isZero();
        } finally {
            buffer.close();
        }
    }

    @Test
    void serviceTraceErrorMetricSkipsNonEntrySpan() throws Exception {
        DcSpan rootA = span("trace-entry", "root-a", "", "service-a");
        rootA.type = "SPAN_KIND_SERVER";
        rootA.name = "GET /demo/checkout";
        rootA.resource = "GET /demo/checkout";

        DcSpan dbClient = span("trace-entry", "db-client", "http-server", "service-b");
        dbClient.type = "SPAN_KIND_CLIENT";
        dbClient.resource = "SELECT demo_inventory";
        dbClient.name = "SELECT demo_inventory";
        dbClient.error = 1;
        dbClient.metaErrorType = "InsufficientStockException";

        DcSpan httpServer = span("trace-entry", "http-server", "http-client", "service-b");
        httpServer.type = "SPAN_KIND_SERVER";
        httpServer.name = "GET /api/orders/{orderId}";
        httpServer.resource = httpServer.name;

        List<DcSpan> spans = List.of(rootA, httpServer, dbClient);
        FillPathAndRelationUtil.fillRelations(spans);
        TraceFillProcessor.FillResult result = new TraceFillProcessor().processTrace(spans);

        assertThat(result.metrics().stream()
                .filter(metric -> "service.trace".equals(metric.measurement()))
                .filter(metric -> metric.fieldValues()[1] > 0)
                .map(metric -> tagValue(metric, "service"))
                .toList()).isEmpty();
        assertThat(result.metrics().stream()
                .filter(metric -> "service.trace".equals(metric.measurement()))
                .map(metric -> tagValue(metric, "service"))
                .toList()).containsExactly("service-a");
    }

    @Test
    void propagatesServiceEntryResourceForDbErrorSpan() throws Exception {
        DcSpan httpClient = span("trace-entry", "http-client", "root-a", "service-a");
        httpClient.type = "SPAN_KIND_CLIENT";
        httpClient.name = "HTTP GET service-b /api/orders";
        httpClient.resource = httpClient.name;
        httpClient.metaHttpMethod = "GET";
        httpClient.metaHttpStatusCode = 200;
        httpClient.metaHttpUrl = "http://service-b:8080/api/orders/10001";

        DcSpan httpServer = span("trace-entry", "http-server", "http-client", "service-b");
        httpServer.type = "SPAN_KIND_SERVER";
        httpServer.name = "GET /api/orders/{orderId}";
        httpServer.resource = httpServer.name;
        httpServer.metaHttpMethod = "GET";
        httpServer.metaHttpStatusCode = 200;
        httpServer.metaHttpUrl = "/api/orders/10001";

        DcSpan dbClient = span("trace-entry", "db-client", "http-server", "service-b");
        dbClient.type = "SPAN_KIND_CLIENT";
        dbClient.resource = "SELECT demo_inventory";
        dbClient.name = "SELECT demo_inventory";
        dbClient.error = 1;
        dbClient.metaErrorType = "InsufficientStockException";
        dbClient.meta = "{\"db.system\":\"mysql\",\"db.name\":\"demo_apm\","
                + "\"db.statement\":\"SELECT sku, available FROM demo_inventory WHERE sku = ?\","
                + "\"server.address\":\"mysql\"}";

        List<DcSpan> filled = FillPathAndRelationUtil.fillBytes(List.of(
                DCSpanJsonEncoder.encode(httpClient),
                DCSpanJsonEncoder.encode(httpServer),
                DCSpanJsonEncoder.encode(dbClient)));
        DcSpan filledDb = filled.stream().filter(s -> "db-client".equals(s.span_id)).findFirst().orElseThrow();
        assertThat(OtelAttributeMaps.firstNonBlank(OtelAttributeMaps.parse(filledDb.meta), "entry.resource"))
                .isEqualTo("/api/orders/10001");

        assertThat(DcSpanUtil.parseSpanData(filledDb).stream()
                .map(OptimizedMetric::measurement))
                .doesNotContain("service.exception");
    }

    @Test
    void propagatesServiceEntryResourceForElasticsearchErrorSpan() throws Exception {
        DcSpan httpServer = span("trace-es", "http-server", "", "order-service");
        httpServer.type = "SPAN_KIND_SERVER";
        httpServer.name = "POST /api/search";
        httpServer.resource = httpServer.name;
        httpServer.metaHttpMethod = "POST";
        httpServer.metaHttpStatusCode = 200;
        httpServer.metaHttpUrl = "/api/search";

        DcSpan esClient = span("trace-es", "es-client", "http-server", "order-service");
        esClient.type = "SPAN_KIND_CLIENT";
        esClient.resource = "/my_index_1/_doc/idTest";
        esClient.name = "elasticsearch.rest.query";
        esClient.error = 1;
        esClient.metaErrorType = "ElasticsearchException";
        esClient.meta = "{\"db.system\":\"elasticsearch\",\"db.elasticsearch.index\":\"my_index_1\","
                + "\"server.address\":\"es\",\"server.port\":\"9200\"}";

        List<DcSpan> filled = FillPathAndRelationUtil.fillBytes(List.of(
                DCSpanJsonEncoder.encode(httpServer),
                DCSpanJsonEncoder.encode(esClient)));
        DcSpan filledEs = filled.stream().filter(s -> "es-client".equals(s.span_id)).findFirst().orElseThrow();
        assertThat(OtelAttributeMaps.firstNonBlank(OtelAttributeMaps.parse(filledEs.meta), "entry.resource"))
                .isEqualTo("/api/search");

        assertThat(DcSpanUtil.parseSpanData(filledEs).stream()
                .map(OptimizedMetric::measurement))
                .doesNotContain("service.exception");
    }

    @Test
    void elasticsearchErrorDoesNotInflateWebServiceEntryMetrics() throws Exception {
        DcSpan httpServer = span("trace-es-entry", "http-server", "", "service-g");
        httpServer.type = "SPAN_KIND_SERVER";
        httpServer.name = "POST /api/search";
        httpServer.resource = httpServer.name;
        httpServer.metaHttpMethod = "POST";
        httpServer.metaHttpStatusCode = 200;
        httpServer.metaHttpUrl = "/api/search";

        DcSpan esClient = span("trace-es-entry", "es-client", "http-server", "service-g");
        esClient.type = "SPAN_KIND_CLIENT";
        esClient.resource = "/my_index_1/_doc/idTest";
        esClient.name = "elasticsearch.rest.query";
        esClient.error = 1;
        esClient.metaErrorType = "ElasticsearchException";
        esClient.meta = "{\"db.system\":\"elasticsearch\",\"db.elasticsearch.index\":\"my_index_1\","
                + "\"server.address\":\"es\",\"server.port\":\"9200\"}";

        TraceFillProcessor.FillResult result = new TraceFillProcessor(
                new VirtualServiceExtractor(new VirtualServiceInstanceRegistry(
                        new MetricWriteRouter(Map.of(
                                DorisTableNames.METRIC_SERVICE_INSTANCE, new DorisBatchWriter(16))),
                        60_000L)))
                .processTrace(List.of(httpServer, esClient));

        assertThat(result.metrics().stream()
                .filter(metric -> "service".equals(metric.measurement()))
                .filter(metric -> "service-g".equals(tagValue(metric, "service")))
                .filter(metric -> tagValue(metric, "errorType").equals("error"))
                .toList()).isEmpty();
        assertThat(result.metrics().stream()
                .filter(metric -> "service.exception".equals(metric.measurement()))
                .filter(metric -> "service-g".equals(tagValue(metric, "service")))
                .toList()).isEmpty();
        assertThat(result.metrics().stream()
                .filter(metric -> "service.exception".equals(metric.measurement()))
                .filter(metric -> tagValue(metric, "service").startsWith("[elasticsearch]"))
                .toList()).isNotEmpty();
        assertThat(result.metrics().stream()
                .filter(metric -> "service.db".equals(metric.measurement()))
                .filter(metric -> metric.fieldValues()[1] > 0)
                .toList()).isNotEmpty();
    }

    @Test
    void otelKafkaProducerVirtualizesLikeRedisOutbound() throws Exception {
        DcSpan httpServer = span("trace-mq-prod", "http-server", "", "service-f");
        httpServer.type = "SPAN_KIND_SERVER";
        httpServer.name = "GET /methodB1";
        httpServer.resource = httpServer.name;
        httpServer.metaHttpMethod = "GET";
        httpServer.metaHttpStatusCode = 200;

        DcSpan kafkaPublish = span("trace-mq-prod", "kafka-publish", "http-server", "service-f");
        kafkaPublish.type = "SPAN_KIND_PRODUCER";
        kafkaPublish.resource = "kafka_topic2 publish";
        kafkaPublish.name = "kafka_topic2 publish";
        kafkaPublish.meta = "{\"messaging.system\":\"kafka\",\"messaging.destination.name\":\"kafka_topic2\","
                + "\"messaging.operation\":\"publish\",\"net.peer.name\":\"kafka.test\"}";

        VirtualServiceExtractor extractor = new VirtualServiceExtractor(new VirtualServiceInstanceRegistry(
                new MetricWriteRouter(Map.of(
                        DorisTableNames.METRIC_SERVICE_INSTANCE, new DorisBatchWriter(16))),
                60_000L));
        new TraceFillProcessor(extractor).processTrace(List.of(httpServer, kafkaPublish));

        assertThat(kafkaPublish.service).isEqualTo("[kafka]kafka_topic2");
        assertThat(kafkaPublish.srcService).isEqualTo("service-f");
        assertThat(kafkaPublish.isIn).isEqualTo(1);
        assertThat(kafkaPublish.isOut).isEqualTo(1);
        assertThat(kafkaPublish.dstService).isEqualTo("[kafka]kafka_topic2");
    }

    @Test
    void isolatedElasticsearchErrorSpanDoesNotUseSpanResourceAsRootResource() throws Exception {
        DcSpan esClient = span("trace-es", "es-client", "", "order-service");
        esClient.type = "SPAN_KIND_CLIENT";
        esClient.resource = "/my_index_1/_doc/idTest";
        esClient.name = "elasticsearch.rest.query";
        esClient.error = 1;
        esClient.metaErrorType = "ElasticsearchException";
        esClient.meta = "{\"db.system\":\"elasticsearch\",\"db.elasticsearch.index\":\"my_index_1\","
                + "\"server.address\":\"es\",\"server.port\":\"9200\"}";

        List<DcSpan> filled = FillPathAndRelationUtil.fillBytes(List.of(DCSpanJsonEncoder.encode(esClient)));
        DcSpan filledEs = filled.get(0);

        assertThat(DcSpanUtil.parseSpanData(filledEs).stream()
                .map(OptimizedMetric::measurement))
                .doesNotContain("service.exception");
    }

    @Test
    void assemblyReturnsImmediateSpanForMissingTraceId() {
        TraceAssemblyBuffer buffer = new TraceAssemblyBuffer(60_000L, (traceId, spans) -> { });
        try {
            DcSpan span = span("", "s1", "", "api");
            assertThat(buffer.offer(span)).containsExactly(span);
        } finally {
            buffer.close();
        }
    }

    private static DcSpan span(String traceId, String spanId, String parentId, String service) {
        DcSpan span = new DcSpan();
        span.trace_id = traceId;
        span.span_id = spanId;
        span.parent_id = parentId;
        span.service = service;
        span.serviceId = service + "-id";
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
}
