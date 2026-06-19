package com.databuff.apm.ingest.trace;

import com.databuff.apm.common.model.DcSpan;

import java.util.ArrayList;
import java.util.List;

/** Demo checkout trace spans aligned with {@code OtlpTraceFixture#sampleTraceExport}. */
final class DemoTraceSpans {

    private DemoTraceSpans() {
    }

    static List<DcSpan> checkoutTrace() {
        List<DcSpan> spans = new ArrayList<>();
        long traceStart = 1_700_000_000_000_000_000L;

        DcSpan root = span("trace-demo", "root-a", "", "service-a", "9bf61532d56eb7b5", traceStart, 240_000_000L);
        root.type = "SPAN_KIND_SERVER";
        root.name = "GET /demo/checkout";
        root.resource = "GET /demo/checkout";
        root.metaHttpMethod = "GET";
        root.metaHttpStatusCode = 200;
        root.metaHttpUrl = "/demo/checkout";
        spans.add(root);

        spans.add(dbClient("trace-demo", "redis-client", "root-a", "service-a", "9bf61532d56eb7b5", traceStart + 5_000_000L,
                13_000_000L, "redis", "GET cart:10001", "redis", "6379"));
        spans.add(httpClient("trace-demo", "remote-http", "root-a", "service-a", "9bf61532d56eb7b5", traceStart + 12_000_000L,
                7_000_000L, "payments.example.com", "443", "https://payments.example.com/api/risk/check"));
        spans.add(httpClient("trace-demo", "http-client", "root-a", "service-a", "9bf61532d56eb7b5", traceStart + 20_000_000L,
                100_000_000L, "service-b", "8080", "http://service-b:8080/api/orders/10001"));
        spans.add(esClient("trace-demo", "es-client", "root-a", "service-a", "9bf61532d56eb7b5", traceStart + 100_000_000L,
                18_000_000L));
        spans.add(rpcClient("trace-demo", "dubbo-client", "root-a", "service-a", "9bf61532d56eb7b5", traceStart + 125_000_000L,
                80_000_000L));
        spans.add(dbClient("trace-demo", "audit-mysql", "root-a", "service-a", "9bf61532d56eb7b5", traceStart + 208_000_000L,
                20_000_000L, "mysql", "INSERT INTO demo_order_audit(order_id, channel) VALUES (?, ?)", "mysql", "3306"));
        spans.add(mqClient("trace-demo", "kafka-client", "root-a", "service-a", "9bf61532d56eb7b5", traceStart + 230_000_000L,
                8_000_000L));

        DcSpan httpServer = span("trace-demo", "http-server", "http-client", "service-b", "5457a0119281bb98",
                traceStart + 30_000_000L, 80_000_000L);
        httpServer.type = "SPAN_KIND_SERVER";
        httpServer.name = "GET /api/orders/{orderId}";
        httpServer.resource = httpServer.name;
        httpServer.metaHttpMethod = "GET";
        httpServer.metaHttpStatusCode = 200;
        httpServer.metaHttpUrl = "/api/orders/10001";
        spans.add(httpServer);

        spans.add(dbClient("trace-demo", "http-mysql", "http-server", "service-b", "5457a0119281bb98", traceStart + 45_000_000L,
                45_000_000L, "mysql", "SELECT id, amount, status FROM demo_order WHERE id = ?", "mysql", "3306"));

        DcSpan dubboServer = span("trace-demo", "dubbo-server", "dubbo-client", "service-b", "5457a0119281bb98",
                traceStart + 135_000_000L, 60_000_000L);
        dubboServer.type = "SPAN_KIND_SERVER";
        dubboServer.meta = "{\"rpc.system\":\"dubbo\",\"rpc.service\":\"com.databuff.demo.OrderService\","
                + "\"rpc.method\":\"findInventory\"}";
        spans.add(dubboServer);

        DcSpan dubboMysql = dbClient("trace-demo", "dubbo-mysql", "dubbo-server", "service-b", "5457a0119281bb98",
                traceStart + 150_000_000L, 30_000_000L, "mysql",
                "SELECT sku, available FROM demo_inventory WHERE sku = ?", "mysql", "3306");
        dubboMysql.error = 1;
        spans.add(dubboMysql);

        spans.add(dbClient("trace-demo", "redis-server-b", "http-server", "service-b", "5457a0119281bb98", traceStart + 95_000_000L,
                13_000_000L, "redis", "SET order:10001", "redis", "6379"));
        return spans;
    }

    private static DcSpan dbClient(
            String traceId,
            String spanId,
            String parentId,
            String service,
            String serviceId,
            long startOffset,
            long duration,
            String dbSystem,
            String statement,
            String host,
            String port) {
        DcSpan span = span(traceId, spanId, parentId, service, serviceId, startOffset, duration);
        span.type = "SPAN_KIND_CLIENT";
        if ("mysql".equals(dbSystem)) {
            span.meta = "{\"db.system\":\"mysql\",\"db.name\":\"demo_apm\",\"db.statement\":\"" + statement + "\","
                    + "\"server.address\":\"" + host + "\",\"server.port\":\"" + port + "\"}";
        } else {
            span.meta = "{\"db.system\":\"" + dbSystem + "\",\"db.statement\":\"" + statement + "\","
                    + "\"server.address\":\"" + host + "\",\"server.port\":\"" + port + "\"}";
        }
        return span;
    }

    private static DcSpan esClient(
            String traceId, String spanId, String parentId, String service, String serviceId, long startOffset, long duration) {
        DcSpan span = span(traceId, spanId, parentId, service, serviceId, startOffset, duration);
        span.type = "SPAN_KIND_CLIENT";
        span.resource = "orders/_search";
        span.name = "orders/_search";
        span.meta = "{\"db.system\":\"elasticsearch\",\"db.elasticsearch.index\":\"orders\","
                + "\"http.method\":\"GET\",\"url.full\":\"http://es:9200/orders/_search\","
                + "\"server.address\":\"es\",\"server.port\":\"9200\"}";
        return span;
    }

    private static DcSpan mqClient(
            String traceId, String spanId, String parentId, String service, String serviceId, long startOffset, long duration) {
        DcSpan span = span(traceId, spanId, parentId, service, serviceId, startOffset, duration);
        span.type = "SPAN_KIND_CLIENT";
        span.meta = "{\"messaging.system\":\"kafka\",\"messaging.destination.name\":\"order-events\","
                + "\"messaging.operation\":\"publish\",\"net.peer.name\":\"kafka\",\"server.port\":\"9092\"}";
        return span;
    }

    private static DcSpan httpClient(
            String traceId,
            String spanId,
            String parentId,
            String service,
            String serviceId,
            long startOffset,
            long duration,
            String host,
            String port,
            String url) {
        DcSpan span = span(traceId, spanId, parentId, service, serviceId, startOffset, duration);
        span.type = "SPAN_KIND_CLIENT";
        span.name = "HTTP GET " + host + " " + url.substring(url.indexOf('/', 8));
        span.resource = span.name;
        span.metaHttpMethod = "GET";
        span.metaHttpStatusCode = 200;
        span.meta = "{\"http.method\":\"GET\",\"http.status_code\":\"200\",\"url.full\":\"" + url + "\","
                + "\"server.address\":\"" + host + "\",\"server.port\":\"" + port + "\"}";
        return span;
    }

    private static DcSpan rpcClient(
            String traceId, String spanId, String parentId, String service, String serviceId, long startOffset, long duration) {
        DcSpan span = span(traceId, spanId, parentId, service, serviceId, startOffset, duration);
        span.type = "SPAN_KIND_CLIENT";
        span.name = "Dubbo DemoOrderService.findInventory";
        span.resource = span.name;
        span.meta = "{\"rpc.system\":\"dubbo\",\"rpc.service\":\"com.databuff.demo.OrderService\","
                + "\"rpc.method\":\"findInventory\",\"net.peer.name\":\"service-b\",\"net.peer.port\":\"20880\"}";
        return span;
    }

    private static DcSpan span(
            String traceId,
            String spanId,
            String parentId,
            String service,
            String serviceId,
            long startOffset,
            long duration) {
        DcSpan span = new DcSpan();
        span.trace_id = traceId;
        span.span_id = spanId;
        span.parent_id = parentId;
        span.service = service;
        span.serviceId = serviceId;
        span.serviceInstance = service + "-1";
        span.resource = "GET /";
        span.name = "GET /";
        span.hostName = "demo-host";
        span.error = 0;
        span.duration = duration;
        span.start = startOffset;
        span.end = startOffset + duration;
        return span;
    }
}
