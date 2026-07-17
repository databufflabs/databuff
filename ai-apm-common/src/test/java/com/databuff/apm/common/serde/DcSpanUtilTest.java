package com.databuff.apm.common.serde;

import com.databuff.apm.common.meta.OtelAttributeMaps;
import com.databuff.apm.common.metric.MetricSchemaRegistry;
import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.model.OptimizedMetric;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DcSpanUtilTest {

    @Test
    void skipsHttpMetricForNonHttpSpan() {
        DcSpan span = baseSpan();
        assertThat(DcSpanUtil.parseSpanData(span).stream().map(OptimizedMetric::measurement))
                .doesNotContain("service.http");
    }

    @Test
    void detectsHttpSpanByUrl() {
        DcSpan span = baseSpan();
        span.metaHttpUrl = "/health";
        assertThat(DcSpanUtil.isHttpSpan(span)).isTrue();
    }

    @Test
    void detectsHttpSpanByStatusCode() {
        DcSpan span = baseSpan();
        span.metaHttpStatusCode = 503;
        assertThat(DcSpanUtil.isHttpSpan(span)).isTrue();
    }

    @Test
    void httpInboundMetricUsesIsInFromSpanName() {
        DcSpan span = baseSpan();
        span.name = "GET /demo/checkout";
        span.resource = "GET /demo/checkout";
        span.type = "SPAN_KIND_SERVER";
        span.metaHttpMethod = "GET";
        span.metaHttpStatusCode = 200;
        OptimizedMetric http = DcSpanUtil.parseSpanData(span).stream()
                .filter(m -> "service.http".equals(m.measurement()))
                .findFirst()
                .orElseThrow();
        assertThat(tagValue(http, "isIn")).isEqualTo("1");
        assertThat(tagValue(http, "isOut")).isEqualTo("0");
    }

    @Test
    void usesResourceWhenUrlMissing() {
        DcSpan span = baseSpan();
        span.type = "SPAN_KIND_SERVER";
        span.name = "POST /orders";
        span.metaHttpMethod = "POST";
        span.resource = "POST /orders";
        OptimizedMetric http = DcSpanUtil.parseSpanData(span).stream()
                .filter(m -> "service.http".equals(m.measurement()))
                .findFirst()
                .orElseThrow();
        assertThat(tagValue(http, "url")).isEqualTo("POST /orders");
    }

    private static String tagValue(OptimizedMetric metric, String column) {
        String[] tags = metric.tagValues();
        var schema = MetricSchemaRegistry.schema(metric.measurement()).orElseThrow();
        int index = schema.tagColumns().indexOf(column);
        return index >= 0 && index < tags.length ? tags[index] : "";
    }

    @Test
    void serviceExceptionSkipsVirtualInboundDbSpan() {
        DcSpan span = baseSpan();
        span.type = "SPAN_KIND_CLIENT";
        span.parent_id = "parent-span";
        span.srcService = "checkout";
        span.srcServiceId = "svc";
        span.srcServiceInstance = "inst-1";
        span.isIn = 1;
        span.isOut = 1;
        span.error = 1;
        span.metaErrorType = "SQLException";
        span.dstService = "[mysql]demo_apm";
        span.dstServiceId = "dad537de7e10e098";
        span.dstServiceInstance = "10.0.0.8";
        span.meta = "{\"db.system\":\"mysql\",\"db.name\":\"demo_apm\",\"db.operation\":\"select\","
                + "\"entry.resource\":\"/api/orders/10001\"}";

        assertThat(DcSpanUtil.serviceExceptionMetric(span)).isNull();
    }

    @Test
    void virtualComponentExceptionMetricForInboundDbSpan() {
        DcSpan span = baseSpan();
        span.type = "SPAN_KIND_CLIENT";
        span.parent_id = "parent-span";
        span.srcService = "checkout";
        span.srcServiceId = "svc";
        span.srcServiceInstance = "inst-1";
        span.isIn = 1;
        span.isOut = 1;
        span.error = 1;
        span.metaErrorType = "SQLException";
        span.dstService = "[mysql]demo_apm";
        span.dstServiceId = "dad537de7e10e098";
        span.dstServiceInstance = "10.0.0.8";
        span.meta = "{\"db.system\":\"mysql\",\"db.name\":\"demo_apm\",\"db.operation\":\"select\","
                + "\"root.resource\":\"/api/orders/10001\"}";

        OptimizedMetric exceptionMetric = DcSpanUtil.virtualComponentExceptionMetric(span);
        assertThat(exceptionMetric).isNotNull();
        assertThat(exceptionMetric.measurement()).isEqualTo("service.exception");
        assertThat(tagValue(exceptionMetric, "service")).isEqualTo("[mysql]demo_apm");
        assertThat(tagValue(exceptionMetric, "service_id")).isEqualTo("dad537de7e10e098");
        assertThat(tagValue(exceptionMetric, "exceptionName")).isEqualTo("SQLException");
        assertThat(tagValue(exceptionMetric, "componentService")).isEqualTo("checkout");
        assertThat(tagValue(exceptionMetric, "rootResource")).isEqualTo("/api/orders/10001");

        Assertions.assertThat(DcSpanUtil.parseSpanData(span).stream().map(OptimizedMetric::measurement))
                .contains("service.exception");
    }

    @Test
    void serviceExceptionAttributesToSrcServiceWhenCurrentServiceIsVirtual() {
        DcSpan span = baseSpan();
        span.service = "[mysql]demo_apm";
        span.serviceId = "dad537de7e10e098";
        span.srcService = "checkout";
        span.srcServiceId = "svc";
        span.srcServiceInstance = "inst-1";
        span.isIn = 1;
        span.error = 1;
        span.metaErrorType = "SQLException";

        OptimizedMetric exceptionMetric = DcSpanUtil.parseSpanData(span).stream()
                .filter(metric -> "service.exception".equals(metric.measurement()))
                .findFirst()
                .orElseThrow();
        assertThat(tagValue(exceptionMetric, "service")).isEqualTo("checkout");
        assertThat(tagValue(exceptionMetric, "service_id")).isEqualTo("1af678a9b03e993f");
    }

    @Test
    void virtualInboundComponentErrorCountsOnComponentMetricNotWebService() {
        DcSpan span = baseSpan();
        span.type = "SPAN_KIND_CLIENT";
        span.parent_id = "parent-span";
        span.srcService = "checkout";
        span.srcServiceId = "svc";
        span.srcServiceInstance = "inst-1";
        span.isIn = 1;
        span.isOut = 1;
        span.error = 1;
        span.dstService = "[mysql]demo_apm";
        span.dstServiceId = "dad537de7e10e098";
        span.dstServiceInstance = "10.0.0.8";
        span.meta = "{\"db.system\":\"mysql\",\"db.name\":\"demo_apm\",\"db.operation\":\"select\"}";

        List<OptimizedMetric> metrics = DcSpanUtil.parseSpanData(span);
        OptimizedMetric virtualEntry = metrics.stream()
                .filter(m -> "service".equals(m.measurement())
                        && "[mysql]demo_apm".equals(tagValue(m, "service")))
                .findFirst()
                .orElseThrow();
        assertThat(tagValue(virtualEntry, "errorType")).isEqualTo("error");
        assertThat(virtualEntry.fieldValues()[1]).isEqualTo(1L);

        assertThat(metrics.stream()
                .filter(m -> "service".equals(m.measurement())
                        && "checkout".equals(tagValue(m, "service")))
                .findFirst()).isEmpty();

        OptimizedMetric db = metrics.stream()
                .filter(m -> "service.db".equals(m.measurement()))
                .findFirst()
                .orElseThrow();
        assertThat(db.fieldValues()[1]).isEqualTo(1L);

        OptimizedMetric exceptionMetric = DcSpanUtil.virtualComponentExceptionMetric(span);
        assertThat(exceptionMetric).isNotNull();
        assertThat(tagValue(exceptionMetric, "service")).isEqualTo("[mysql]demo_apm");
        assertThat(tagValue(exceptionMetric, "componentService")).isEqualTo("checkout");
        assertThat(DcSpanUtil.serviceExceptionMetric(span)).isNull();
    }

    @Test
    void emitsServiceExceptionForErrorSpan() {
        DcSpan span = baseSpan();
        span.isIn = 1;
        span.error = 1;
        span.metaHttpStatusCode = 500;
        Assertions.assertThat(DcSpanUtil.parseSpanData(span).stream().map(OptimizedMetric::measurement))
                .contains("service.exception");
        assertThat(DcSpanUtil.resolveErrorType(span)).isEqualTo("HTTP 500");
    }

    @Test
    void serviceExceptionUsesErrorSpanResource() {
        DcSpan span = baseSpan();
        span.type = "SPAN_KIND_SERVER";
        span.parent_id = "";
        span.isIn = 1;
        span.error = 1;
        span.resource = "GET /api/orders/10001";
        span.name = "GET /api/orders/10001";
        span.metaErrorType = "InsufficientStockException";
        span.meta = "{\"entry.resource\":\"/api/orders/10001\"}";
        OptimizedMetric exceptionMetric = DcSpanUtil.parseSpanData(span).stream()
                .filter(metric -> "service.exception".equals(metric.measurement()))
                .findFirst()
                .orElseThrow();
        assertThat(tagValue(exceptionMetric, "resource")).isEqualTo("GET /api/orders/10001");
        assertThat(tagValue(exceptionMetric, "exceptionName")).isEqualTo("InsufficientStockException");
    }

    @Test
    void serviceExceptionSkipsComponentErrorSpan() {
        DcSpan span = baseSpan();
        span.type = "SPAN_KIND_CLIENT";
        span.error = 1;
        span.resource = "/my_index_1/_doc/idTest";
        span.name = "elasticsearch.rest.query";
        span.metaErrorType = "ElasticsearchException";
        span.meta = "{\"db.system\":\"elasticsearch\",\"root.resource\":\"/api/search\"}";

        Assertions.assertThat(DcSpanUtil.parseSpanData(span).stream().map(OptimizedMetric::measurement))
                .doesNotContain("service.exception");
    }

    @Test
    void skipsServiceExceptionForSuccessSpan() {
        DcSpan span = baseSpan();
        Assertions.assertThat(DcSpanUtil.parseSpanData(span).stream().map(OptimizedMetric::measurement))
                .doesNotContain("service.exception");
    }

    @Test
    void emitsServiceRpcForRpcSpan() {
        DcSpan span = baseSpan();
        span.meta = "{\"rpc.system\":\"dubbo\",\"rpc.service\":\"com.demo.OrderService\",\"rpc.method\":\"findInventory\"}";
        Assertions.assertThat(DcSpanUtil.parseSpanData(span).stream().map(OptimizedMetric::measurement))
                .contains("service.rpc");
    }

    @Test
    void skipsRpcMetricForHttpSpan() {
        DcSpan span = baseSpan();
        span.metaHttpMethod = "GET";
        span.meta = "{\"rpc.system\":\"grpc\"}";
        Assertions.assertThat(DcSpanUtil.parseSpanData(span).stream().map(OptimizedMetric::measurement))
                .doesNotContain("service.rpc");
    }

    @Test
    void skipsServiceFlowForDbSpan() {
        DcSpan span = baseSpan();
        span.parent_id = "parent-span";
        span.srcService = "service-a";
        span.dstService = "mysql";
        span.meta = "{\"db.system\":\"mysql\",\"db.operation\":\"select\"}";
        Assertions.assertThat(DcSpanUtil.parseSpanData(span).stream().map(OptimizedMetric::measurement))
                .contains("service.db")
                .doesNotContain("service.flow");
    }

    @Test
    void emitsVirtualServiceDbMetricWithInboundTags() {
        DcSpan span = baseSpan();
        span.type = "SPAN_KIND_CLIENT";
        span.parent_id = "parent-span";
        span.srcService = "checkout";
        span.srcServiceId = "svc";
        span.srcServiceInstance = "inst-1";
        span.isIn = 1;
        span.isOut = 1;
        span.dstService = "[mysql]demo_apm";
        span.dstServiceId = "dad537de7e10e098";
        span.dstServiceInstance = "10.0.0.8";
        span.meta = "{\"db.system\":\"mysql\",\"db.name\":\"demo_apm\",\"db.operation\":\"select\","
                + "\"server.address\":\"10.0.0.8\",\"server.port\":\"3306\"}";
        OptimizedMetric db = DcSpanUtil.parseSpanData(span).stream()
                .filter(m -> "service.db".equals(m.measurement()))
                .findFirst()
                .orElseThrow();
        assertThat(tagValue(db, "service")).isEqualTo("[mysql]demo_apm");
        assertThat(tagValue(db, "service_instance")).isEqualTo("10.0.0.8");
        assertThat(tagValue(db, "isIn")).isEqualTo("1");
        assertThat(tagValue(db, "isOut")).isEqualTo("1");
        assertThat(tagValue(db, "srcService")).isEqualTo("checkout");

        OptimizedMetric entry = DcSpanUtil.parseSpanData(span).stream()
                .filter(m -> "service".equals(m.measurement()))
                .findFirst()
                .orElseThrow();
        assertThat(tagValue(entry, "service")).isEqualTo("[mysql]demo_apm");
        assertThat(tagValue(entry, "service_instance")).isEqualTo("10.0.0.8");
    }

    @Test
    void emitsVirtualServiceMqMetricWithInboundTags() {
        DcSpan span = baseSpan();
        span.type = "SPAN_KIND_CLIENT";
        span.parent_id = "parent-span";
        span.isIn = 1;
        span.isOut = 1;
        span.dstService = "[kafka]order-events";
        span.dstServiceId = "abc123";
        span.dstServiceInstance = "broker-1";
        span.meta = "{\"messaging.system\":\"kafka\",\"messaging.destination.name\":\"order-events\","
                + "\"net.peer.name\":\"broker-1\",\"messaging.operation\":\"publish\"}";
        OptimizedMetric mq = DcSpanUtil.parseSpanData(span).stream()
                .filter(m -> "service.mq".equals(m.measurement()))
                .findFirst()
                .orElseThrow();
        assertThat(tagValue(mq, "service")).isEqualTo("[kafka]order-events");
        assertThat(tagValue(mq, "service_instance")).isEqualTo("broker-1");
        assertThat(tagValue(mq, "isIn")).isEqualTo("1");
        assertThat(tagValue(mq, "isConsume")).isEqualTo("0");
    }

    @Test
    void internalHttpClientMetricUsesCalleeServiceDimension() {
        DcSpan span = baseSpan();
        span.type = "SPAN_KIND_CLIENT";
        span.parent_id = "parent-span";
        span.service = "service-a";
        span.serviceId = "a1b2c3d4e5f67890";
        span.srcService = "service-a";
        span.srcServiceId = "a1b2c3d4e5f67890";
        span.dstService = "service-b";
        span.dstServiceId = "b1c2d3e4f5a67890";
        span.isOut = 1;
        span.metaHttpMethod = "GET";
        span.metaHttpStatusCode = 200;
        span.metaHttpUrl = "http://service-b:8080/api/orders/10001";
        OptimizedMetric http = DcSpanUtil.parseSpanData(span).stream()
                .filter(m -> "service.http".equals(m.measurement()))
                .findFirst()
                .orElseThrow();
        assertThat(tagValue(http, "service")).isEqualTo("service-b");
        assertThat(tagValue(http, "service_id")).isEqualTo("b1c2d3e4f5a67890");
        assertThat(tagValue(http, "srcService")).isEqualTo("service-a");
        assertThat(tagValue(http, "isOut")).isEqualTo("1");
    }

    @Test
    void emitsServiceDbForDbSpan() {
        DcSpan span = baseSpan();
        span.parent_id = "parent-span";
        span.srcService = "checkout";
        span.srcServiceId = "svc";
        span.dstService = "mysql";
        span.dstServiceId = "mysql";
        span.isIn = 1;
        span.isOut = 1;
        span.meta = "{\"db.system\":\"mysql\",\"db.name\":\"demo_apm\",\"db.operation\":\"select\","
                + "\"db.statement\":\"SELECT id FROM demo_order WHERE id = ?\"}";
        OptimizedMetric db = DcSpanUtil.parseSpanData(span).stream()
                .filter(m -> "service.db".equals(m.measurement()))
                .findFirst()
                .orElseThrow();
        assertThat(tagValue(db, "service")).isEqualTo("mysql");
        assertThat(tagValue(db, "service_id")).isEqualTo("dad537de7e10e098");
        assertThat(tagValue(db, "srcService")).isEqualTo("checkout");
        assertThat(tagValue(db, "isIn")).isEqualTo("1");
        assertThat(tagValue(db, "isOut")).isEqualTo("1");
    }

    @Test
    void skipsDbMetricForHttpSpan() {
        DcSpan span = baseSpan();
        span.metaHttpMethod = "GET";
        span.meta = "{\"db.system\":\"mysql\"}";
        Assertions.assertThat(DcSpanUtil.parseSpanData(span).stream().map(OptimizedMetric::measurement))
                .doesNotContain("service.db");
    }

    @Test
    void emitsServiceMqForMessagingSpan() {
        DcSpan span = baseSpan();
        span.parent_id = "parent-span";
        span.meta = "{\"messaging.system\":\"kafka\",\"messaging.destination.name\":\"orders\","
                + "\"messaging.kafka.consumer.group\":\"checkout-group\","
                + "\"messaging.operation\":\"receive\",\"messaging.kafka.partition\":\"3\"}";
        OptimizedMetric mq = DcSpanUtil.parseSpanData(span).stream()
                .filter(m -> "service.mq".equals(m.measurement()))
                .findFirst()
                .orElseThrow();
        assertThat(tagValue(mq, "type")).isEqualTo("kafka");
        assertThat(tagValue(mq, "topic")).isEqualTo("orders");
        assertThat(tagValue(mq, "group")).isEqualTo("checkout-group");
        assertThat(tagValue(mq, "isConsume")).isEqualTo("1");
    }

    @Test
    void emitsServiceDbForElasticsearchSpan() {
        DcSpan span = baseSpan();
        span.parent_id = "parent-span";
        span.meta = "{\"db.system\":\"elasticsearch\",\"db.elasticsearch.index\":\"orders\","
                + "\"http.method\":\"GET\",\"http.url\":\"http://es:9200/orders/_search\"}";
        OptimizedMetric db = DcSpanUtil.parseSpanData(span).stream()
                .filter(m -> "service.db".equals(m.measurement()))
                .findFirst()
                .orElseThrow();
        Assertions.assertThat(DcSpanUtil.parseSpanData(span).stream().map(OptimizedMetric::measurement))
                .doesNotContain("service.es");
        assertThat(tagValue(db, "dbType")).isEqualTo("elasticsearch");
        assertThat(tagValue(db, "sqlDatabase")).isEqualTo("orders");
        assertThat(tagValue(db, "sqlOperation")).isEqualTo("GET");
        assertThat(tagValue(db, "sqlContent")).isEqualTo("/orders/_search");
    }

    @Test
    void classifiesElasticsearchAsDatabaseEvenWithMaterializedHttpFields() {
        DcSpan span = baseSpan();
        span.parent_id = "parent-span";
        span.metaHttpMethod = "GET";
        span.metaHttpUrl = "http://es:9200/orders/_search";
        span.meta = "{\"db.system\":\"elasticsearch\",\"db.elasticsearch.index\":\"orders\","
                + "\"http.method\":\"GET\",\"http.url\":\"http://es:9200/orders/_search\","
                + "\"server.address\":\"es\",\"server.port\":\"9200\"}";

        assertThat(DcSpanUtil.isEsSpan(span)).isTrue();
        assertThat(DcSpanUtil.isHttpSpan(span)).isFalse();
        Assertions.assertThat(DcSpanUtil.parseSpanData(span).stream().map(OptimizedMetric::measurement))
                .contains("service.db")
                .doesNotContain("service.http", "service.remote");
    }

    @Test
    void skipsServiceRemoteForVirtualComponentEvenWhenRemoteFlagLeaksIn() {
        DcSpan span = baseSpan();
        span.type = "SPAN_KIND_CLIENT";
        span.isIn = 1;
        span.isOut = 1;
        span.srcService = "service-a";
        span.srcServiceId = "service-a-id";
        span.dstService = "[elasticsearch]es:9200";
        span.dstServiceId = "es-id";
        span.dstServiceInstance = "es";
        span.metaHttpMethod = "GET";
        span.metaHttpUrl = "http://es:9200/orders/_search";
        span.meta = "{\"remote\":\"true\",\"db.system\":\"elasticsearch\","
                + "\"db.elasticsearch.index\":\"orders\",\"http.method\":\"GET\","
                + "\"http.url\":\"http://es:9200/orders/_search\","
                + "\"server.address\":\"es\",\"server.port\":\"9200\"}";

        Assertions.assertThat(DcSpanUtil.parseSpanData(span).stream().map(OptimizedMetric::measurement))
                .contains("service.db")
                .doesNotContain("service.remote");
    }

    @Test
    void emitsServiceConfigForConfigSpan() {
        DcSpan span = baseSpan();
        span.parent_id = "parent-span";
        span.meta = "{\"config.type\":\"nacos\",\"config.operation\":\"get\"}";
        OptimizedMetric config = DcSpanUtil.parseSpanData(span).stream()
                .filter(m -> "service.config".equals(m.measurement()))
                .findFirst()
                .orElseThrow();
        assertThat(tagValue(config, "config.type")).isEqualTo("nacos");
        assertThat(tagValue(config, "operation")).isEqualTo("get");
    }

    @Test
    void emitsServiceRemoteForExternalVirtualHttpSpan() {
        DcSpan span = baseSpan();
        span.type = "SPAN_KIND_CLIENT";
        span.isIn = 1;
        span.isOut = 1;
        span.srcService = "service-a";
        span.srcServiceId = "service-a-id";
        span.dstService = "[remote]api.example.com:443";
        span.dstServiceId = "remote-id";
        span.dstServiceInstance = "api.example.com";
        span.metaHttpMethod = "GET";
        span.meta = "{\"remote\":\"true\",\"http.method\":\"GET\",\"server.address\":\"api.example.com\",\"server.port\":\"443\"}";

        OptimizedMetric remote = DcSpanUtil.parseSpanData(span).stream()
                .filter(m -> "service.remote".equals(m.measurement()))
                .findFirst()
                .orElseThrow();
        assertThat(tagValue(remote, "service")).isEqualTo("[remote]api.example.com:443");
        assertThat(tagValue(remote, "remoteType")).isEqualTo("http");
        assertThat(tagValue(remote, "isIn")).isEqualTo("1");
        assertThat(tagValue(remote, "isOut")).isEqualTo("1");
        assertThat(tagValue(remote, "srcService")).isEqualTo("service-a");
    }

    @Test
    void serviceRemoteMapsDurationToSumDurationAndCountsErrorOnRemoteMetric() {
        DcSpan span = baseSpan();
        span.type = "SPAN_KIND_CLIENT";
        span.isIn = 1;
        span.isOut = 1;
        span.service = "service-a";
        span.serviceId = "service-a-id";
        span.srcService = "service-a";
        span.srcServiceId = "service-a-id";
        span.dstService = "[remote]api.example.com:443";
        span.dstServiceId = "remote-id";
        span.error = 1;
        span.duration = 7_000_000L;
        span.metaHttpMethod = "GET";
        span.meta = "{\"remote\":\"true\",\"http.method\":\"GET\",\"server.address\":\"api.example.com\"}";

        List<OptimizedMetric> metrics = DcSpanUtil.parseSpanData(span);
        OptimizedMetric remote = metrics.stream()
                .filter(m -> "service.remote".equals(m.measurement()))
                .findFirst()
                .orElseThrow();
        assertThat(remote.fieldValues()[0]).isEqualTo(1L);
        assertThat(remote.fieldValues()[1]).isEqualTo(1L);
        assertThat(remote.fieldValues()[2]).isEqualTo(7_000_000L);

        assertThat(metrics.stream()
                .filter(m -> "service".equals(m.measurement())
                        && "service-a".equals(tagValue(m, "service")))
                .findFirst()).isEmpty();

        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        MetricSchemaRegistry.applyFieldValues(
                row, "service.remote", remote.fieldValues());
        assertThat(row.get("cnt")).isEqualTo(1L);
        assertThat(row.get("error")).isEqualTo(1L);
        assertThat(row.get("sumDuration")).isEqualTo(7_000_000L);
    }

    @Test
    void skipsServiceRemoteWhenLinkedToInternalPeer() {
        DcSpan span = baseSpan();
        span.type = "SPAN_KIND_CLIENT";
        span.isOut = 1;
        span.srcService = "service-a";
        span.srcServiceId = "service-a-id";
        span.dstService = "service-b";
        span.dstServiceId = "service-b-id";
        span.meta = "{\"rpc.system\":\"dubbo\"}";

        Assertions.assertThat(DcSpanUtil.parseSpanData(span).stream().map(OptimizedMetric::measurement))
                .doesNotContain("service.remote");
    }

    @Test
    void internalRpcClientMetricUsesCalleeServiceDimension() {
        DcSpan span = baseSpan();
        span.service = "service-a";
        span.serviceId = "service-a-id";
        span.type = "SPAN_KIND_CLIENT";
        span.isOut = 1;
        span.srcService = "service-a";
        span.srcServiceId = "service-a-id";
        span.dstService = "service-b";
        span.dstServiceId = "service-b-id";
        span.meta = "{\"rpc.system\":\"dubbo\",\"rpc.method\":\"findInventory\"}";

        OptimizedMetric rpc = DcSpanUtil.parseSpanData(span).stream()
                .filter(m -> "service.rpc".equals(m.measurement()))
                .findFirst()
                .orElseThrow();
        assertThat(tagValue(rpc, "service")).isEqualTo("service-b");
        assertThat(tagValue(rpc, "srcService")).isEqualTo("service-a");
        assertThat(tagValue(rpc, "type")).isEqualTo("dubbo");
        assertThat(tagValue(rpc, "isOut")).isEqualTo("1");
    }

    @Test
    void buildsServiceInstanceTagsFromResourceAttributes() {
        DcSpan span = baseSpan();
        span.service = "demo-order";
        span.serviceId = "demo-order-id";
        span.serviceInstance = "demo-order-1";
        span.hostName = "app-1";
        span.meta = """
                {
                  "host.name":"app-1",
                  "host.ip":"10.0.0.8",
                  "k8s.namespace.name":"prod",
                  "k8s.pod.name":"demo-order-abc",
                  "process.runtime.version":"17.0.8"
                }
                """;
        var tags = DcSpanUtil.serviceInstanceTags(
                span, span.serviceInstance, OtelAttributeMaps.parse(span.meta));
        assertThat(tags.get("service")).isEqualTo("demo-order");
        assertThat(tags.get("service_id")).isEqualTo("464a0a08964a061e");
        assertThat(tags.get("service_instance")).isEqualTo("demo-order-1");
        assertThat(tags.get("hostname")).isEqualTo("app-1");
        assertThat(tags.get("hostIp")).isEqualTo("10.0.0.8");
        assertThat(tags.get("k8sNamespace")).isEqualTo("prod");
        assertThat(tags.get("k8sPodName")).isEqualTo("demo-order-abc");
        assertThat(tags.get("javaVersion")).isEqualTo("17.0.8");
    }

    @Test
    void parseSpanDataDoesNotEmitServiceInstance() {
        DcSpan span = baseSpan();
        Assertions.assertThat(DcSpanUtil.parseSpanData(span).stream().map(OptimizedMetric::measurement))
                .doesNotContain("service.instance");
    }

    @Test
    void emitsServiceRedisForRedisSpan() {
        DcSpan span = baseSpan();
        span.parent_id = "parent-span";
        span.meta = "{\"db.system\":\"redis\",\"db.statement\":\"GET cart\"}";
        Assertions.assertThat(DcSpanUtil.parseSpanData(span).stream().map(OptimizedMetric::measurement))
                .contains("service.redis")
                .doesNotContain("service.db");
    }

    @Test
    void resolvePortalSpanDisplayForComponentSpans() {
        DcSpan http = baseSpan();
        http.metaHttpMethod = "GET";
        http.metaHttpUrl = "/demo/checkout";
        assertThat(DcSpanUtil.resolvePortalSpanDisplay(http))
                .isEqualTo(new DcSpanUtil.PortalSpanDisplay("web", "web"));

        DcSpan db = baseSpan();
        db.meta = "{\"db.system\":\"mysql\",\"db.operation\":\"select\"}";
        assertThat(DcSpanUtil.resolvePortalSpanDisplay(db))
                .isEqualTo(new DcSpanUtil.PortalSpanDisplay("db", "mysql"));

        DcSpan cache = baseSpan();
        cache.meta = "{\"db.system\":\"redis\",\"db.statement\":\"SET order:10001\"}";
        assertThat(DcSpanUtil.resolvePortalSpanDisplay(cache))
                .isEqualTo(new DcSpanUtil.PortalSpanDisplay("cache", "redis"));

        DcSpan mq = baseSpan();
        mq.meta = "{\"messaging.system\":\"kafka\",\"messaging.destination.name\":\"orders\"}";
        assertThat(DcSpanUtil.resolvePortalSpanDisplay(mq))
                .isEqualTo(new DcSpanUtil.PortalSpanDisplay("mq", "kafka"));

        DcSpan dubbo = baseSpan();
        dubbo.name = "dubbo.call";
        dubbo.resource = "com.demo.OrderService.findInventory(String)";
        dubbo.meta = "{\"component\":\"apache-dubbo\",\"dubbo-version\":\"2.7\","
                + "\"method\":\"findInventory\",\"url\":\"dubbo://service-b:20880/com.demo.OrderService\","
                + "\"peer.hostname\":\"service-b\",\"peer.port\":\"20880\","
                + "\"server.service\":\"service-b\",\"server.ip\":\"10.0.0.8\"}";
        assertThat(DcSpanUtil.isRpcSpan(dubbo)).isTrue();
        assertThat(DcSpanUtil.resolvePortalSpanDisplay(dubbo))
                .isEqualTo(new DcSpanUtil.PortalSpanDisplay("custom", "dubbo"));
        assertThat(DcSpanUtil.parseSpanData(dubbo).stream().map(OptimizedMetric::measurement))
                .contains("service.rpc");

        DcSpan grpc = baseSpan();
        grpc.name = "grpc.client";
        grpc.meta = "{\"component\":\"grpc-client\",\"request.type\":\"com.demo.Request\","
                + "\"response.type\":\"com.demo.Response\",\"status.code\":\"0\"}";
        assertThat(DcSpanUtil.isRpcSpan(grpc)).isTrue();
        assertThat(DcSpanUtil.resolvePortalSpanDisplay(grpc))
                .isEqualTo(new DcSpanUtil.PortalSpanDisplay("custom", "grpc"));

        DcSpan legacySkyWalkingDubbo = baseSpan();
        legacySkyWalkingDubbo.name = "com.demo.OrderService.findInventory(String)";
        legacySkyWalkingDubbo.resource = legacySkyWalkingDubbo.name;
        legacySkyWalkingDubbo.metaHttpUrl = "dubbo://service-b:20880/com.demo.OrderService.findInventory(String)";
        legacySkyWalkingDubbo.meta = "{\"url\":\"dubbo://service-b:20880/com.demo.OrderService.findInventory(String)\","
                + "\"skywalking.componentId\":\"3\",\"skywalking.spanLayer\":\"RPCFramework\"}";
        assertThat(DcSpanUtil.isRpcSpan(legacySkyWalkingDubbo)).isTrue();
        assertThat(DcSpanUtil.resolvePortalSpanDisplay(legacySkyWalkingDubbo))
                .isEqualTo(new DcSpanUtil.PortalSpanDisplay("custom", "dubbo"));
    }

    private static DcSpan baseSpan() {
        DcSpan span = new DcSpan();
        span.service = "checkout";
        span.serviceId = "svc";
        span.serviceInstance = "inst";
        span.resource = "GET /cart";
        span.error = 0;
        span.duration = 120_000_000L;
        span.start = 1_700_000_000_000_000_000L;
        return span;
    }
}
