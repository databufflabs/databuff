package com.databuff.apm.web.portal;

import com.databuff.apm.common.query.ApmQueryModels;
import com.databuff.apm.common.query.ApmQueryModels.ExceptionListPoint;
import com.databuff.apm.common.query.ApmQueryModels.ServiceFlowTreeRow;
import com.databuff.apm.common.query.ApmQueryModels.SpanDetail;
import com.databuff.apm.common.query.ApmQueryModels.SpanSummary;
import com.databuff.apm.common.query.TimeSeriesFillUtil;
import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.web.TestStorageSupport;
import com.databuff.apm.web.flow.ServiceFlowService;
import com.databuff.apm.web.trace.TraceQueryService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TracePortalServiceTest {

    @Test
    void delegatesServiceInstanceCounts() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        when(traceQuery.serviceInstanceCounts(any())).thenReturn(Map.of("demo-order", 2));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Integer> counts = service.serviceInstanceCounts(Map.of(
                "from", 0L,
                "to", 1000L));

        assertThat(counts.get("demo-order")).isEqualTo(2);
    }

    @Test
    void listUsesEntrySpanScopeForParentZero() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        when(traceQuery.spanList(any())).thenReturn(List.of());
        when(traceQuery.spanListCount(any())).thenReturn(0L);

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        service.list(Map.of(
                "parentId", "0",
                "serviceIds", List.of("9bf61532d56eb7b5"),
                "fromTime", "2026-06-05 22:40:00",
                "toTime", "2026-06-05 22:41:00",
                "offset", 0,
                "size", 50));

        org.mockito.ArgumentCaptor<TraceQueryService.SpanListRequest> captor =
                org.mockito.ArgumentCaptor.forClass(TraceQueryService.SpanListRequest.class);
        org.mockito.Mockito.verify(traceQuery).spanList(captor.capture());
        assertThat(captor.getValue().isParent()).isEqualTo(1);
        assertThat(captor.getValue().serviceIds()).containsExactly("9bf61532d56eb7b5");
    }

    @Test
    void listPushesErrorFilterToSpanListQuery() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        when(traceQuery.spanList(any())).thenReturn(List.of());
        when(traceQuery.spanListCount(any())).thenReturn(0L);

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        service.list(Map.of(
                "parentId", "0",
                "fromTime", "2026-06-20 07:02:00",
                "toTime", "2026-06-20 07:03:00",
                "error", 1,
                "offset", 0,
                "size", 50));

        org.mockito.ArgumentCaptor<TraceQueryService.SpanListRequest> listCaptor =
                org.mockito.ArgumentCaptor.forClass(TraceQueryService.SpanListRequest.class);
        org.mockito.Mockito.verify(traceQuery).spanList(listCaptor.capture());
        org.mockito.Mockito.verify(traceQuery).spanListCount(listCaptor.getValue());
        assertThat(listCaptor.getValue().error()).isEqualTo(1);
        assertThat(listCaptor.getValue().isParent()).isEqualTo(1);
    }

    @Test
    void listSkipsCountWhenIncludeTotalFalse() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        when(traceQuery.spanList(any())).thenReturn(List.of());

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Object> resp = service.list(Map.of(
                "parentId", "0",
                "fromTime", "2026-06-20 07:02:00",
                "toTime", "2026-06-20 07:03:00",
                "includeTotal", false,
                "offset", 0,
                "size", 50));

        org.mockito.Mockito.verify(traceQuery).spanList(any());
        org.mockito.Mockito.verify(traceQuery, never()).spanListCount(any());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertThat(data.get("total")).isEqualTo(0L);
    }

    @Test
    void buildsPortalSpanListEnvelope() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        when(traceQuery.spanListCount(any())).thenReturn(1L);
        when(traceQuery.spanList(any())).thenReturn(List.of(
                new SpanSummary(
                        "t1", "s1", "demo-order", null, "GET /orders",
                        "2026-06-01 12:00:00", 2_000_000L, 1,
                        "host-1", "GET /orders", "host-1", 500, "timeout")));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Object> resp = service.list(Map.of(
                "fromTime", "2026-06-01 11:00:00",
                "toTime", "2026-06-01 13:00:00",
                "offset", 0,
                "size", 20));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("trace_id")).isEqualTo("t1");
        assertThat(list.get(0).get("serviceId")).isEqualTo("464a0a08964a061e");
    }

    @Test
    void listReturnsFriendlyFailWhenStorageThrows() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        when(traceQuery.spanList(any())).thenThrow(new RuntimeException(
                "errCode = 2, detailMessage = fail to find path in version_graph. spec_version: 0-4532"));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Object> resp = service.list(Map.of(
                "parentId", "0",
                "fromTime", "2026-07-22 18:25:00",
                "toTime", "2026-07-22 18:30:00",
                "offset", 0,
                "size", 50));

        assertThat(resp.get("status")).isEqualTo(500);
        assertThat(String.valueOf(resp.get("message"))).contains("版本链损坏");
        assertThat(resp).doesNotContainKey("data");
    }

    @Test
    void spanListCombinesHttpMethodAndUrlWhenResourceIsMethodOnly() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        when(traceQuery.spanListCount(any())).thenReturn(1L);
        when(traceQuery.spanList(any())).thenReturn(List.of(
                new SpanSummary(
                        "t1", "s1", "service-b", null, "GET",
                        "2026-06-16 13:06:00", 2_000_000L, 0,
                        "service-b-pod", "GET", "service-b-pod", 200, null,
                        null, null, "/callB")));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Object> resp = service.list(Map.of(
                "parentId", "0",
                "fromTime", "2026-06-16 13:05:00",
                "toTime", "2026-06-16 13:06:00",
                "offset", 0,
                "size", 50));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("resource")).isEqualTo("GET /callB");
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) list.get(0).get("meta");
        assertThat(meta.get("http.url")).isEqualTo("/callB");
        assertThat(meta.get("http.method")).isEqualTo("GET");
    }

    @Test
    void resourceSpanListUsesAllSpansScopeAndResourceFilter() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        when(traceQuery.spanList(any())).thenReturn(List.of(
                new SpanSummary(
                        "t1", "s1", "demo-order", null, "GET /demo/checkout",
                        "2026-06-01 12:00:00", 2_000_000L, 0,
                        "host-1", "GET /demo/checkout", "host-1", 200, null,
                        null, null, "/demo/checkout"),
                new SpanSummary(
                        "t2", "s2", "demo-order", null, "GET /demo/health",
                        "2026-06-01 12:00:01", 1_000_000L, 0,
                        "host-1", "GET /demo/health", "host-1", 200, null,
                        null, null, "/demo/health")));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Object> resp = service.spanList(Map.of(
                "url", "/demo/checkout",
                "componentType", "service.http",
                "fromTime", "2026-06-01 11:00:00",
                "toTime", "2026-06-01 13:00:00",
                "offset", 0,
                "size", 20));

        org.mockito.ArgumentCaptor<TraceQueryService.SpanListRequest> captor =
                org.mockito.ArgumentCaptor.forClass(TraceQueryService.SpanListRequest.class);
        org.mockito.Mockito.verify(traceQuery).spanList(captor.capture());
        org.mockito.Mockito.verify(traceQuery, never()).spanListCount(any());
        assertThat(captor.getValue().isParent()).isNull();
        assertThat(captor.getValue().resource()).isEqualTo("/demo/checkout");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertThat(data).doesNotContainKey("total");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("resource")).isEqualTo("GET /demo/checkout");
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) list.get(0).get("meta");
        assertThat(meta.get("http.url")).isEqualTo("/demo/checkout");
    }

    @Test
    void resourceSpanListFillsDbMetaFromComponentType() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        when(traceQuery.spanList(any())).thenReturn(List.of(
                new SpanSummary(
                        "t-db", "db1", "[mysql]demo_apm", "dad537de7e10e098",
                        "SELECT demo_inventory",
                        "2026-08-09 23:03:45", 30_000_000L, 1,
                        "service-b-1",
                        "SELECT sku, available FROM demo_inventory WHERE sku = ?",
                        "demo-host-b",
                        null,
                        "InsufficientStockException",
                        "parent-1",
                        0,
                        null,
                        "service-b",
                        "9bf61532d56eb7b5",
                        "service-b-1",
                        "{\"db.system\":\"mysql\",\"db.name\":\"demo_apm\","
                                + "\"db.statement\":\"SELECT sku, available FROM demo_inventory WHERE sku = ?\"}",
                        "{\"db.response.returned_rows\":\"0\"}")));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Object> resp = service.spanList(Map.of(
                "resource", "SELECT sku, available FROM demo_inventory WHERE sku = ?",
                "componentType", "service.db",
                "serviceId", "dad537de7e10e098",
                "fromTime", "2026-08-09 22:00:00",
                "toTime", "2026-08-09 23:30:00",
                "offset", 0,
                "size", 20));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
        assertThat(list).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) list.get(0).get("meta");
        assertThat(meta.get("db.operation")).isEqualTo("SELECT");
        assertThat(meta.get("db.instance")).isEqualTo("demo_apm");
        assertThat(meta.get("db.type")).isEqualTo("mysql");
        assertThat(meta.get("db.returnRows")).isEqualTo("0");
        assertThat(list.get(0).get("srcService")).isEqualTo("service-b");
    }

    @Test
    void resourceSpanListFillsMqMetaFromComponentType() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        when(traceQuery.spanList(any())).thenReturn(List.of(
                new SpanSummary(
                        "t-mq", "mq1", "[kafka]orders", "kafkasvc",
                        "orders",
                        "2026-08-09 23:03:45", 12_000_000L, 0,
                        "consumer-1", "orders", "host-1",
                        null, null, null, null, null,
                        "service-a", "svc-a", "a-1",
                        "{\"messaging.system\":\"kafka\",\"messaging.destination.name\":\"orders\","
                                + "\"messaging.kafka.consumer.group\":\"order-group\","
                                + "\"messaging.kafka.partition\":\"3\",\"server.address\":\"kafka-1\"}",
                        "{\"record.e2e.duration.ns\":\"45000000\"}")));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Object> resp = service.spanList(Map.of(
                "resource", "orders",
                "componentType", "service.mq",
                "fromTime", "2026-08-09 22:00:00",
                "toTime", "2026-08-09 23:30:00",
                "offset", 0,
                "size", 20));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) ((Map<?, ?>) resp.get("data")).get("list");
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) list.get(0).get("meta");
        assertThat(list.get(0).get("type")).isEqualTo("kafka");
        assertThat(meta.get("mq.topic")).isEqualTo("orders");
        assertThat(meta.get("mq.group")).isEqualTo("order-group");
        assertThat(meta.get("partition")).isEqualTo("3");
        assertThat(meta.get("mq.broker")).isEqualTo("kafka-1");
        assertThat(meta.get("record.e2e_duration_ns")).isEqualTo("45000000");
    }

    @Test
    void resourceSpanListFillsRpcRedisConfigAndHttpMeta() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        when(traceQuery.spanList(any())).thenReturn(List.of(
                new SpanSummary(
                        "t-rpc", "rpc1", "service-b", "svc-b",
                        "Dubbo DemoOrderService.findInventory",
                        "2026-08-09 23:03:45", 20_000_000L, 0,
                        "b-1", "Dubbo DemoOrderService.findInventory", "host-b",
                        null, null, null, null, null,
                        "service-a", "svc-a", "a-1",
                        "{\"rpc.system\":\"dubbo\",\"rpc.service\":\"com.databuff.demo.OrderService\","
                                + "\"rpc.method\":\"findInventory\",\"thread.name\":\"DubboServerHandler-1\"}",
                        null)));

        TracePortalService rpcService = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Object> rpcResp = rpcService.spanList(Map.of(
                "resource", "Dubbo DemoOrderService.findInventory",
                "componentType", "service.rpc",
                "fromTime", "2026-08-09 22:00:00",
                "toTime", "2026-08-09 23:30:00",
                "offset", 0,
                "size", 20));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rpcList = (List<Map<String, Object>>) ((Map<?, ?>) rpcResp.get("data")).get("list");
        @SuppressWarnings("unchecked")
        Map<String, Object> rpcMeta = (Map<String, Object>) rpcList.get(0).get("meta");
        assertThat(rpcList.get(0).get("type")).isEqualTo("dubbo");
        assertThat(rpcMeta.get("rpc.system")).isEqualTo("dubbo");
        assertThat(rpcMeta.get("rpc.method")).isEqualTo("findInventory");
        assertThat(rpcMeta.get("thread.name")).isEqualTo("DubboServerHandler-1");

        when(traceQuery.spanList(any())).thenReturn(List.of(
                new SpanSummary(
                        "t-redis", "r1", "[redis]cache", "redis-id",
                        "GET cart:10001",
                        "2026-08-09 23:03:45", 1_000_000L, 0,
                        "c-1", "GET cart:10001", "host-c",
                        null, null, null, null, null,
                        "service-a", "svc-a", "a-1",
                        "{\"db.system\":\"redis\",\"db.statement\":\"GET cart:10001\"}",
                        null)));
        Map<String, Object> redisResp = rpcService.spanList(Map.of(
                "resource", "GET cart:10001",
                "componentType", "service.redis",
                "fromTime", "2026-08-09 22:00:00",
                "toTime", "2026-08-09 23:30:00",
                "offset", 0,
                "size", 20));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> redisList =
                (List<Map<String, Object>>) ((Map<?, ?>) redisResp.get("data")).get("list");
        @SuppressWarnings("unchecked")
        Map<String, Object> redisMeta = (Map<String, Object>) redisList.get(0).get("meta");
        assertThat(redisList.get(0).get("type")).isEqualTo("redis");
        assertThat(redisMeta.get("db.statement")).isEqualTo("GET cart:10001");

        when(traceQuery.spanList(any())).thenReturn(List.of(
                new SpanSummary(
                        "t-cfg", "cfg1", "[nacos]config", "nacos-id",
                        "GET /nacos/v1/cs/configs",
                        "2026-08-09 23:03:45", 3_000_000L, 0,
                        "cfg-1", "GET /nacos/v1/cs/configs", "host-cfg",
                        null, null, null, null, null,
                        "service-a", "svc-a", "a-1",
                        "{\"config.type\":\"nacos\",\"config.operation\":\"GET\",\"db.system\":\"nacos\"}",
                        null)));
        Map<String, Object> cfgResp = rpcService.spanList(Map.of(
                "resource", "GET /nacos/v1/cs/configs",
                "componentType", "service.config",
                "fromTime", "2026-08-09 22:00:00",
                "toTime", "2026-08-09 23:30:00",
                "offset", 0,
                "size", 20));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cfgList =
                (List<Map<String, Object>>) ((Map<?, ?>) cfgResp.get("data")).get("list");
        @SuppressWarnings("unchecked")
        Map<String, Object> cfgMeta = (Map<String, Object>) cfgList.get(0).get("meta");
        assertThat(cfgList.get(0).get("type")).isEqualTo("nacos");
        assertThat(cfgMeta.get("config.type")).isEqualTo("nacos");
        assertThat(cfgMeta.get("config.operation")).isEqualTo("GET");

        when(traceQuery.spanList(any())).thenReturn(List.of(
                new SpanSummary(
                        "t-http", "h1", "service-b", "svc-b", "GET",
                        "2026-08-09 23:03:45", 5_000_000L, 0,
                        "b-1", "GET", "host-b",
                        200, null, null, null,
                        "http://service-b:8080/api/orders/10001",
                        "service-a", "svc-a", "a-1",
                        "{\"http.method\":\"GET\",\"http.url\":\"http://service-b:8080/api/orders/10001\"}",
                        null)));
        Map<String, Object> httpResp = rpcService.spanList(Map.of(
                "url", "/api/orders/10001",
                "componentType", "service.http",
                "fromTime", "2026-08-09 22:00:00",
                "toTime", "2026-08-09 23:30:00",
                "offset", 0,
                "size", 20));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> httpList =
                (List<Map<String, Object>>) ((Map<?, ?>) httpResp.get("data")).get("list");
        @SuppressWarnings("unchecked")
        Map<String, Object> httpMeta = (Map<String, Object>) httpList.get(0).get("meta");
        assertThat(httpMeta.get("http.url")).isEqualTo("/api/orders/10001");
        assertThat(httpMeta.get("http.status_code")).isEqualTo(200);
        assertThat(httpMeta.get("http.method")).isEqualTo("GET");
    }

    @Test
    void resourceSpanListMatchesAbsoluteHttpUrlAgainstPortalUrl() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        when(traceQuery.spanList(any())).thenReturn(List.of(
                new SpanSummary(
                        "t1", "s1", "frontend", "046b2a50b8c3c54c",
                        "GET /api/recommendations?productIds=HQTGWGPNH4",
                        "2026-07-21 15:59:00", 2_000_000L, 0,
                        "frontend-pod", "GET",
                        "frontend-pod", 200, null,
                        null, null,
                        "http://frontend-proxy:8080/api/recommendations?productIds=HQTGWGPNH4"),
                new SpanSummary(
                        "t2", "s2", "frontend", "046b2a50b8c3c54c",
                        "GET /api/health",
                        "2026-07-21 15:59:01", 1_000_000L, 0,
                        "frontend-pod", "GET /api/health",
                        "frontend-pod", 200, null,
                        null, null,
                        "http://frontend-proxy:8080/api/health")));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Object> resp = service.spanList(Map.of(
                "url", "/api/recommendations?productIds=HQTGWGPNH4",
                "componentType", "service.http",
                "fromTime", "2026-07-21 15:00:00",
                "toTime", "2026-07-21 16:00:00",
                "offset", 0,
                "size", 20));

        org.mockito.Mockito.verify(traceQuery, never()).spanListCount(any());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertThat(data).doesNotContainKey("total");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
        assertThat(list).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) list.get(0).get("meta");
        assertThat(meta.get("http.url"))
                .isEqualTo("/api/recommendations?productIds=HQTGWGPNH4");
    }

    @Test
    void errorResourceSpanListFiltersErrors() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.querySpanSummaries(anyString())).thenReturn(List.of(
                new SpanSummary(
                        "t1", "s1", "demo-order", null, "/demo/checkout",
                        "2026-06-01 12:00:00", 2_000_000L, 1,
                        "host-1", "/demo/checkout", "host-1", 500, "timeout"),
                new SpanSummary(
                        "t2", "s2", "demo-order", null, "/demo/checkout",
                        "2026-06-01 12:00:01", 1_000_000L, 0,
                        "host-1", "/demo/checkout", "host-1", 200, null)));

        TracePortalService service = new TracePortalService(
                mock(TraceQueryService.class), mock(ServiceFlowService.class), reader, TestStorageSupport.storage());
        Map<String, Object> resp = service.errorSpanList(Map.of(
                "resource", "/demo/checkout",
                "fromTime", "2026-06-01 11:00:00",
                "toTime", "2026-06-01 13:00:00",
                "offset", 0,
                "size", 20));

        verify(reader, never()).queryCallSpanCount(anyString());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertThat(data).doesNotContainKey("total");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("trace_id")).isEqualTo("t1");
    }

    @Test
    void filtersSpanListByResourceAndError() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        when(traceQuery.spanListCount(any())).thenReturn(1L);
        when(traceQuery.spanList(any())).thenReturn(List.of(
                new SpanSummary(
                        "t1", "s1", "demo-order", null, "GET /orders",
                        "2026-06-01 12:00:00", 2_000_000L, 1,
                        "host-1", "GET /orders", "host-1", 500, "timeout"),
                new SpanSummary(
                        "t2", "s2", "demo-order", null, "GET /health",
                        "2026-06-01 12:00:01", 1_000_000L, 0,
                        "host-1", "GET /health", "host-1", 200, null)));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Object> resp = service.list(Map.of(
                "fromTime", "2026-06-01 11:00:00",
                "toTime", "2026-06-01 13:00:00",
                "resource", "GET /orders",
                "error", 1,
                "offset", 0,
                "size", 20));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("trace_id")).isEqualTo("t1");
        assertThat(list.get(0).get("errorType")).isEqualTo("timeout");
    }

    @Test
    void queryParamsUsesMetricFacetsWithoutTraceId() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryStringMap(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            assertThat(sql).contains("metric_service_trace");
            assertThat(sql).contains("map_key");
            return Map.of("demo-order", "demo-order-id");
        });
        when(reader.queryIntMap(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            assertThat(sql).contains("`resource`");
            return Map.of("GET /orders", 12);
        });
        when(reader.queryRows(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(
                Map.of("min_duration", 1_000_000L, "max_duration", 3_000_000L)));

        TraceQueryService traceQuery = mock(TraceQueryService.class);
        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), reader, TestStorageSupport.storage());
        Map<String, Object> data = service.queryParams(Map.of(
                "serviceId", "demo-order",
                "componentType", "service.trace",
                "fromTime", "2026-06-01 11:00:00",
                "toTime", "2026-06-01 13:00:00"));

        verify(traceQuery, never()).spanList(any());
        verify(traceQuery, never()).traceDetail(any());
        assertThat(data.get("status")).isEqualTo(Map.of("0", 1, "1", 1));
        assertThat(data.get("service")).isEqualTo(Map.of("demo-order", "demo-order-id"));
        assertThat(data.get("resource")).isEqualTo(Map.of("GET /orders", 12));
        assertThat(data.get("duration")).isEqualTo(Map.of("min", 1_000_000L, "max", 3_000_000L));
    }

    @Test
    void queryParamsFallsBackToTraceDetailWhenTraceIdPresent() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        when(traceQuery.traceDetail(any())).thenReturn(List.of(
                new SpanDetail(
                        "t1", "s1", "0", "demo-order", "demo-order", "GET /orders",
                        "2026-06-01 12:00:00", 1_000_000_000L, 2_000_000L, 1,
                        "host-1", "inst-1", "GET /orders", "http", 1, 0, "{}", "{}",
                        500, "GET", "http://demo/orders", "timeout")));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Object> data = service.queryParams(Map.of(
                "traceId", "t1",
                "queryParams", List.of("status", "service", "resource", "errorType")));

        verify(traceQuery).traceDetail(any());
        @SuppressWarnings("unchecked")
        Map<String, Integer> status = (Map<String, Integer>) data.get("status");
        assertThat(status.get("1")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, String> services = (Map<String, String>) data.get("service");
        assertThat(services).containsKey("demo-order");
        @SuppressWarnings("unchecked")
        Map<String, Integer> resources = (Map<String, Integer>) data.get("resource");
        assertThat(resources).containsKey("GET /orders");
        @SuppressWarnings("unchecked")
        Map<String, Integer> errorTypes = (Map<String, Integer>) data.get("errorType");
        assertThat(errorTypes).containsKey("timeout");
    }

    @Test
    void cntGraphStatsUsesTraceMetricTrendBuckets() throws Exception {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        when(traceQuery.spanList(any())).thenReturn(List.of(
                new SpanSummary(
                        "t1", "s1", "demo-order", null, "GET /orders",
                        "2026-06-06 07:19:00", 2_000_000L, 0,
                        "host-1", "GET /orders", "host-1", 200, null)));

        ApmReadRepository reader = mock(ApmReadRepository.class);
        long bucket = TimeSeriesFillUtil.alignBucketEpochSec(
                PortalTimeParser.rangeFrom(Map.of("fromTime", "2026-06-06 06:20:00"), 0), 60);
        when(reader.queryComponentTrendBuckets(anyString())).thenAnswer(invocation -> {
            assertThat(invocation.getArgument(0, String.class)).contains("metric_service_trace");
            return List.of(new ApmQueryModels.ComponentTrendBucketPoint(
                    bucket, "demo-order", 12, 0, 24_000_000L, 3_000_000L, 1_000_000L, 0, 0));
        });

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), reader, TestStorageSupport.storage());
        Map<String, Object> graphs = service.cntGraphStats(Map.of(
                "fromTime", "2026-06-06 06:20:00",
                "toTime", "2026-06-06 07:20:00",
                "interval", 60,
                "parentId", "0"));

        org.mockito.Mockito.verify(traceQuery, org.mockito.Mockito.never()).spanList(any());
        org.mockito.Mockito.verify(reader).queryComponentTrendBuckets(anyString());
        @SuppressWarnings("unchecked")
        Map<String, Number> callCnts = (Map<String, Number>) graphs.get("callCnts");
        assertThat(callCnts.get(String.valueOf(bucket * 1000L))).isEqualTo(12L);
    }

    @Test
    void errorCntGraphStatsUsesTraceMetricErrorBuckets() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        long bucket = TimeSeriesFillUtil.alignBucketEpochSec(
                PortalTimeParser.rangeFrom(Map.of("fromTime", "2026-06-06 07:34:00"), 0), 60);
        when(reader.queryComponentTrendBuckets(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            assertThat(sql).contains("metric_service_trace");
            assertThat(sql).contains("`errorType` = 'error'");
            return List.of(new ApmQueryModels.ComponentTrendBucketPoint(
                    bucket, "demo-order", 0, 3, 0, 0, 0, 0, 0));
        });

        TracePortalService service = new TracePortalService(
                mock(TraceQueryService.class), mock(ServiceFlowService.class), reader, TestStorageSupport.storage());
        Map<String, Object> graphs = service.errorCntGraphStats(Map.of(
                "fromTime", "2026-06-06 07:34:00",
                "toTime", "2026-06-06 07:49:00",
                "interval", 60,
                "parentId", "0"));

        org.mockito.Mockito.verify(reader).queryComponentTrendBuckets(anyString());
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Long>> errorCnts = (Map<String, Map<String, Long>>) graphs.get("errorCnts");
        assertThat(errorCnts.get(String.valueOf(bucket * 1000L))).containsEntry("demo-order", 3L);
    }

    @Test
    void graphStatsUsesTraceMetricTrendBuckets() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        long bucket = TimeSeriesFillUtil.alignBucketEpochSec(
                PortalTimeParser.rangeFrom(Map.of("fromTime", "2026-06-01 11:00:00"), 0), 60);
        when(reader.queryComponentTrendBuckets(anyString())).thenAnswer(invocation -> {
            assertThat(invocation.getArgument(0, String.class)).contains("metric_service_trace");
            return List.of(new ApmQueryModels.ComponentTrendBucketPoint(
                    bucket, "demo-order", 10, 1, 12_000_000_000L, 3_000_000L, 1_000_000L, 0, 0));
        });

        TracePortalService service = new TracePortalService(
                mock(TraceQueryService.class), mock(ServiceFlowService.class), reader, TestStorageSupport.storage());
        Map<String, Object> graphs = service.graphStats(Map.of(
                "fromTime", "2026-06-01 11:00:00",
                "toTime", "2026-06-01 13:00:00",
                "serviceId", "demo-order"));

        @SuppressWarnings("unchecked")
        Map<String, Number> latencies =
                (Map<String, Number>) graphs.get("avgLatencys");
        assertThat(latencies.get(String.valueOf(bucket * 1000L))).isEqualTo(1_200_000_000.0);
    }

    @Test
    void metricGraphStatsFillsFullTimeRange() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        long from = PortalTimeParser.rangeFrom(Map.of("fromTime", "2026-06-06 06:20:00"), 0);
        long to = PortalTimeParser.rangeTo(Map.of("toTime", "2026-06-06 07:20:00"), 0);
        long earlyBucket = TimeSeriesFillUtil.alignBucketEpochSec(from, 60);
        long lateBucket = TimeSeriesFillUtil.alignBucketEpochSec(to - 1, 60);
        when(reader.queryComponentTrendBuckets(anyString())).thenReturn(List.of(
                new ApmQueryModels.ComponentTrendBucketPoint(
                        earlyBucket, "demo-order", 8, 0, 8_000_000L, 2_000_000L, 1_000_000L, 0, 0),
                new ApmQueryModels.ComponentTrendBucketPoint(
                        lateBucket, "demo-order", 5, 0, 5_000_000L, 2_000_000L, 1_000_000L, 0, 0)));

        TracePortalService service = new TracePortalService(
                mock(TraceQueryService.class), mock(ServiceFlowService.class), reader, TestStorageSupport.storage());
        Map<String, Object> graphs = service.cntGraphStats(Map.of(
                "fromTime", "2026-06-06 06:20:00",
                "toTime", "2026-06-06 07:20:00",
                "interval", 60,
                "parentId", "0"));

        @SuppressWarnings("unchecked")
        Map<String, Number> callCnts = (Map<String, Number>) graphs.get("callCnts");
        assertThat(callCnts.get(String.valueOf(earlyBucket * 1000L))).isEqualTo(8L);
        assertThat(callCnts.get(String.valueOf(lateBucket * 1000L))).isEqualTo(5L);
        assertThat(callCnts).hasSizeGreaterThan(30);
    }

    @Test
    void cntGraphStatsWithTraceIdAggregatesFromSpans() throws Exception {
        long startMs = PortalTimeParser.rangeFrom(Map.of("fromTime", "2026-06-06 06:20:00"), 0) + 60_000L;
        long startNs = startMs * 1_000_000L;
        long bucket = TimeSeriesFillUtil.alignBucketEpochSec(startMs, 60);

        TraceQueryService traceQuery = mock(TraceQueryService.class);
        when(traceQuery.traceDetail(any())).thenReturn(List.of(
                new SpanDetail(
                        "t1", "s1", "0", "demo-order", "demo-order", "GET /orders",
                        "2026-06-06 06:21:00", startNs, 2_500_000L, 0, "host-1"),
                new SpanDetail(
                        "t1", "s2", "s1", "demo-order", "demo-order", "SELECT orders",
                        "2026-06-06 06:21:00", startNs + 1_000_000L, 500_000L, 0, "host-1")));

        ApmReadRepository reader = mock(ApmReadRepository.class);
        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), reader, TestStorageSupport.storage());
        Map<String, Object> graphs = service.cntGraphStats(Map.of(
                "traceId", "t1",
                "fromTime", "2026-06-06 06:20:00",
                "toTime", "2026-06-06 07:20:00",
                "interval", 60,
                "parentId", "0"));

        verify(reader, never()).queryComponentTrendBuckets(anyString());
        verify(traceQuery).traceDetail(any());
        @SuppressWarnings("unchecked")
        Map<String, Number> callCnts = (Map<String, Number>) graphs.get("callCnts");
        assertThat(callCnts.get(String.valueOf(bucket * 1000L))).isEqualTo(1L);
    }

    @Test
    void errorAndLatencyGraphStatsWithTraceIdAggregateFromSpans() throws Exception {
        long startMs = PortalTimeParser.rangeFrom(Map.of("fromTime", "2026-06-06 07:34:00"), 0) + 30_000L;
        long startNs = startMs * 1_000_000L;
        long bucket = TimeSeriesFillUtil.alignBucketEpochSec(startMs, 60);

        TraceQueryService traceQuery = mock(TraceQueryService.class);
        when(traceQuery.traceDetail(any())).thenReturn(List.of(
                new SpanDetail(
                        "t-err", "s1", "0", "demo-order", "demo-order", "GET /orders",
                        "2026-06-06 07:34:30", startNs, 3_000_000L, 1, "host-1")));

        ApmReadRepository reader = mock(ApmReadRepository.class);
        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), reader, TestStorageSupport.storage());
        Map<String, Object> body = Map.of(
                "traceId", "t-err",
                "fromTime", "2026-06-06 07:34:00",
                "toTime", "2026-06-06 07:49:00",
                "interval", 60,
                "parentId", "0");

        Map<String, Object> errors = service.errorCntGraphStats(body);
        Map<String, Object> latency = service.graphStats(body);

        verify(reader, never()).queryComponentTrendBuckets(anyString());
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Long>> errorCnts = (Map<String, Map<String, Long>>) errors.get("errorCnts");
        assertThat(errorCnts.get(String.valueOf(bucket * 1000L))).containsEntry("demo-order", 1L);
        @SuppressWarnings("unchecked")
        Map<String, Number> avgLatencys = (Map<String, Number>) latency.get("avgLatencys");
        assertThat(avgLatencys.get(String.valueOf(bucket * 1000L))).isEqualTo(3_000_000.0);
    }

    @Test
    void buildsTraceDetailSpans() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        long rootStartNs = 1_000_000_000_000_000_000L;
        when(traceQuery.traceDetail(any())).thenReturn(List.of(
                new SpanDetail("t1", "s1", "0", "demo-order", null, "GET /orders",
                        "2026-06-01 12:00:00", rootStartNs, 2_000_000L, 0, "host-1"),
                new SpanDetail("t1", "s2", "s1", "demo-order", null, "SELECT orders",
                        "2026-06-01 12:00:00", rootStartNs + 50_000_000L, 1_000_000L, 0, "host-1")));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Object> resp = service.traceSpans(Map.of("traceId", "t1", "size", 100));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> spans = (List<Map<String, Object>>) resp.get("data");
        assertThat(spans).hasSize(2);
        assertThat(spans.get(0).get("parent_id")).isEqualTo("0");
        assertThat(spans.get(0).get("relativeTime")).isEqualTo(0L);
        assertThat(spans.get(1).get("relativeTime")).isEqualTo(50_000_000L);
    }

    @Test
    void traceDetailSpansPreserveDirectionAndHttpUrlResource() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        long rootStartNs = 1_000_000_000_000_000_000L;
        when(traceQuery.traceDetail(any())).thenReturn(List.of(
                new SpanDetail(
                        "t1", "s1", "0", "demo-order", null, "GET", "2026-06-01 12:00:00",
                        rootStartNs, 2_000_000L, 0, "host-1", "inst-1", "GET", "web",
                        1, 0, "{}", null, 200, "GET", "http://example.test/orders?id=1", null),
                new SpanDetail(
                        "t1", "s2", "s1", "demo-order", null, "POST", "2026-06-01 12:00:00",
                        rootStartNs + 50_000_000L, 1_000_000L, 0, "host-1", "inst-1", "POST", "web",
                        0, 1, "{}", null, 200, "POST", "/pay", null)));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Object> resp = service.traceSpans(Map.of("traceId", "t1", "size", 100));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> spans = (List<Map<String, Object>>) resp.get("data");
        assertThat(spans.get(0).get("resource")).isEqualTo("GET /orders?id=1");
        assertThat(spans.get(0).get("service_type")).isEqualTo("web");
        assertThat(spans.get(0).get("type")).isEqualTo("web");
        assertThat(spans.get(0).get("isIn")).isEqualTo(1);
        assertThat(spans.get(0).get("isOut")).isEqualTo(0);
        assertThat(spans.get(1).get("resource")).isEqualTo("POST /pay");
        assertThat(spans.get(1).get("isIn")).isEqualTo(0);
        assertThat(spans.get(1).get("isOut")).isEqualTo(1);
    }

    @Test
    void traceDetailSpansMapSpanKindTypeToPortalWebDisplay() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        long rootStartNs = 1_000_000_000_000_000_000L;
        when(traceQuery.traceDetail(any())).thenReturn(List.of(
                new SpanDetail(
                        "t1", "s1", "0", "service-a", null, "GET /demo/checkout", "2026-06-01 12:00:00",
                        rootStartNs, 2_000_000L, 0, "host-1", "inst-1", "GET /demo/checkout", "SPAN_KIND_SERVER",
                        1, 0, "{}", null, 200, "GET", "/demo/checkout", null)));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Object> resp = service.traceSpans(Map.of("traceId", "t1", "size", 100));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> spans = (List<Map<String, Object>>) resp.get("data");
        assertThat(spans.get(0).get("service_type")).isEqualTo("web");
        assertThat(spans.get(0).get("type")).isEqualTo("web");
    }

    @Test
    void traceDetailEntrySpanMapsClientPeerFromMeta() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        long rootStartNs = 1_000_000_000_000_000_000L;
        when(traceQuery.traceDetail(any())).thenReturn(List.of(
                new SpanDetail(
                        "t1", "s1", "0", "flagd", null, "EventStream", "2026-06-01 12:00:00",
                        rootStartNs, 2_000_000L, 0, "flagd-host", "flagd-instance-1",
                        "flagd.evaluation.v1.Service/EventStream", "SPAN_KIND_SERVER",
                        1, 0,
                        "{\"client.service\":\"fraud-detection\",\"client.ip\":\"fraud-inst-1\",\"rpc.system\":\"grpc\"}",
                        null, null, null, null, null)));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Object> resp = service.traceSpans(Map.of("traceId", "t1", "size", 100));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> spans = (List<Map<String, Object>>) resp.get("data");
        assertThat(spans.get(0).get("clientService")).isEqualTo("fraud-detection");
        assertThat(spans.get(0).get("clientServiceInstance")).isEqualTo("fraud-inst-1");
    }

    @Test
    void traceDetailSpansResolveComponentServiceTypes() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        long rootStartNs = 1_000_000_000_000_000_000L;
        when(traceQuery.traceDetail(any())).thenReturn(List.of(
                new SpanDetail(
                        "t1", "s1", "0", "service-a", null, "GET /demo/checkout", "2026-06-01 12:00:00",
                        rootStartNs, 2_000_000L, 0, "host-1", "inst-1", "GET /demo/checkout", "SPAN_KIND_SERVER",
                        1, 0, "{}", null, 200, "GET", "/demo/checkout", null),
                new SpanDetail(
                        "t1", "s2", "s1", "service-a", null, "SELECT demo_order", "2026-06-01 12:00:00",
                        rootStartNs + 10_000_000L, 500_000L, 0, "host-1", "inst-1", "SELECT demo_order", "SPAN_KIND_CLIENT",
                        0, 1, "{\"db.system\":\"mysql\",\"db.operation\":\"select\"}", null, null, null, null, null),
                new SpanDetail(
                        "t1", "s3", "s1", "service-a", null, "SET order:10001", "2026-06-01 12:00:00",
                        rootStartNs + 20_000_000L, 300_000L, 0, "host-1", "inst-1", "SET order:10001", "SPAN_KIND_CLIENT",
                        0, 1, "{\"db.system\":\"redis\",\"db.statement\":\"SET order:10001\"}", null, null, null, null, null)));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Object> resp = service.traceSpans(Map.of("traceId", "t1", "size", 100));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> spans = (List<Map<String, Object>>) resp.get("data");
        assertThat(spans.get(0).get("service_type")).isEqualTo("web");
        assertThat(spans.get(1).get("service_type")).isEqualTo("db");
        assertThat(spans.get(1).get("type")).isEqualTo("mysql");
        assertThat(spans.get(2).get("service_type")).isEqualTo("cache");
        assertThat(spans.get(2).get("type")).isEqualTo("redis");
    }

    @Test
    void preservesSubMillisecondTraceSpanDuration() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        long rootStartNs = 1_000_000_000_000_000_000L;
        when(traceQuery.traceDetail(any())).thenReturn(List.of(
                new SpanDetail("t1", "s1", "0", "demo-order", null, "GET /orders",
                        "2026-06-01 12:00:00", rootStartNs, 4_000_000L, 0, "host-1"),
                new SpanDetail("t1", "s2", "s1", "demo-order", null, "SELECT * FROM orders",
                        "2026-06-01 12:00:00", rootStartNs + 1_000_000L, 500_000L, 0, "host-1")));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Object> resp = service.traceSpans(Map.of("traceId", "t1", "size", 100));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> spans = (List<Map<String, Object>>) resp.get("data");
        assertThat(spans.get(1).get("duration")).isEqualTo(500_000L);
        assertThat(spans.get(1).get("exectime")).isEqualTo(500_000L);
    }

    @Test
    void serviceFlowBuildsTreeFromTraceId() {
        TraceQueryService traceQuery = mock(TraceQueryService.class);
        long rootStartNs = 1_000_000_000_000_000_000L;
        when(traceQuery.traceDetail(any())).thenReturn(List.of(
                new SpanDetail("t1", "s1", "0", "gateway", "gw-id", "GET /",
                        "2026-06-01 12:00:00", rootStartNs, 100_000_000L, 0, "host-gw"),
                new SpanDetail("t1", "s2", "s1", "gateway", "gw-id", "route",
                        "2026-06-01 12:00:00", rootStartNs + 10_000_000L, 20_000_000L, 0, "host-gw"),
                new SpanDetail("t1", "s3", "s2", "checkout", "co-id", "POST /pay",
                        "2026-06-01 12:00:00", rootStartNs + 20_000_000L, 50_000_000L, 0, "host-co")));

        TracePortalService service = new TracePortalService(
                traceQuery, mock(ServiceFlowService.class), mock(ApmReadRepository.class), TestStorageSupport.storage());
        Map<String, Object> resp = service.serviceFlow(Map.of("traceId", "t1"));

        assertThat(resp.get("service")).isEqualTo("gateway");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) resp.get("children");
        assertThat(children).hasSize(1);
        assertThat(children.get(0).get("service")).isEqualTo("checkout");
    }

    @Test
    void pairsClientAndServerCallSpans() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryMetaServices(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("demo-order")) {
                return List.of(new ApmQueryModels.MetaServicePoint(
                        "demo-order", "demo-order", null, null, null, null, null, null, null, null, null, null, false, null, null, null, null));
            }
            return List.of(new ApmQueryModels.MetaServicePoint(
                    "demo-pay", "demo-pay", null, null, null, null, null, null, null, null, null, null, false, null, null, null, null));
        });
        when(reader.queryCallSpanCount(anyString())).thenReturn(1L);
        ApmQueryModels.CallSpanRow serverSpan =
                new ApmQueryModels.CallSpanRow(
                        "t1", "s2", "s1", 100L, 110L, "GET /orders", 900_000L, 0, 0,
                        "demo-order", "464a0a08964a061e", "order-1", "demo-pay", "5531560ada6ec064", "pay-1",
                        "demo-order", "464a0a08964a061e", "order-1", 1, 0,
                        "GET /orders", "{}", null, 200, "GET", "/orders", null);
        ApmQueryModels.CallSpanRow clientSpan =
                new ApmQueryModels.CallSpanRow(
                        "t1", "s1", "0", 100L, 110L, "GET /orders", 1_000_000L, 0, 0,
                        "demo-pay", "5531560ada6ec064", "pay-1", "", "", "",
                        "demo-order", "464a0a08964a061e", "order-1", 0, 1,
                        "GET /orders", "{}", null, 200, "GET", "/orders", null);
        when(reader.queryCallSpans(anyString()))
                .thenReturn(List.of(clientSpan))
                .thenReturn(List.of(serverSpan));

        TracePortalService service = new TracePortalService(
                mock(TraceQueryService.class), mock(ServiceFlowService.class), reader, TestStorageSupport.storage());
        Map<String, Object> resp = service.callSpans(Map.of(
                "serviceId", "demo-order",
                "srcServiceId", "demo-pay",
                "resource", "GET /orders",
                "componentType", "service.http",
                "fromTime", "2026-06-01 11:00:00",
                "toTime", "2026-06-01 13:00:00",
                "offset", 0,
                "size", 20));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("data");
        assertThat(resp.get("total")).isEqualTo(1L);
        assertThat(rows).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> client = (Map<String, Object>) rows.get(0).get("client");
        @SuppressWarnings("unchecked")
        Map<String, Object> server = (Map<String, Object>) rows.get(0).get("server");
        assertThat(client.get("span_id")).isEqualTo("s1");
        assertThat(server.get("span_id")).isEqualTo("s2");
        assertThat(rows.get(0).get("httpMethod")).isEqualTo("GET");
    }

    @Test
    void skipsCallSpanCountWhenTotalIsNotRequested() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryMetaServices(anyString())).thenReturn(List.of(new ApmQueryModels.MetaServicePoint(
                "demo-order", "demo-order", null, null, null, null, null, null, null, null, null, null,
                false, null, null, null, null)));
        ApmQueryModels.CallSpanRow clientSpan = new ApmQueryModels.CallSpanRow(
                "t1", "s1", "0", 100L, 110L, "GET /orders", 1_000_000L, 0, 0,
                "demo-pay", "pay-id", "pay-1", "", "", "",
                "demo-order", "demo-order", "order-1", 0, 1,
                "GET /orders", "{}", null, 200, "GET", "/orders", null);
        when(reader.queryCallSpans(anyString()))
                .thenReturn(List.of(clientSpan))
                .thenReturn(List.of());

        TracePortalService service = new TracePortalService(
                mock(TraceQueryService.class), mock(ServiceFlowService.class), reader, TestStorageSupport.storage());
        Map<String, Object> response = service.callSpans(Map.of(
                "serviceId", "demo-order",
                "componentType", "service.http",
                "fromTime", "2026-06-01 11:00:00",
                "toTime", "2026-06-01 13:00:00",
                "offset", 0,
                "size", 10,
                "includeTotal", false));

        assertThat(response.get("total")).isEqualTo(1L);
        verify(reader, never()).queryCallSpanCount(anyString());
    }

    @Test
    void queriesHttpCallSpansByPathWhenSpanResourceUsesRouteTemplate() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryMetaServices(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("5457a0119281bb98")) {
                return List.of(new ApmQueryModels.MetaServicePoint(
                        "5457a0119281bb98", "service-b", null, null, "web", null, null, null, null, null, null, null,
                        false, null, null, null, null));
            }
            return List.of(new ApmQueryModels.MetaServicePoint(
                    "9bf61532d56eb7b5", "service-a", null, null, "web", null, null, null, null, null, null, null,
                    false, null, null, null, null));
        });
        when(reader.queryCallSpanCount(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            assertThat(sql).contains("`resource` = 'GET /api/orders/10001'");
            assertThat(sql).contains("`meta.http.url` = '/api/orders/10001'");
            assertThat(sql).contains("`meta.http.method` = 'GET'");
            assertThat(sql).doesNotContain("LIKE '%/api/orders/10001%'");
            assertThat(sql).doesNotContain("COALESCE(`meta.http.url`");
            assertThat(sql).containsAnyOf("9bf61532d56eb7b5", "service-a");
            assertThat(sql).containsAnyOf("5457a0119281bb98", "service-b");
            return 1L;
        });
        ApmQueryModels.CallSpanRow clientSpan =
                new ApmQueryModels.CallSpanRow(
                        "trace-8", "http-client", "root-a", 100L, 110L, "GET /api/orders/{orderId}", 1_000_000L, 0, 0,
                        "service-a", "9bf61532d56eb7b5", "service-a-1", "service-a", "9bf61532d56eb7b5", "service-a-1",
                        "service-b", "5457a0119281bb98", "service-b-1", 0, 1,
                        "GET /api/orders/{orderId}", "{}", null, 200, "GET", "/api/orders/10001", null);
        when(reader.queryCallSpans(anyString())).thenReturn(List.of(clientSpan));

        TracePortalService service = new TracePortalService(
                mock(TraceQueryService.class), mock(ServiceFlowService.class), reader, TestStorageSupport.storage());
        Map<String, Object> resp = service.callSpans(Map.of(
                "serviceId", "5457a0119281bb98",
                "srcServiceId", "9bf61532d56eb7b5",
                "resource", "/api/orders/10001",
                "httpMethod", "GET",
                "componentType", "service.http",
                "fromTime", "2026-06-05 21:45:00",
                "toTime", "2026-06-05 21:46:00",
                "offset", 0,
                "size", 50));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("data");
        assertThat(resp.get("total")).isEqualTo(1L);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("url")).isEqualTo("/api/orders/10001");
    }

    @Test
    void queriesDbCallSpansByDstServiceAndSqlStatement() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryMetaServices(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("dad537de7e10e098")) {
                return List.of(new ApmQueryModels.MetaServicePoint(
                        "dad537de7e10e098", "mysql", null, null, "db", null, null, null, null, null, null, null,
                        true, null, null, null, null));
            }
            return List.of(new ApmQueryModels.MetaServicePoint(
                    "9bf61532d56eb7b5", "service-a", null, null, "web", null, null, null, null, null, null, null,
                    false, null, null, null, null));
        });
        when(reader.queryCallSpanCount(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            assertThat(sql).contains("`dstServiceId` = 'dad537de7e10e098'");
            assertThat(sql).contains("`srcServiceId` = '9bf61532d56eb7b5'");
            assertThat(sql).contains("db.statement");
            assertThat(sql).contains("(FLOOR(`end` / 1000000 / 60000) * 60000) >= ");
            assertThat(sql).contains("(FLOOR(`end` / 1000000 / 60000) * 60000) < ");
            assertThat(sql).contains("`startTime` >=");
            return 1L;
        });
        ApmQueryModels.CallSpanRow dbSpan =
                new ApmQueryModels.CallSpanRow(
                        "t-db", "db1", "root", 100L, 110L, "INSERT demo_order_audit", 20_000_000L, 0, 0,
                        "service-a", "9bf61532d56eb7b5", "service-a-1", "service-a", "9bf61532d56eb7b5", "service-a-1",
                        "mysql", "dad537de7e10e098", "", 1, 1,
                        "INSERT demo_order_audit",
                        "{\"db.system\":\"mysql\",\"db.name\":\"demo_apm\","
                                + "\"db.statement\":\"INSERT INTO demo_order_audit(order_id, channel) VALUES (?, ?)\"}",
                        null, null, null, null, null);
        when(reader.queryCallSpans(anyString())).thenReturn(List.of(dbSpan));

        TracePortalService service = new TracePortalService(
                mock(TraceQueryService.class), mock(ServiceFlowService.class), reader, TestStorageSupport.storage());
        Map<String, Object> resp = service.callSpans(Map.of(
                "serviceId", "dad537de7e10e098",
                "srcServiceId", "9bf61532d56eb7b5",
                "resource", "INSERT INTO demo_order_audit(order_id, channel) VALUES (?, ?)",
                "componentType", "service.db",
                "fromTime", "2026-06-05 21:37:00",
                "toTime", "2026-06-05 21:38:00",
                "offset", 0,
                "size", 50));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("data");
        assertThat(resp.get("total")).isEqualTo(1L);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("dbType")).isEqualTo("mysql");
        assertThat(rows.get(0).get("sqlDatabase")).isEqualTo("demo_apm");
    }

    @Test
    void multipleServiceFlowReturnsEmptyWhenEntrypointMissing() {
        TracePortalService service = new TracePortalService(
                mock(TraceQueryService.class), mock(ServiceFlowService.class), mock(ApmReadRepository.class),
                TestStorageSupport.storage());
        Map<String, Object> resp = service.multipleServiceFlow(Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> flows = (Map<String, Object>) resp.get("serviceFlows");
        assertThat(flows).isEmpty();
    }

    @Test
    void exceptionListReadsMetricServiceExceptionRows() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryExceptionListCount(anyString())).thenReturn(1L);
        when(reader.queryExceptionList(anyString())).thenReturn(List.of(
                new ExceptionListPoint(
                        1_710_000_000_000L,
                        "/api/orders/10001",
                        "InsufficientStockException",
                        "service-b",
                        "service-b-id",
                        "inst-1",
                        "/demo/checkout",
                        2L)));

        TracePortalService service = new TracePortalService(
                mock(TraceQueryService.class), mock(ServiceFlowService.class), reader, TestStorageSupport.storage());
        Map<String, Object> resp = service.exceptionList(Map.of(
                "serviceId", "service-b",
                "fromTime", "2026-06-01 11:00:00",
                "toTime", "2026-06-01 13:00:00",
                "offset", 0,
                "size", 20));

        assertThat(resp.get("total")).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("data");
        assertThat(rows.get(0).get("resource")).isEqualTo("/api/orders/10001");
        assertThat(rows.get(0).get("errorType")).isEqualTo("InsufficientStockException");
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) rows.get(0).get("meta");
        assertThat(meta.get("error.type")).isEqualTo("InsufficientStockException");
    }

    @Test
    void multipleServiceFlowBuildsTreeFromMetricRows() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryServiceFlowTreeRows(anyString())).thenReturn(List.of(
                new ServiceFlowTreeRow("root", "", "gateway", "gw", "GET /", 1, 10, 0, 10, 1000),
                new ServiceFlowTreeRow("child", "root", "checkout", "co", "POST /pay", 1, 4, 0, 4, 400)));

        TracePortalService service = new TracePortalService(
                mock(TraceQueryService.class), mock(ServiceFlowService.class), reader, TestStorageSupport.storage());
        Map<String, Object> resp = service.multipleServiceFlow(Map.of(
                "entrypointPathId", "123456789",
                "fromTime", "2026-06-05 21:37:00",
                "toTime", "2026-06-05 21:38:00"));

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> flows = (Map<String, Map<String, Object>>) resp.get("serviceFlows");
        assertThat(flows).containsKey("gateway");
    }
}
