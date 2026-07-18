package com.databuff.apm.web.portal;

import com.databuff.apm.common.query.ApmQueryModels;
import com.databuff.apm.common.query.ApmQueryModels.DbDownstreamPoint;
import com.databuff.apm.common.query.ApmQueryModels.DbServiceSummaryPoint;
import com.databuff.apm.common.query.ApmQueryModels.MetaServicePoint;
import com.databuff.apm.common.query.ApmQueryModels.ServiceSummaryPoint;
import com.databuff.apm.common.query.ApmQueryModels.ServiceTrendBucketPoint;
import com.databuff.apm.common.query.TimeSeriesFillUtil;
import com.databuff.apm.common.util.PortalServiceIdResolver;
import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.web.TestStorageSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServicePortalServiceTest {

  private static void mockEmptyComponentPeerQueries(ApmReadRepository reader) throws Exception {
    when(reader.queryDbDownstream(anyString())).thenReturn(List.of());
  }

  private static void mockDemoOrderHttpRpcPeers(ApmReadRepository reader) throws Exception {
    when(reader.queryDbDownstream(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      if (sql.contains("metric_service_http") && sql.contains("GROUP BY `srcService`")) {
        double avg = sql.contains("`isOut` = '1'") ? 14.0 : 12.0;
        return List.of(new DbDownstreamPoint("9b8a9c29874af555", "demo-gateway", 20, 1, avg));
      }
      if (sql.contains("metric_service_http") && sql.contains("GROUP BY `service`")) {
        double avg = sql.contains("`isOut` = '1'") ? 10.0 : 8.0;
        return List.of(new DbDownstreamPoint("5531560ada6ec064", "demo-pay", 10, 0, avg));
      }
      return List.of();
    });
  }

  private static void mockServiceARelationPeers(ApmReadRepository reader, boolean includeRpc) throws Exception {
    when(reader.queryDbDownstream(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      if (sql.contains("metric_service_http") && sql.contains("GROUP BY `service`")) {
        double avg = sql.contains("`isOut` = '1'") ? 7.0 : 5.0;
        return List.of(new DbDownstreamPoint("b1c2d3e4f5a67890", "service-b", 180, 0, avg));
      }
      if (includeRpc && sql.contains("metric_service_rpc") && sql.contains("GROUP BY `service`")) {
        double avg = sql.contains("`isOut` = '1'") ? 65.0 : 60.0;
        return List.of(new DbDownstreamPoint("b1c2d3e4f5a67890", "service-b", 11, 0, avg));
      }
      if (sql.contains("metric_service_db")) {
        double avg = sql.contains("`isOut` = '1'") ? 3.0 : 2.0;
        return List.of(new DbDownstreamPoint("dad537de7e10e096", "mysql", 28, 0, avg));
      }
      return List.of();
    });
  }

    @Test
    void buildsPortalTrendSeriesWithEpochMsValues() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryServiceTrendBuckets(anyString())).thenReturn(List.of(
                new ServiceTrendBucketPoint(1_780_545_360L, "demo-order", 100, 2, 1_250_000_000),
                new ServiceTrendBucketPoint(1_780_545_420L, "demo-order", 80, 0, 900_000_000),
                new ServiceTrendBucketPoint(1_780_545_360L, "demo-pay", 50, 1, 500_000_000)));

        ServicePortalService service = TestStorageSupport.servicePortalService(reader);
        List<Map<String, Object>> series = service.serviceListTrendChart(Map.of(
                "startTime", "2026-06-04 11:00:00",
                "endTime", "2026-06-04 12:00:00",
                "interval", 60,
                "metric", "reqCount",
                "limit", 1,
                "sortOrder", "desc"));

        assertThat(series).hasSize(1);
        assertThat(series.get(0).get("tags")).isEqualTo(Map.of("service", "demo-order"));
        long from = PortalTimeParser.rangeFrom(Map.of("startTime", "2026-06-04 11:00:00"), 0);
        long to = PortalTimeParser.rangeTo(Map.of("endTime", "2026-06-04 12:00:00"), 0);
        @SuppressWarnings("unchecked")
        List<List<Object>> values = (List<List<Object>>) series.get(0).get("values");
        assertThat(values).hasSize(60);
        assertThat(values.get(0).get(0)).isEqualTo(TimeSeriesFillUtil.alignBucketEpochSec(from, 60) * 1000L);
        assertThat(values.get(0).get(1)).isNull();
        assertThat(values).anyMatch(row ->
                row.get(0).equals(1_780_545_360_000L) && ((Number) row.get(1)).doubleValue() == 100.0);
        assertThat(values.get(values.size() - 1).get(0))
                .isEqualTo(TimeSeriesFillUtil.lastInclusiveBucketEpochSec(to, 60) * 1000L);
    }

    @Test
    void serviceListTrendChartFiltersVirtualServicesForWebOnly() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryServiceTrendBuckets(anyString())).thenReturn(List.of(
                new ServiceTrendBucketPoint(1_780_545_360L, "demo-order", 100, 2, 1_250_000_000),
                new ServiceTrendBucketPoint(1_780_545_360L, "[mysql]demo_apm", 50, 1, 500_000_000)));
        when(reader.queryMetaServices(anyString())).thenReturn(List.of());

        ServicePortalService service = TestStorageSupport.servicePortalService(reader);
        List<Map<String, Object>> series = service.serviceListTrendChart(Map.of(
                "startTime", "2026-06-05 23:34:00",
                "endTime", "2026-06-05 23:49:00",
                "interval", 60,
                "metric", "errRate",
                "limit", 5,
                "sortOrder", "desc",
                "serviceTypes", List.of("web")));

        assertThat(series).hasSize(1);
        assertThat(series.get(0).get("tags")).isEqualTo(Map.of("service", "demo-order"));
    }

    @Test
    void buildsPortalListEnvelope() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryServiceSummaries(anyString())).thenReturn(List.of(
                new ServiceSummaryPoint("demo-order", null, 100, 5, 1_000_000_000, 50_000_000_000L)));
        when(reader.queryDistinctCount(anyString())).thenReturn(1L);
        when(reader.queryMetaServices(anyString())).thenReturn(List.of());

        ServicePortalService service = TestStorageSupport.servicePortalService(reader);
        Map<String, Object> resp = service.list(Map.of(
                "fromTime", "2026-06-04 11:00:00",
                "toTime", "2026-06-04 12:00:00",
                "offset", 0,
                "size", 20));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("data");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("serviceId")).isEqualTo("464a0a08964a061e");
        assertThat(rows.get(0).get("callCnt")).isEqualTo(100L);
        assertThat(rows.get(0).get("maxLatency")).isEqualTo(50_000_000_000L);
        assertThat(rows.get(0).get("language")).isEqualTo("");
        assertThat(resp.get("total")).isEqualTo(1L);
        assertThat(resp.get("status")).isEqualTo(200);
        assertThat(resp.get("message")).isEqualTo("SUCCESS");
    }

    @Test
    void listEnrichesLanguageFromMetaService() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryServiceSummaries(anyString())).thenReturn(List.of(
                new ServiceSummaryPoint("demo-order", null, 100, 5, 1_000_000_000, 0)));
        when(reader.queryDistinctCount(anyString())).thenReturn(1L);
        when(reader.queryMetaServices(anyString())).thenReturn(List.of(
                new ApmQueryModels.MetaServicePoint(
                        "464a0a08964a061e",
                        "Order Service",
                        "demo-order",
                        "web",
                        null,
                        "web",
                        "jvm",
                        "java",
                        "OTLP",
                        "k8s",
                        null,
                        null,
                        false,
                        null,
                        null,
                        "OpenJDK",
                        "17")));

        ServicePortalService service = TestStorageSupport.servicePortalService(reader);
        Map<String, Object> resp = service.list(Map.of(
                "fromTime", "2026-06-04 11:00:00",
                "toTime", "2026-06-04 12:00:00",
                "offset", 0,
                "size", 20));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("data");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("language")).isEqualTo("java");
        assertThat(rows.get(0).get("type")).isEqualTo("web");
    }

    @Test
    void listFiltersVirtualServicesWhenRequestingWebOnly() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryServiceSummaries(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("NOT LIKE '[%%'")) {
                return List.of(new ServiceSummaryPoint("demo-order", null, 100, 5, 1_000_000_000, 0));
            }
            return List.of(
                    new ServiceSummaryPoint("demo-order", null, 100, 5, 1_000_000_000, 0),
                    new ServiceSummaryPoint("[mysql]demo_apm", "c72cc83a8831e407", 50, 0, 500_000_000, 0));
        });
        when(reader.queryDistinctCount(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            return sql.contains("NOT LIKE '[%%'") ? 1L : 2L;
        });

        ServicePortalService service = TestStorageSupport.servicePortalService(reader);
        Map<String, Object> resp = service.list(Map.of(
                "fromTime", "2026-06-05 23:17:00",
                "toTime", "2026-06-05 23:32:00",
                "offset", 0,
                "size", 50,
                "serviceTypes", List.of("web")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("data");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("service")).isEqualTo("demo-order");
        assertThat(rows.get(0).get("type")).isEqualTo("web");
        assertThat(resp.get("total")).isEqualTo(1L);
    }

    @Test
    void listPaginatesAfterServiceTypeFilter() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryServiceSummaries(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("OFFSET 2")) {
                return List.of(new ServiceSummaryPoint("service-c", null, 80, 0, 800_000_000, 0));
            }
            return List.of(
                    new ServiceSummaryPoint("service-a", null, 100, 0, 1_000_000_000, 0),
                    new ServiceSummaryPoint("service-b", null, 90, 0, 900_000_000, 0));
        });
        when(reader.queryDistinctCount(anyString())).thenReturn(3L);

        ServicePortalService service = TestStorageSupport.servicePortalService(reader);
        Map<String, Object> firstPage = service.list(Map.of(
                "fromTime", "2026-06-05 23:17:00",
                "toTime", "2026-06-05 23:32:00",
                "offset", 0,
                "size", 2,
                "serviceTypes", List.of("web")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> firstRows = (List<Map<String, Object>>) firstPage.get("data");
        assertThat(firstRows).hasSize(2);
        assertThat(firstRows.get(0).get("service")).isEqualTo("service-a");
        assertThat(firstRows.get(1).get("service")).isEqualTo("service-b");
        assertThat(firstPage.get("total")).isEqualTo(3L);

        Map<String, Object> secondPage = service.list(Map.of(
                "fromTime", "2026-06-05 23:17:00",
                "toTime", "2026-06-05 23:32:00",
                "offset", 2,
                "size", 2,
                "serviceTypes", List.of("web")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> secondRows = (List<Map<String, Object>>) secondPage.get("data");
        assertThat(secondRows).hasSize(1);
        assertThat(secondRows.get(0).get("service")).isEqualTo("service-c");
        assertThat(secondPage.get("total")).isEqualTo(3L);
    }

    @Test
    void listInfersServiceIconFromName() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryServiceSummaries(anyString())).thenReturn(List.of(
                new ServiceSummaryPoint("[mysql]demo_apm", "c72cc83a8831e407", 50, 0, 500_000_000, 0)));
        when(reader.queryDistinctCount(anyString())).thenReturn(1L);

        ServicePortalService service = TestStorageSupport.servicePortalService(reader);
        Map<String, Object> resp = service.list(Map.of(
                "fromTime", "2026-06-05 23:17:00",
                "toTime", "2026-06-05 23:32:00",
                "offset", 0,
                "size", 50));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("data");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("type")).isEqualTo("mysql");
        assertThat(rows.get(0)).doesNotContainKey("service_type");
    }

    @Test
    void buildsServiceInfoShape() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryMetaServices(anyString())).thenReturn(List.of(
                ApmQueryModels.MetaServicePoint.minimal("demo-order", "Order Service")));
        when(reader.queryServiceSummaries(anyString())).thenReturn(List.of(
                new ServiceSummaryPoint("demo-order", null, 100, 5, 1_000_000_000, 0)));
        when(reader.queryDistinctCount(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("metric_service_http")) {
                return 10L;
            }
            return 0L;
        });

        ServicePortalService service = TestStorageSupport.servicePortalService(reader);
        Map<String, Object> info = service.serviceInfo(Map.of(
                "serviceId", "demo-order",
                "fromTime", "2026-06-04 11:00:00",
                "toTime", "2026-06-04 12:00:00"));

        assertThat(info).isNotNull();
        assertThat(info.get("serviceId")).isEqualTo("464a0a08964a061e");
        assertThat(info.get("name")).isEqualTo("Order Service");
        assertThat(info.get("service")).isEqualTo("demo-order");
        assertThat(info.get("service_type")).isEqualTo("web");
        assertThat(info.get("callCnt")).isEqualTo(100L);
        assertThat(info.get("k8sNamespace")).isEqualTo("");
        assertThat(info).doesNotContainKey("serviceInstanceCount");
        @SuppressWarnings("unchecked")
        List<String> componentTypes = (List<String>) info.get("componentTypes");
        assertThat(componentTypes).contains("service.http");
        @SuppressWarnings("unchecked")
        Map<String, Object> tags = (Map<String, Object>) info.get("tags");
        assertThat(tags.get("custom")).isEqualTo(List.of());
    }

    @Test
    void serviceInfoAddsJvmTechnologyWhenJvmMetricsExist() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryMetaServices(anyString())).thenReturn(List.of(
                MetaServicePoint.minimal("service-a", "service-a")));
        when(reader.queryServiceSummaries(anyString())).thenReturn(List.of());
        when(reader.queryDistinctCount(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("metric_jvm") && sql.contains("COUNT(*)")) {
                return 3L;
            }
            return 0L;
        });

        ServicePortalService service = TestStorageSupport.servicePortalService(reader);
        Map<String, Object> info = service.serviceInfo(Map.of(
                "serviceId", "service-a",
                "fromTime", "2026-06-04 11:00:00",
                "toTime", "2026-06-04 12:00:00"));

        assertThat(info).isNotNull();
        assertThat(String.valueOf(info.get("technology"))).contains("jvm");
        @SuppressWarnings("unchecked")
        List<String> componentTypes = (List<String>) info.get("componentTypes");
        assertThat(componentTypes).doesNotContain("service.jvm");
    }

    @Test
    void serviceInfoReturnsNullWithoutServiceId() {
        ServicePortalService service = TestStorageSupport.servicePortalService(mock(ApmReadRepository.class));
        assertThat(service.serviceInfo(Map.of())).isNull();
    }

  @Test
  void buildsServiceDetailTrendSeries() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryServiceTrendBuckets(anyString())).thenReturn(List.of(
            new ServiceTrendBucketPoint(1_780_545_360L, "demo-order", 100, 2, 1_000_000_000)));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    List<Map<String, Object>> series = service.serviceDetailTrendChart(Map.of(
            "serviceId", "demo-order",
            "startTime", "2026-06-04 11:00:00",
            "endTime", "2026-06-04 12:00:00",
            "interval", 60,
            "metric", "reqCount"));

    long from = PortalTimeParser.rangeFrom(Map.of("startTime", "2026-06-04 11:00:00"), 0);
    assertThat(series).hasSize(1);
    @SuppressWarnings("unchecked")
    List<List<Object>> values = (List<List<Object>>) series.get(0).get("values");
    assertThat(values).hasSize(60);
    assertThat(values.get(0).get(0)).isEqualTo(TimeSeriesFillUtil.alignBucketEpochSec(from, 60) * 1000L);
    assertThat(values).anyMatch(row -> row.get(0).equals(1_780_545_360_000L) && row.get(1).equals(100.0));
  }

  @Test
  void buildsServiceDetailTrendSeriesForMd5ServiceId() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryServiceTrendBuckets(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      assertThat(sql).contains("`service_id` = '9bf61532d56eb7b5'");
      return List.of(new ServiceTrendBucketPoint(1_780_545_360L, "service-a", 100, 2, 1_000_000_000));
    });

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    List<Map<String, Object>> series = service.serviceDetailTrendChart(Map.of(
            "serviceId", "9bf61532d56eb7b5",
            "startTime", "2026-06-04 11:00:00",
            "endTime", "2026-06-04 12:00:00",
            "interval", 60,
            "metric", "reqCount"));

    long from = PortalTimeParser.rangeFrom(Map.of("startTime", "2026-06-04 11:00:00"), 0);
    assertThat(series).hasSize(1);
    @SuppressWarnings("unchecked")
    List<List<Object>> values = (List<List<Object>>) series.get(0).get("values");
    assertThat(values).hasSize(60);
    assertThat(values.get(0).get(0)).isEqualTo(TimeSeriesFillUtil.alignBucketEpochSec(from, 60) * 1000L);
    assertThat(values).anyMatch(row -> row.get(0).equals(1_780_545_360_000L) && row.get(1).equals(100.0));
  }

  @Test
  void buildsGraphStatsMaps() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryServiceTrendBuckets(anyString())).thenReturn(List.of(
            new ServiceTrendBucketPoint(1_780_545_360L, "demo-order", 10, 1, 5_000_000)));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> stats = service.graphStats(Map.of(
            "componentType", "service.http",
            "serviceId", "demo-order",
            "fromTime", "2026-06-04 11:00:00",
            "toTime", "2026-06-04 12:00:00",
            "interval", 60));

    long from = PortalTimeParser.rangeFrom(Map.of("fromTime", "2026-06-04 11:00:00"), 0);
    long to = PortalTimeParser.rangeTo(Map.of("toTime", "2026-06-04 12:00:00"), 0);
    @SuppressWarnings("unchecked")
    Map<String, Number> callCnts = (Map<String, Number>) stats.get("callCnts");
    assertThat(callCnts).hasSize(60);
    assertThat(callCnts.get(String.valueOf(TimeSeriesFillUtil.alignBucketEpochSec(from, 60) * 1000L)))
            .isNull();
    assertThat(callCnts.get("1780545360000")).isEqualTo(10L);
    assertThat(callCnts.keySet()).contains(String.valueOf(TimeSeriesFillUtil.lastInclusiveBucketEpochSec(to, 60) * 1000L));
  }

  @Test
  void fillsMetricStatsGapsWithNull() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryMetricSeries(anyString())).thenReturn(List.of(
            new ApmQueryModels.MetricSeriesPoint(1_780_545_360L, 3.0)));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    List<Map<String, Object>> series = service.metricStats(Map.of(
            "metric", "service.exception",
            "fields", List.of("sum(error)"),
            "filters", Map.of("serviceId", "demo-order"),
            "fromTime", "2026-06-04 11:00:00",
            "toTime", "2026-06-04 12:00:00"));

    assertThat(series).hasSize(1);
    @SuppressWarnings("unchecked")
    List<List<Number>> values = (List<List<Number>>) series.get(0).get("values");
    assertThat(values).hasSize(60);
    assertThat(values).anyMatch(row -> row.get(1) == null);
    assertThat(values).anyMatch(row -> row.get(1) != null && ((Number) row.get(1)).doubleValue() == 3.0);
  }

  @Test
  void endpointsAppliesInboundFilter() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryHttpEndpoints(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      assertThat(sql).contains("`isIn` = '1'");
      assertThat(sql).doesNotContain("`service_id` =");
      return List.of(
              new ApmQueryModels.HttpEndpointPoint(
                      "9bf61532d56eb7b5", "service-a", "/demo/checkout", "GET", "200", 29, 0, 45.0, 90_000_000.0),
              new ApmQueryModels.HttpEndpointPoint(
                      "fedcba0987654321", "service-b", "/api/orders/10001", "GET", "200", 29, 0, 80.0, 160_000_000.0));
    });

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> resp = service.endpoints(Map.of(
            "componentType", "service.http",
            "isIn", 1,
            "fromTime", "2026-06-05 21:43:00",
            "toTime", "2026-06-05 21:58:00",
            "sortField", "callCnt",
            "sortOrder", "desc",
            "offset", 0,
            "size", 50));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("data");
    assertThat(rows).hasSize(2);
    assertThat(rows.stream().map(row -> row.get("serviceName")).toList())
            .containsExactlyInAnyOrder("service-a", "service-b");
  }

  @Test
  void endpointsFallsBackWhenInboundHttpMetricsAreEmpty() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryHttpEndpoints(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      if (sql.contains("`isIn` = '1'")) {
        return List.of();
      }
      assertThat(sql).doesNotContain("`isIn` = '1'");
      return List.of(new ApmQueryModels.HttpEndpointPoint(
              "9bf61532d56eb7b5", "service-a", "/demo/checkout", "GET", "200", 29, 0, 45.0, 90_000_000.0));
    });

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> resp = service.endpoints(Map.of(
            "componentType", "service.http",
            "serviceId", "service-a",
            "isIn", 1,
            "fromTime", "2026-06-05 21:43:00",
            "toTime", "2026-06-05 21:58:00"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("data");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("resource")).isEqualTo("/demo/checkout");
    verify(reader, times(2)).queryHttpEndpoints(anyString());
  }

  @Test
  void endpointsListsInboundDbSql() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryDbEndpoints(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      assertThat(sql).contains("`isIn` = '1'");
      assertThat(sql).contains("metric_service_db");
      return List.of(new ApmQueryModels.DbEndpointPoint(
              "dad537de7e10e098", "mysql",
              "INSERT INTO demo_order_audit(order_id) VALUES (?)",
              "INSERT", "mysql", "demo_apm",
              29, 0, 2.0, 4_000_000.0, 100, 0));
    });

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> resp = service.endpoints(Map.of(
            "componentType", "service.db",
            "isIn", 1,
            "fromTime", "2026-06-05 21:45:00",
            "toTime", "2026-06-05 22:00:00",
            "sortField", "callCnt",
            "sortOrder", "desc",
            "offset", 0,
            "size", 50));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("data");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("serviceName")).isEqualTo("mysql");
    assertThat(rows.get(0).get("sqlOperation")).isEqualTo("INSERT");
    assertThat(rows.get(0).get("callCnt")).isEqualTo(29L);
    assertThat(resp.get("total")).isEqualTo(1);
  }

  @Test
  void endpointsListsInboundDbSqlForDatabaseTarget() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryDbEndpoints(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      assertThat(sql).contains("`isIn` = '1'");
      assertThat(sql).contains("metric_service_db");
      assertThat(sql).contains("dad537de7e10e098");
      assertThat(sql).doesNotContain("9bf61532d56eb7b5");
      assertThat(sql).doesNotContain("NOT LIKE '%elastic%'");
      return List.of(new ApmQueryModels.DbEndpointPoint(
              "dad537de7e10e098", "mysql",
              "INSERT INTO demo_order_audit(order_id) VALUES (?)",
              "INSERT", "mysql", "demo_apm",
              29, 0, 2.0, 4_000_000.0, 100, 0));
    });
    when(reader.queryTopGroups(anyString())).thenReturn(List.of("mysql", "[mysql]demo_apm"));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> resp = service.endpoints(Map.of(
            "componentType", "service.db",
            "serviceId", "dad537de7e10e098",
            "dbTarget", 1,
            "isIn", 1,
            "fromTime", "2026-06-05 21:45:00",
            "toTime", "2026-06-05 22:00:00",
            "sortField", "callCnt",
            "sortOrder", "desc",
            "offset", 0,
            "size", 50));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("data");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("serviceName")).isEqualTo("mysql");
    assertThat(resp.get("total")).isEqualTo(1);
  }

  @Test
  void slowSqlTopListReturnsInboundSlowSqlForDatabaseTarget() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryDbSlowSqlTop(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      assertThat(sql).contains("`isSlow` = '1'");
      assertThat(sql).contains("`isIn` = '1'");
      assertThat(sql).contains("metric_service_db");
      assertThat(sql).contains("dad537de7e10e098");
      assertThat(sql).doesNotContain("9bf61532d56eb7b5");
      assertThat(sql).doesNotContain("NOT LIKE '%elastic%'");
      return List.of(new ApmQueryModels.DbSlowSqlTopPoint(
              "SELECT * FROM demo_order WHERE id = ?",
              12,
              0,
              5_000_000.0,
              20_000_000.0,
              1_000_000.0,
              3));
    });
    when(reader.queryTopGroups(anyString())).thenReturn(List.of("mysql", "[mysql]demo_apm"));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("componentType", "service.db");
    body.put("serviceId", "dad537de7e10e098");
    body.put("dbTarget", 1);
    body.put("isIn", 1);
    body.put("isSlow", 1);
    body.put("fromTime", "2026-06-05 21:45:00");
    body.put("toTime", "2026-06-05 22:00:00");
    body.put("sortField", "avgLatency");
    body.put("sortOrder", "desc");
    body.put("offset", 0);
    body.put("size", 50);
    Map<String, Object> resp = service.slowSqlTopList(body);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("data");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("resource")).isEqualTo("SELECT * FROM demo_order WHERE id = ?");
    assertThat(rows.get(0).get("callCnt")).isEqualTo(12L);
    assertThat(rows.get(0).get("avgLatency")).isEqualTo(5_000_000L);
    assertThat(rows.get(0).get("maxDuration")).isEqualTo(20_000_000L);
    assertThat(rows.get(0).get("minDuration")).isEqualTo(1_000_000L);
    assertThat(rows.get(0).get("srcServiceCnt")).isEqualTo(3L);
    assertThat(resp.get("total")).isEqualTo(1);
  }

  @Test
  void endpointsListsInboundElasticsearchForDatabaseTarget() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryDbEndpoints(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      assertThat(sql).contains("`isIn` = '1'");
      assertThat(sql).contains("metric_service_db");
      assertThat(sql).contains("c89bb188ecff78ec");
      assertThat(sql).doesNotContain("NOT LIKE '%elastic%'");
      return List.of(new ApmQueryModels.DbEndpointPoint(
              "es1234567890abcd", "[elasticsearch]es:9200",
              "GET orders/_search",
              "GET", "elasticsearch", "orders",
              18, 0, 4.0, 8_000_000.0, 0, 0));
    });
    when(reader.queryTopGroups(anyString())).thenReturn(List.of("[elasticsearch]es:9200"));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> resp = service.endpoints(Map.of(
            "componentType", "service.db",
            "serviceId", "[elasticsearch]es:9200",
            "dbTarget", 1,
            "isIn", 1,
            "fromTime", "2026-06-05 21:45:00",
            "toTime", "2026-06-05 22:00:00",
            "sortField", "callCnt",
            "sortOrder", "desc",
            "offset", 0,
            "size", 50));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("data");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("serviceName")).isEqualTo("[elasticsearch]es:9200");
    assertThat(rows.get(0).get("dbType")).isEqualTo("elasticsearch");
    assertThat(resp.get("total")).isEqualTo(1);
  }

  @Test
  void endpointsListsInboundRpcResources() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryComponentEndpoints(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      assertThat(sql).contains("metric_service_rpc");
      assertThat(sql).doesNotContain("metric_service_http");
      assertThat(sql).contains("`isIn` = '1'");
      return List.of(new ApmQueryModels.ComponentEndpointPoint(
              "fedcba0987654321", "service-b", "com.demo.OrderService/getOrder",
              java.util.Map.of("type", "dubbo", "statusCode", "0"),
              29, 0, 45.0, 90_000_000.0, 0, 0, 0, 0, 0, 0));
    });

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> resp = service.endpoints(Map.of(
            "componentType", "service.rpc",
            "isIn", 1,
            "fromTime", "2026-06-06 00:19:00",
            "toTime", "2026-06-06 00:34:00",
            "sortField", "callCnt",
            "sortOrder", "desc",
            "offset", 0,
            "size", 50));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("data");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("resource")).isEqualTo("com.demo.OrderService/getOrder");
    assertThat(rows.get(0).get("type")).isEqualTo("dubbo");
    assertThat(rows.get(0).get("callCnt")).isEqualTo(29L);
  }

  @Test
  void buildsEndpointsEnvelope() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryHttpEndpoints(anyString())).thenReturn(List.of(
            new ApmQueryModels.HttpEndpointPoint(
                    "demo-order-id", "demo-order", "/orders", "GET", "200", 20, 1, 50.0, 100_000_000.0),
            new ApmQueryModels.HttpEndpointPoint(
                    "demo-order-id", "demo-order", "/health", "GET", "200", 50, 0, 10.0, 20_000_000.0)));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> resp = service.endpoints(Map.of(
            "componentType", "service.http",
            "serviceId", "demo-order",
            "fromTime", "2026-06-04 11:00:00",
            "toTime", "2026-06-04 12:00:00",
            "sortField", "callCnt",
            "sortOrder", "desc",
            "offset", 0,
            "size", 10));

    assertThat(resp.get("status")).isEqualTo(200);
    assertThat(resp.get("total")).isEqualTo(2);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("data");
    assertThat(rows.get(0).get("resource")).isEqualTo("/health");
    assertThat(rows.get(0).get("callCnt")).isEqualTo(50L);
    assertThat(rows.get(0).get("serviceId")).isEqualTo("353958a7cd58ce61");
    assertThat(rows.get(0).get("avgLatency")).isEqualTo(10_000_000L);
    assertThat(rows.get(0).get("maxLatency")).isEqualTo(20_000_000L);
    assertThat(rows.get(1).get("resource")).isEqualTo("/orders");
    assertThat(rows.get(1).get("avgLatency")).isEqualTo(50_000_000L);
    assertThat(rows.get(1).get("maxLatency")).isEqualTo(100_000_000L);
  }

  @Test
  void buildsDistributionStatsFromLatencyBuckets() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryHttpLatencyBuckets(anyString())).thenReturn(List.of(
            new ApmQueryModels.HttpLatencyBucketPoint("0-50ms", 10, 0),
            new ApmQueryModels.HttpLatencyBucketPoint("50-100ms", 5, 1)));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> stats = service.distributionStats(Map.of(
            "componentType", "service.http",
            "serviceId", "demo-order",
            "fromTime", "2026-06-04 11:00:00",
            "toTime", "2026-06-04 12:00:00"));

    @SuppressWarnings("unchecked")
    Map<String, Long> histogram = (Map<String, Long>) stats.get("histogram");
    assertThat(histogram).isNotEmpty();
    assertThat(stats.get("p50Latency")).isNotNull();
    assertThat(stats.get("serviceId")).isEqualTo("464a0a08964a061e");
  }

  @Test
  void buildsReqTopRows() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryHttpEndpoints(anyString())).thenReturn(List.of(
            new ApmQueryModels.HttpEndpointPoint(
                    "demo-order-id", "demo-order", "/orders", "GET", "200", 20, 1, 50.0, 100_000_000.0),
            new ApmQueryModels.HttpEndpointPoint(
                    "demo-order-id", "demo-order", "/health", "GET", "200", 50, 0, 10.0, 20_000_000.0)));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    List<Map<String, Object>> rows = service.reqTop(Map.of(
            "componentType", "service.http",
            "serviceId", "demo-order",
            "fromTime", "2026-06-04 11:00:00",
            "toTime", "2026-06-04 12:00:00",
            "size", 5));

    assertThat(rows.get(0).get("resource")).isEqualTo("/orders");
    assertThat(rows.get(0).get("avgLatency")).isEqualTo(50_000_000L);
  }

  @Test
  void buildsExceptionDistEnvelope() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryExceptionDist(anyString(), org.mockito.ArgumentMatchers.eq("resource"))).thenReturn(List.of(
            new ApmQueryModels.ExceptionDistPoint(
                    null, null, null, "/orders", "/orders", 4)));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> resp = service.exceptionDistMap(Map.of(
            "groupBy", "resource",
            "serviceId", "demo-order",
            "fromTime", "2026-06-04 11:00:00",
            "toTime", "2026-06-04 12:00:00"));

    assertThat(resp.get("totalError")).isEqualTo(4L);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("data");
    assertThat(rows.get(0).get("resource")).isEqualTo("/orders");
  }

  @Test
  void exceptionDistByServiceIdReturnsDisplayName() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryExceptionDist(anyString(), org.mockito.ArgumentMatchers.eq("serviceId"))).thenReturn(List.of(
            new ApmQueryModels.ExceptionDistPoint(
                    null, "9bf61532d56eb7b5", null, null, null, 12)));
    when(reader.queryMetaServices(anyString())).thenReturn(List.of(
            MetaServicePoint.minimal("9bf61532d56eb7b5", "service-a")));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> resp = service.exceptionDistMap(Map.of(
            "groupBy", "serviceId",
            "fromTime", "2026-06-04 11:00:00",
            "toTime", "2026-06-04 12:00:00"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("data");
    assertThat(rows.get(0).get("serviceId")).isEqualTo("9bf61532d56eb7b5");
    assertThat(rows.get(0).get("service")).isEqualTo("service-a");
    assertThat(rows.get(0).get("serviceName")).isEqualTo("service-a");
  }

  @Test
  void exceptionDistByServiceIdFallsBackToMetricServiceName() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryExceptionDist(anyString(), org.mockito.ArgumentMatchers.eq("serviceId"))).thenReturn(List.of(
            new ApmQueryModels.ExceptionDistPoint(
                    null, "demo-order", null, null, null, 5)));
    when(reader.queryMetaServices(anyString())).thenReturn(List.of());

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> resp = service.exceptionDistMap(Map.of(
            "groupBy", "serviceId",
            "fromTime", "2026-06-04 11:00:00",
            "toTime", "2026-06-04 12:00:00"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("data");
    assertThat(rows.get(0).get("service")).isEqualTo("demo-order");
  }

  @Test
  void exceptionDistByServiceIdTop5UsesResolvedServiceKeySql() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryExceptionDist(anyString(), org.mockito.ArgumentMatchers.eq("serviceId")))
            .thenAnswer(invocation -> {
              String sql = invocation.getArgument(0);
              assertThat(sql).contains("metric_service_exception");
              assertThat(sql).contains("GROUP BY COALESCE(NULLIF(`service_id`, ''), `service`)");
              assertThat(sql).doesNotContain("GROUP BY service_id\n");
              return List.of(
                      new ApmQueryModels.ExceptionDistPoint(
                              null, "demo-order", null, null, null, 12),
                      new ApmQueryModels.ExceptionDistPoint(
                              null, "demo-pay", null, null, null, 8),
                      new ApmQueryModels.ExceptionDistPoint(
                              null, "demo-gateway", null, null, null, 5),
                      new ApmQueryModels.ExceptionDistPoint(
                              null, "demo-inventory", null, null, null, 3),
                      new ApmQueryModels.ExceptionDistPoint(
                              null, "demo-user", null, null, null, 2),
                      new ApmQueryModels.ExceptionDistPoint(
                              null, "demo-auth", null, null, null, 1));
            });
    when(reader.queryMetaServices(anyString())).thenReturn(List.of());

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> resp = service.exceptionDistMap(Map.of(
            "groupBy", "serviceId",
            "offset", 0,
            "size", 5,
            "sortField", "errCnt",
            "sortOrder", "desc",
            "fromTime", "2026-06-04 11:00:00",
            "toTime", "2026-06-04 12:00:00"));

    assertThat(resp.get("total")).isEqualTo(6);
    assertThat(resp.get("totalError")).isEqualTo(31L);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("data");
    assertThat(rows).hasSize(5);
    assertThat(rows.get(0).get("service")).isEqualTo("demo-order");
    assertThat(rows.get(0).get("errCnt")).isEqualTo(12L);
    assertThat(rows.get(0).get("percentage")).isEqualTo(12.0 * 100 / 31);
  }

  @Test
  void exceptionDistByExceptionNamePrefersMetricWithoutDoubleCounting() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryExceptionDist(anyString(), org.mockito.ArgumentMatchers.eq("exceptionName")))
            .thenAnswer(invocation -> {
              String sql = invocation.getArgument(0);
              if (sql.contains("metric_service_exception")) {
                return List.of(new ApmQueryModels.ExceptionDistPoint(
                        "InsufficientStockException", null, null, null, null, 60));
              }
              return List.of(new ApmQueryModels.ExceptionDistPoint(
                      "InsufficientStockException", null, null, null, null, 60));
            });

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> resp = service.exceptionDistMap(Map.of(
            "groupBy", "exceptionName",
            "fromTime", "2026-06-04 11:00:00",
            "toTime", "2026-06-04 12:00:00"));

    assertThat(resp.get("totalError")).isEqualTo(60L);
  }

  @Test
  void exceptionDistByServiceInstanceUsesMetricExceptionTable() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryExceptionDist(anyString(), org.mockito.ArgumentMatchers.eq("serviceId,serviceInstance")))
            .thenReturn(List.of(
                    new ApmQueryModels.ExceptionDistPoint(
                            null, "demo-order", "inst-1", null, null, 7)));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> resp = service.exceptionDistMap(Map.of(
            "groupBy", "serviceId,serviceInstance",
            "serviceId", "demo-order",
            "fromTime", "2026-06-04 11:00:00",
            "toTime", "2026-06-04 12:00:00"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("data");
    assertThat(rows.get(0).get("serviceInstance")).isEqualTo("inst-1");
    assertThat(rows.get(0).get("errCnt")).isEqualTo(7L);
  }

  @Test
  void callEndpointsPreferMetricServiceIdOverRequestName() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryHttpEndpoints(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      if (sql.contains("`isOut` = '1'")) {
        return List.of(new ApmQueryModels.HttpEndpointPoint(
                "a1b2c3d4e5f67890", "service-a", "/api/orders/10001", "GET", "200", 29, 0, 100.0, 200_000_000.0));
      }
      return List.of(new ApmQueryModels.HttpEndpointPoint(
              "fedcba0987654321", "service-b", "/api/orders/10001", "GET", "200", 29, 0, 80.0, 160_000_000.0));
    });

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> resp = service.callEndpoints(Map.of(
            "componentType", "service.http",
            "serviceId", "service-b",
            "srcServiceId", "service-a",
            "fromTime", "2026-06-05 20:36:00",
            "toTime", "2026-06-05 20:37:00"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("data");
    assertThat(rows.get(0).get("serviceId")).isEqualTo("fedcba0987654321");
    assertThat(rows.get(0).get("serviceName")).isEqualTo("service-b");
    assertThat(rows.get(0).get("srcServiceId")).isEqualTo("a1b2c3d4e5f67890");
  }

  @Test
  void reqContributorServiceGroupsBySrcServiceForInboundResource() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryComponentResourceRelations(anyString(), any())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      @SuppressWarnings("unchecked")
      List<String> groupBy = invocation.getArgument(1);
      assertThat(groupBy).containsExactly("srcService", "srcServiceId");
      assertThat(sql).contains("metric_service_http");
      assertThat(sql).containsAnyOf("5457a0119281bb98", "service-b");
      assertThat(sql).contains("/api/orders/10001");
      assertThat(sql).contains("`isIn` = '1'");
      return List.of(new ApmQueryModels.ComponentResourceRelationPoint(
              "5457a0119281bb98",
              "service-b",
              "/api/orders/10001",
              "9bf61532d56eb7b5",
              "service-a",
              null,
              null,
              29,
              0,
              0,
              80_000_000.0,
              100_000_000.0));
    });

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> resp = service.reqContributorService(Map.of(
            "componentType", "service.http",
            "serviceId", "service-b",
            "resourceQuery", "/api/orders/10001",
            "isIn", 1,
            "fromTime", "2026-06-05 20:36:00",
            "toTime", "2026-06-05 20:37:00"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("list");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("serviceName")).isEqualTo("service-a");
    assertThat(rows.get(0).get("serviceId")).isEqualTo("9bf61532d56eb7b5");
    assertThat(rows.get(0).get("srcService")).isEqualTo("service-a");
    assertThat(rows.get(0).get("callCnt")).isEqualTo(29L);
  }

  @Test
  void buildsCallEndpointsEnvelope() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryHttpEndpoints(anyString())).thenReturn(List.of(
            new ApmQueryModels.HttpEndpointPoint(
                    "demo-order-id", "demo-order", "/orders", "GET", "200", 20, 1, 50.0, 100_000_000.0)));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> resp = service.callEndpoints(Map.of(
            "componentType", "service.http",
            "serviceId", "demo-order-id",
            "srcServiceId", "demo-pay-id",
            "fromTime", "2026-06-04 11:00:00",
            "toTime", "2026-06-04 12:00:00"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("data");
    assertThat(rows.get(0).get("reqInCnt")).isEqualTo(20L);
    assertThat(rows.get(0).get("serviceId")).isEqualTo("353958a7cd58ce61");
    assertThat(rows.get(0).get("srcServiceId")).isEqualTo("a90411bd7ed56cce");
    assertThat(rows.get(0).get("serviceName")).isEqualTo("demo-order");
  }

  @Test
  void dbCallGraphStatsResolvesMd5ServiceIdsAgainstReadableDbMetrics() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryComponentTrendBuckets(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      if (!sql.contains("metric_service_db")) {
        return List.of();
      }
      assertThat(sql).containsAnyOf("dad537de7e10e098", "mysql");
      assertThat(sql).containsAnyOf("9bf61532d56eb7b5", "service-a");
      return List.of(new ApmQueryModels.ComponentTrendBucketPoint(
              1_780_653_280L, "mysql", 10, 0, 5_000_000L, 8_000_000L, 1_000_000L, 100, 5));
    });
    when(reader.queryTopGroups(anyString())).thenReturn(List.of("mysql", "service-a"));
    when(reader.queryMetaServices(anyString())).thenReturn(List.of(
            MetaServicePoint.minimal("9bf61532d56eb7b5", "Service A")));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> outbound = service.callGraphStats(Map.of(
            "componentType", "service.db",
            "serviceId", "dad537de7e10e098",
            "srcServiceId", "9bf61532d56eb7b5",
            "isOut", 1,
            "fromTime", "2026-06-05 21:08:00",
            "toTime", "2026-06-05 21:23:00"));

    assertThat(outbound).containsKey("callCnts");
    assertThat(outbound).containsKey("avgReadRows");
  }

  @Test
  void rpcCallGraphStatsUsesComponentTrendBuckets() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryComponentTrendBuckets(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      if (!sql.contains("metric_service_rpc")) {
        return List.of();
      }
      assertThat(sql).contains("`isIn` = '1'");
      return List.of(new ApmQueryModels.ComponentTrendBucketPoint(
              1_780_653_280L, "service-b", 12, 1, 6_000_000L, 9_000_000L, 2_000_000L, 0, 0));
    });

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> inbound = service.callGraphStats(Map.of(
            "componentType", "service.rpc",
            "serviceId", "service-b",
            "srcServiceId", "service-a",
            "isIn", 1,
            "fromTime", "2026-06-05 21:08:00",
            "toTime", "2026-06-05 21:23:00"));

    assertThat(inbound).containsKey("callCnts");
    assertThat(inbound).containsKey("avgLatencys");
  }

  @Test
  void redisCallEndpointsUsesComponentEndpointSummary() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryComponentEndpoints(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      assertThat(sql).contains("metric_service_redis");
      return List.of(new ApmQueryModels.ComponentEndpointPoint(
              "redis-id", "[redis]demo", "GET key",
              java.util.Map.of("command", "GET"),
              15, 0, 1.5, 3_000_000.0, 0, 0, 100, 200, 0, 0));
    });
    when(reader.queryTopGroups(anyString())).thenReturn(List.of("[redis]demo", "service-a"));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> resp = service.callEndpoints(Map.of(
            "componentType", "service.redis",
            "serviceId", "[redis]demo",
            "srcServiceId", "service-a",
            "fromTime", "2026-06-05 21:08:00",
            "toTime", "2026-06-05 21:23:00"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("data");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("resource")).isEqualTo("GET key");
    assertThat(rows.get(0).get("command")).isEqualTo("GET");
    assertThat(rows.get(0).get("reqInCnt")).isEqualTo(15L);
  }

  @Test
  void dbCallEndpointsResolvesMd5ServiceIdsAgainstReadableDbMetrics() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryDbEndpoints(anyString())).thenReturn(List.of(
            new ApmQueryModels.DbEndpointPoint(
                    "dad537de7e10e098", "mysql", "SELECT 1", "SELECT", "mysql", "demo_apm",
                    28, 0, 2.0, 4_000_000.0, 100, 0)));
    when(reader.queryTopGroups(anyString())).thenReturn(List.of("mysql", "service-a"));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> resp = service.callEndpoints(Map.of(
            "componentType", "service.db",
            "serviceId", "dad537de7e10e098",
            "srcServiceId", "9bf61532d56eb7b5",
            "fromTime", "2026-06-05 21:08:00",
            "toTime", "2026-06-05 21:23:00"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("data");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("serviceId")).isEqualTo("dad537de7e10e098");
    assertThat(rows.get(0).get("serviceName")).isEqualTo("mysql");
    assertThat(resp.get("total")).isEqualTo(1);
  }

  @Test
  void dbCallInfoResolvesMd5ServiceIdsAgainstReadableDbMetrics() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryDbEndpoints(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      if (!sql.contains("dad537de7e10e098") && !sql.contains("mysql")) {
        return List.of();
      }
      if (!sql.contains("9bf61532d56eb7b5") && !sql.contains("service-a")) {
        return List.of();
      }
      return List.of(new ApmQueryModels.DbEndpointPoint(
              "dad537de7e10e098", "mysql", "SELECT 1", "SELECT", "mysql", "demo_apm",
              28, 0, 2.0, 4_000_000.0, 100, 0));
    });
    when(reader.queryTopGroups(anyString())).thenReturn(List.of("mysql", "service-a"));
    when(reader.queryMetaServices(anyString())).thenReturn(List.of(
            MetaServicePoint.minimal("9bf61532d56eb7b5", "Service A")));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> data = service.callInfo(Map.of(
            "componentType", "service.db",
            "serviceId", "dad537de7e10e098",
            "srcServiceId", "9bf61532d56eb7b5",
            "fromTime", "2026-06-05 21:08:00",
            "toTime", "2026-06-05 21:23:00"));

    assertThat(data.get("reqInCnt")).isEqualTo(28L);
    assertThat(data.get("reqOutCnt")).isEqualTo(28L);
    assertThat(data.get("componentType")).isEqualTo("service.db");
  }

  @Test
  void callInfoUsesDirectionalHttpMetricsForInAndOutCounts() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryComponentCallStats(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      if (sql.contains("`isOut` = '1'")) {
        if (sql.contains("service-b") || sql.contains("5457a0119281bb98")) {
          return new ApmQueryModels.ComponentCallStatsPoint(0, 0, 0);
        }
        return new ApmQueryModels.ComponentCallStatsPoint(22, 0, 880_000_000L);
      }
      return new ApmQueryModels.ComponentCallStatsPoint(22, 0, 1_760_000_000L);
    });

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> data = service.callInfo(Map.of(
            "componentType", "service.http",
            "serviceId", "service-b-id",
            "srcServiceId", "service-a-id",
            "resource", "/api/orders/10001",
            "fromTime", "2026-06-05 20:36:00",
            "toTime", "2026-06-05 20:37:00"));

    assertThat(data.get("reqInCnt")).isEqualTo(22L);
    assertThat(data.get("reqOutCnt")).isEqualTo(22L);
  }

  @Test
  void rpcCallInfoUsesDirectionalComponentStats() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryComponentCallStats(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      assertThat(sql).contains("metric_service_rpc");
      if (sql.contains("`isIn` = '1'")) {
        assertThat(sql).containsAnyOf("5457a0119281bb98", "service-b");
        assertThat(sql).containsAnyOf("9bf61532d56eb7b5", "service-a");
        return new ApmQueryModels.ComponentCallStatsPoint(40, 1, 2_000_000_000L);
      }
      if (sql.contains("`isOut` = '1'")
              && (sql.contains("5457a0119281bb98") || sql.contains("service-b"))
              && (sql.contains("9bf61532d56eb7b5") || sql.contains("service-a"))) {
        return new ApmQueryModels.ComponentCallStatsPoint(0, 0, 0);
      }
      return new ApmQueryModels.ComponentCallStatsPoint(40, 1, 2_000_000_000L);
    });
    when(reader.queryTopGroups(anyString())).thenReturn(List.of("service-a", "service-b"));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> data = service.callInfo(Map.of(
            "componentType", "service.rpc",
            "serviceId", "5457a0119281bb98",
            "srcServiceId", "9bf61532d56eb7b5",
            "fromTime", "2026-06-06 00:21:00",
            "toTime", "2026-06-06 00:36:00"));

    assertThat(data.get("reqInCnt")).isEqualTo(40L);
    assertThat(data.get("reqOutCnt")).isEqualTo(40L);
    assertThat(data.get("componentType")).isEqualTo("service.rpc");
  }

  @Test
  void buildsResourceInfo() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryHttpEndpoints(anyString())).thenReturn(List.of(
            new ApmQueryModels.HttpEndpointPoint(
                    "demo-order-id", "demo-order", "/orders", "POST", "200", 30, 2, 25.0, 50_000_000.0)));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> info = service.resourceInfo(Map.of(
            "serviceId", "demo-order",
            "url", "/orders",
            "fromTime", "2026-06-04 11:00:00",
            "toTime", "2026-06-04 12:00:00"));

    assertThat(info.get("resource")).isEqualTo("/orders");
    assertThat(info.get("callCnt")).isEqualTo(30L);
  }

  @Test
  void decodesDoubleEncodedResourcePath() {
    assertThat(ServicePortalService.decodeResourceValue("%252Fdemo%252Fcheckout"))
            .isEqualTo("/demo/checkout");
  }

  @Test
  void buildsResourceRelationGroupedByComponentType() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryHttpEndpoints(anyString())).thenReturn(List.of(
            new ApmQueryModels.HttpEndpointPoint(
                    "9bf61532d56eb7b5", "service-a", "/demo/checkout", "GET", "200", 29, 1, 45.0, 90_000_000.0)));
    when(reader.queryComponentResourceRelations(anyString(), any())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      if (sql.contains("`isIn` = '1'") && sql.contains("GROUP BY `service_id`, `service`, `url`")) {
        return List.of(new ApmQueryModels.ComponentResourceRelationPoint(
                "9bf61532d56eb7b5", "service-a", "/demo/checkout",
                null, null, null, null,
                29, 2, 1, 45_000_000.0, 120_000_000.0));
      }
      return List.of();
    });

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> data = service.resourceRelation(Map.of(
            "serviceId", "9bf61532d56eb7b5",
            "url", "%252Fdemo%252Fcheckout",
            "componentType", "service.http",
            "start", 1_780_545_360_000L,
            "end", 1_780_548_960_000L));

    assertThat(data.get("reqCnt")).isEqualTo(29L);
    @SuppressWarnings("unchecked")
    Map<String, List<Map<String, Object>>> current =
            (Map<String, List<Map<String, Object>>>) data.get("currentResources");
    assertThat(current.get("service.http")).hasSize(1);
    assertThat(current.get("service.http").get(0).get("resource")).isEqualTo("/demo/checkout");
    assertThat(current.get("service.http").get(0).get("allCnt")).isEqualTo(29L);
    assertThat(current.get("service.http").get(0).get("avgTime")).isEqualTo(45_000_000.0);
  }

  @Test
  void buildsResourceRelationDownstreamGroupedByComponentType() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryHttpEndpoints(anyString())).thenReturn(List.of(
            new ApmQueryModels.HttpEndpointPoint(
                    "9bf61532d56eb7b5", "service-a", "/demo/checkout", "GET", "200", 29, 1, 45.0, 90_000_000.0)));
    when(reader.queryComponentResourceRelations(anyString(), any())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      if (sql.contains("metric_service_http")
              && sql.contains("`rootResource` = '/demo/checkout'")
              && sql.contains("`isOut` = '1'")) {
        return List.of(new ApmQueryModels.ComponentResourceRelationPoint(
                "b1c2d3e4f5a67890", "service-b", "/api/orders/10001",
                "9bf61532d56eb7b5", "service-a", "/demo/checkout", null,
                12, 1, 0, 30_000_000.0, 80_000_000.0));
      }
      if (sql.contains("metric_service_db")
              && sql.contains("`rootResource` = '/demo/checkout'")
              && sql.contains("`isOut` = '1'")) {
        return List.of(new ApmQueryModels.ComponentResourceRelationPoint(
                "dad537de7e10e098", "mysql", "INSERT INTO demo_order_audit(order_id) VALUES (?)",
                "9bf61532d56eb7b5", "service-a", "/demo/checkout", null,
                8, 0, 1, 20_000_000.0, 50_000_000.0));
      }
      if (sql.contains("metric_service_redis")
              && sql.contains("`rootResource` = '/demo/checkout'")
              && sql.contains("`isOut` = '1'")) {
        return List.of(new ApmQueryModels.ComponentResourceRelationPoint(
                "redis-id", "redis", "GET cart:10001",
                "9bf61532d56eb7b5", "service-a", "/demo/checkout", null,
                5, 0, 0, 10_000_000.0, 30_000_000.0));
      }
      return List.of();
    });

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> data = service.resourceRelation(Map.of(
            "serviceId", "9bf61532d56eb7b5",
            "url", "/demo/checkout",
            "componentType", "service.http",
            "start", 1_780_703_940_000L,
            "end", 1_780_704_840_000L));

    @SuppressWarnings("unchecked")
    Map<String, List<Map<String, Object>>> downstream =
            (Map<String, List<Map<String, Object>>>) data.get("downFlowResources");
    assertThat(downstream.get("service.http")).hasSize(1);
    assertThat(downstream.get("service.http").get(0).get("resource")).isEqualTo("/api/orders/10001");
    assertThat(downstream.get("service.http").get(0).get("serviceId")).isEqualTo("b1c2d3e4f5a67890");
    assertThat(downstream.get("service.db")).hasSize(1);
    assertThat(downstream.get("service.db").get(0).get("allCnt")).isEqualTo(8L);
    assertThat(downstream.get("service.redis")).hasSize(1);
    assertThat(downstream.get("service.redis").get(0).get("resource")).isEqualTo("GET cart:10001");
  }

  @Test
  void buildsResourceAllCntTrend() throws Exception {
    Map<String, Object> body = Map.of(
            "serviceId", "9bf61532d56eb7b5",
            "url", "/demo/checkout",
            "componentType", "service.http",
            "isIn", 1,
            "fromTime", "2026-06-05 22:11:00",
            "toTime", "2026-06-05 23:11:00",
            "interval", 60);
    long bucketEpochSec = TimeSeriesFillUtil.alignBucketEpochSec(
            PortalTimeParser.rangeFrom(body, 0L), 60);

    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryComponentTrendBuckets(anyString())).thenReturn(List.of(
            new ApmQueryModels.ComponentTrendBucketPoint(
                    bucketEpochSec, "service-a", 10, 1, 0, 0, 0, 0, 0, 2)));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> data = service.allCntForSingleResource(body);

    verify(reader, times(1)).queryComponentTrendBuckets(anyString());
    verify(reader, never()).queryTopGroups(anyString());
    verify(reader).queryComponentTrendBuckets(org.mockito.ArgumentMatchers.argThat(sql ->
            sql.contains("metric_service_http")
                    && sql.contains("`service_id` = '9bf61532d56eb7b5'")
                    && sql.contains("`url` = '/demo/checkout'")
                    && !sql.contains("DISTINCT")));

    @SuppressWarnings("unchecked")
    Map<String, Long> bucket = (Map<String, Long>) data.get(String.valueOf(bucketEpochSec * 1000L));
    assertThat(bucket.get("call")).isEqualTo(10L);
    assertThat(bucket.get("slow")).isEqualTo(2L);
    assertThat(bucket.get("error")).isEqualTo(1L);
  }

  @Test
  void buildsResourcesMap() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryDistinctTags(anyString())).thenReturn(List.of("/orders", "/health"));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, List<String>> resources = service.resources(Map.of(
            "field", "resource",
            "componentType", "service.http",
            "serviceId", "demo-order",
            "fromTime", "2026-06-04 11:00:00",
            "toTime", "2026-06-04 12:00:00"));

    assertThat(resources.get("service.http")).containsExactly("/orders", "/health");
  }

  @Test
  void buildsMetricStatsSeries() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryTopGroups(anyString())).thenReturn(List.of("InternalServerError"));
    when(reader.queryMetricSeries(anyString())).thenReturn(List.of(
            new ApmQueryModels.MetricSeriesPoint(1_780_545_360L, 3.0)));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    List<Map<String, Object>> series = service.metricStats(Map.of(
            "metric", "service.exception",
            "fields", List.of("sum(error)"),
            "groupBys", List.of("exceptionName"),
            "filters", Map.of("serviceId", "demo-order"),
            "fromTime", "2026-06-04 11:00:00",
            "toTime", "2026-06-04 12:00:00"));

    assertThat(series).hasSize(1);
    assertThat(series.get(0).get("tags")).isEqualTo(Map.of("exceptionName", "InternalServerError"));
    @SuppressWarnings("unchecked")
    List<List<Number>> values = (List<List<Number>>) series.get(0).get("values");
    assertThat(values).hasSize(60);
    assertThat(values).anyMatch(row -> row.get(1) == null);
    assertThat(values).anyMatch(row -> row.get(1) != null && ((Number) row.get(1)).doubleValue() == 3.0);
  }

  @Test
  void buildsResourceStatsSeries() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryServiceTrendBuckets(anyString())).thenReturn(List.of(
            new ServiceTrendBucketPoint(1_780_545_360L, "demo-order", 10, 0, 5_000_000)));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    List<Map<String, Object>> series = service.resourceStats(Map.of(
            "componentType", "service.http",
            "serviceId", "demo-order",
            "resource", "/orders",
            "fromTime", "2026-06-04 11:00:00",
            "toTime", "2026-06-04 12:00:00",
            "interval", 60));

    assertThat(series).hasSize(1);
    @SuppressWarnings("unchecked")
    Map<String, String> tags = (Map<String, String>) series.get(0).get("tags");
    assertThat(tags.get("service")).isEqualTo("demo-order");
    @SuppressWarnings("unchecked")
    List<List<Number>> values = (List<List<Number>>) series.get(0).get("values");
    assertThat(values).hasSizeGreaterThan(1);
    assertThat(values).anyMatch(row -> row.get(1) == null);
  }

  @Test
  void mergesInboundAndOutboundCallGraphStats() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryServiceTrendBuckets(anyString())).thenReturn(List.of(
            new ServiceTrendBucketPoint(1_717_200_000L, "demo-order", 50, 2, 5_000_000L)));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> result = service.callGraphStats(Map.of(
            "serviceId", "demo-order",
            "srcServiceId", "demo-pay",
            "url", "/orders",
            "fromTime", "2026-06-01 11:00:00",
            "toTime", "2026-06-01 13:00:00"));

    @SuppressWarnings("unchecked")
    Map<String, Object> isIn = (Map<String, Object>) result.get("isIn");
    @SuppressWarnings("unchecked")
    Map<String, Object> isOut = (Map<String, Object>) result.get("isOut");
    assertThat(isIn).isNotEmpty();
    assertThat(isOut).isNotEmpty();
  }

  @Test
  void returnsFlatCallGraphStatsWhenDirectionRequested() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryServiceTrendBuckets(anyString())).thenReturn(List.of(
            new ServiceTrendBucketPoint(1_717_200_000L, "demo-order", 50, 2, 5_000_000L)));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> inbound = service.callGraphStats(Map.of(
            "serviceId", "demo-order",
            "srcServiceId", "demo-pay",
            "isIn", 1,
            "fromTime", "2026-06-01 11:00:00",
            "toTime", "2026-06-01 13:00:00"));
    Map<String, Object> outbound = service.callGraphStats(Map.of(
            "serviceId", "demo-order",
            "srcServiceId", "demo-pay",
            "isOut", 1,
            "fromTime", "2026-06-01 11:00:00",
            "toTime", "2026-06-01 13:00:00"));

    assertThat(inbound).containsKey("callCnts");
    assertThat(inbound).doesNotContainKey("isIn");
    assertThat(outbound).containsKey("callCnts");
    assertThat(outbound).doesNotContainKey("isOut");
  }

  @Test
  void fallsBackToCallerMetricsForLegacyOutboundCallGraphStats() throws Exception {
    long from = PortalTimeParser.rangeFrom(Map.of("fromTime", "2026-06-01 11:00:00"), 0);
    long bucket = TimeSeriesFillUtil.alignBucketEpochSec(from, 60);
    String demoOrderId = PortalServiceIdResolver.normalize("demo-order");
    String demoPayId = PortalServiceIdResolver.normalize("demo-pay");
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryServiceTrendBuckets(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      if (sql.contains(demoOrderId) && sql.contains(demoPayId) && sql.contains("`isOut` = '1'")) {
        return List.of(new ServiceTrendBucketPoint(bucket, "demo-pay", 50, 2, 5_000_000L));
      }
      return List.of();
    });

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> outbound = service.callGraphStats(Map.of(
            "serviceId", demoOrderId,
            "srcServiceId", demoPayId,
            "isOut", 1,
            "fromTime", "2026-06-01 11:00:00",
            "toTime", "2026-06-01 13:00:00"));

    verify(reader, times(1)).queryServiceTrendBuckets(anyString());
    assertThat(outbound).containsKey("callCnts");
    @SuppressWarnings("unchecked")
    Map<String, Number> callCnts = (Map<String, Number>) outbound.get("callCnts");
    assertThat(callCnts.get(String.valueOf(bucket * 1000L))).isEqualTo(50L);
  }

  @Test
  void rpcCallGraphStatsOutboundFallsBackToCallerMetrics() throws Exception {
    long from = PortalTimeParser.rangeFrom(Map.of("fromTime", "2026-06-06 00:21:00"), 0);
    long bucket = TimeSeriesFillUtil.alignBucketEpochSec(from, 60);
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryComponentTrendBuckets(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      if (!sql.contains("metric_service_rpc")) {
        return List.of();
      }
      if (sql.contains("5457a0119281bb98") && sql.contains("9bf61532d56eb7b5") && sql.contains("`isOut` = '1'")) {
        return List.of();
      }
      if (sql.contains("9bf61532d56eb7b5") && sql.contains("`isOut` = '1'")) {
        return List.of(new ApmQueryModels.ComponentTrendBucketPoint(
                bucket, "service-a", 18, 0, 4_000_000L, 6_000_000L, 1_000_000L, 0, 0));
      }
      return List.of();
    });

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> outbound = service.callGraphStats(Map.of(
            "componentType", "service.rpc",
            "serviceId", "5457a0119281bb98",
            "srcServiceId", "9bf61532d56eb7b5",
            "isOut", 1,
            "fromTime", "2026-06-06 00:21:00",
            "toTime", "2026-06-06 00:36:00"));

    verify(reader, times(2)).queryComponentTrendBuckets(anyString());
    assertThat(outbound).containsKey("callCnts");
    @SuppressWarnings("unchecked")
    Map<String, Number> callCnts = (Map<String, Number>) outbound.get("callCnts");
    assertThat(callCnts.get(String.valueOf(bucket * 1000L))).isEqualTo(18L);
  }

  @Test
  void rpcCallEndpointsUsesPairThenCallerOutboundMetrics() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryComponentEndpoints(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      assertThat(sql).contains("metric_service_rpc");
      if (sql.contains("`isOut` = '1'")
              && (sql.contains("5457a0119281bb98") || sql.contains("service-b"))
              && (sql.contains("9bf61532d56eb7b5") || sql.contains("service-a"))) {
        return List.of();
      }
      if (sql.contains("`isOut` = '1'") && (sql.contains("9bf61532d56eb7b5") || sql.contains("service-a"))) {
        return List.of(new ApmQueryModels.ComponentEndpointPoint(
                "9bf61532d56eb7b5", "service-a", "com.demo.OrderService/getOrder",
                java.util.Map.of("type", "dubbo"),
                18, 0, 45.0, 90_000_000.0, 0, 0, 0, 0, 0, 0));
      }
      return List.of();
    });

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> resp = service.callEndpoints(Map.of(
            "componentType", "service.rpc",
            "serviceId", "5457a0119281bb98",
            "srcServiceId", "9bf61532d56eb7b5",
            "fromTime", "2026-06-06 00:21:00",
            "toTime", "2026-06-06 00:36:00"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("data");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("reqOutCnt")).isEqualTo(18L);
  }

  @Test
  void filtersMiddlewareServicesByKind() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryServiceSummaries(anyString())).thenReturn(List.of(
            new ServiceSummaryPoint("mysql-order", null, 10, 0, 5_000_000, 0),
            new ServiceSummaryPoint("demo-order", null, 20, 0, 8_000_000, 0)));
    when(reader.queryDistinctCount(anyString())).thenReturn(2L);

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> result = service.middlewareList(Map.of(
            "kind", "db",
            "fromTime", "2026-06-01 11:00:00",
            "toTime", "2026-06-01 13:00:00",
            "offset", 0,
            "size", 20));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("data");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("serviceId")).isEqualTo("f844978e4f424f86");
  }

  @Test
  void dbListReturnsDbMetricsFromMetricServiceDb() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryMetaServices(anyString())).thenReturn(List.of(
            new MetaServicePoint(
                    "mysql-order-id", "mysql-order", "mysql-order", "db", null, "mysql",
                    null, null, "OTLP", null, null, null, Boolean.TRUE, null, null, null, null)));
    when(reader.queryDbServiceSummaries(anyString())).thenReturn(List.of(
            new DbServiceSummaryPoint("mysql-order", "mysql-order-id", "mysql", 120, 3, 8, 960_000_000, 16_000_000)));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> result = service.dbList(Map.of(
            "fromTime", "2026-06-01 11:00:00",
            "toTime", "2026-06-01 13:00:00",
            "offset", 0,
            "size", 20));

    assertThat(result.get("status")).isEqualTo(200);
    assertThat(result.get("total")).isEqualTo(1L);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("data");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("serviceId")).isEqualTo("mysql-order-id");
    assertThat(rows.get(0).get("callCnt")).isEqualTo(120L);
    assertThat(rows.get(0).get("slowCnt")).isEqualTo(8L);
    assertThat(rows.get(0).get("type")).isEqualTo("mysql");
  }

  @Test
  void dbListExcludesNonVirtualApplicationServicesFromMeta() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryMetaServices(anyString())).thenReturn(List.of(
            new MetaServicePoint(
                    "mysql-order-id", "[mysql]dogi", "[mysql]dogi", "db", null, "mysql",
                    null, null, "OTLP", null, null, null, Boolean.TRUE, null, null, null, null),
            new MetaServicePoint(
                    "service-h-id", "service-h", "service-h", "db", null, "web",
                    null, null, "OTLP", null, null, null, Boolean.FALSE, null, null, null, null)));
    when(reader.queryDbServiceSummaries(anyString())).thenReturn(List.of(
            new DbServiceSummaryPoint("[mysql]dogi", "mysql-order-id", "mysql", 120, 3, 8, 960_000_000, 16_000_000)));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> result = service.dbList(Map.of(
            "fromTime", "2026-06-01 11:00:00",
            "toTime", "2026-06-01 13:00:00",
            "offset", 0,
            "size", 20));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("data");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("name")).isEqualTo("[mysql]dogi");
  }

  @Test
  void mqListExcludesNonVirtualApplicationServicesFromMeta() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryMetaServices(anyString())).thenReturn(List.of(
            new MetaServicePoint(
                    "kafka-topic-id", "[kafka]orders", "[kafka]orders", "mq", null, "kafka",
                    null, null, "OTLP", null, null, null, Boolean.TRUE, null, null, null, null),
            new MetaServicePoint(
                    "service-j-id", "service-j", "service-j", "mq", null, "kafka",
                    null, null, "OTLP", null, null, null, Boolean.FALSE, null, null, null, null)));
    when(reader.queryDbServiceSummaries(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      if (sql.contains("srcService")) {
        return List.of(new DbServiceSummaryPoint("[kafka]orders", "kafka-topic-id", "kafka", 40, 1, 0, 200_000_000, 10_000_000));
      }
      return List.of(new DbServiceSummaryPoint("[kafka]orders", "kafka-topic-id", "kafka", 100, 2, 0, 500_000_000, 10_000_000));
    });

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> result = service.mqList(Map.of(
            "fromTime", "2026-06-01 11:00:00",
            "toTime", "2026-06-01 13:00:00",
            "offset", 0,
            "size", 20));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("data");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("name")).isEqualTo("[kafka]orders");
  }

  @Test
  void mqListReturnsProducerAndConsumerMetrics() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryMetaServices(anyString())).thenReturn(List.of(
            new MetaServicePoint(
                    "kafka-topic-id", "orders-topic", "orders-topic", "mq", null, "kafka",
                    null, null, "OTLP", null, null, null, Boolean.TRUE, null, null, null, null)));
    when(reader.queryDbServiceSummaries(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      if (sql.contains("srcService")) {
        return List.of(new DbServiceSummaryPoint("orders-topic", "kafka-topic-id", "kafka", 40, 1, 0, 200_000_000, 10_000_000));
      }
      return List.of(new DbServiceSummaryPoint("orders-topic", "kafka-topic-id", "kafka", 100, 2, 0, 500_000_000, 10_000_000));
    });

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> result = service.mqList(Map.of(
            "fromTime", "2026-06-01 11:00:00",
            "toTime", "2026-06-01 13:00:00",
            "offset", 0,
            "size", 20));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("data");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("reqInCallCnt")).isEqualTo(100L);
    assertThat(rows.get(0).get("reqOutCallCnt")).isEqualTo(40L);
  }

  @Test
  void cacheListReturnsRedisMetrics() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryMetaServices(anyString())).thenReturn(List.of(
            new MetaServicePoint(
                    "redis-id", "redis-cache", "redis-cache", "cache", null, "redis",
                    null, null, "OTLP", null, null, null, Boolean.TRUE, null, null, null, null)));
    when(reader.queryDbServiceSummaries(anyString())).thenReturn(List.of(
            new DbServiceSummaryPoint("redis-cache", "redis-id", "GET", 80, 0, 5, 160_000_000, 4_000_000)));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> result = service.cacheList(Map.of(
            "fromTime", "2026-06-01 11:00:00",
            "toTime", "2026-06-01 13:00:00",
            "offset", 0,
            "size", 20));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("data");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("callCnt")).isEqualTo(80L);
    assertThat(rows.get(0).get("service_type")).isEqualTo("cache");
  }

  @Test
  void remoteCallListExcludesDatabaseVirtualServicesFromRemoteMetrics() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryMetaServices(anyString())).thenReturn(List.of());
    when(reader.queryDbServiceSummaries(anyString())).thenReturn(List.of(
            new DbServiceSummaryPoint("[elasticsearch]es:9200", "es-id", "elasticsearch", 120, 0, 0, 600_000_000, 10_000_000),
            new DbServiceSummaryPoint("[remote]api.example.com:443", "remote-id", "http", 80, 1, 0, 320_000_000, 8_000_000)));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> result = service.remoteCallList(Map.of(
            "fromTime", "2026-06-01 11:00:00",
            "toTime", "2026-06-01 13:00:00",
            "offset", 0,
            "size", 20));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("data");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("name")).isEqualTo("[remote]api.example.com:443");
    assertThat(rows.get(0).get("avgLatency")).isEqualTo(4_000_000.0);
    assertThat(rows.get(0).get("p100Latency")).isEqualTo(8_000_000L);
    assertThat(rows.get(0).containsKey("p50Latency")).isFalse();
  }

  @Test
  void mergesLegacySelfOutboundPeerOntoSingleDownstreamCallee() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryDbDownstream(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      if (sql.contains("metric_service_http") && sql.contains("GROUP BY `service`")) {
        if (sql.contains("`isOut` = '1'")) {
          return List.of(new DbDownstreamPoint("a1b2c3d4e5f67890", "service-a", 29, 0, 100.0));
        }
        return List.of(new DbDownstreamPoint("b1c2d3e4f5a67890", "service-b", 29, 0, 80.0));
      }
      if (sql.contains("metric_service_db")) {
        return List.of();
      }
      return List.of();
    });
    when(reader.queryServiceSummaries(anyString())).thenReturn(List.of(
            new ServiceSummaryPoint("service-a", null, 29, 0, 900_000_000, 0)));
    when(reader.queryMetaServices(anyString())).thenReturn(List.of());

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> result = service.getServiceInstanceRelations(Map.of(
            "start", 1_780_652_100_000L,
            "end", 1_780_655_700_000L,
            "serviceId", "a1b2c3d4e5f67890"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> downflow = (List<Map<String, Object>>) result.get("downflowServiceStats");
    assertThat(downflow.stream().filter(row -> "service.http".equals(row.get("componentType"))).count())
            .isEqualTo(1);
    Map<String, Object> httpPeer = downflow.stream()
            .filter(row -> "service.http".equals(row.get("componentType")))
            .findFirst()
            .orElseThrow();
    assertThat(httpPeer.get("serviceId")).isEqualTo("b1c2d3e4f5a67890");
    assertThat(httpPeer.get("reqInCnt")).isEqualTo(29L);
    assertThat(httpPeer.get("reqOutCnt")).isEqualTo(29L);
  }

  @Test
  void computesDownstreamNetworkTimeFromSeparateInOutMetrics() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    mockServiceARelationPeers(reader, false);
    when(reader.queryServiceSummaries(anyString())).thenReturn(List.of(
            new ServiceSummaryPoint("service-a", null, 208, 0, 900_000_000, 0)));
    when(reader.queryMetaServices(anyString())).thenReturn(List.of());

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> result = service.getServiceInstanceRelations(Map.of(
            "start", 1_780_652_100_000L,
            "end", 1_780_655_700_000L,
            "serviceId", "a1b2c3d4e5f67890"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> downflow = (List<Map<String, Object>>) result.get("downflowServiceStats");
    Map<String, Object> httpPeer = downflow.stream()
            .filter(row -> "service.http".equals(row.get("componentType")))
            .findFirst()
            .orElseThrow();

    // out avg 7ms, in avg 5ms => network 2ms per request
    assertThat(httpPeer.get("reqOutCnt")).isEqualTo(180L);
    assertThat(httpPeer.get("reqInCnt")).isEqualTo(180L);
    assertThat(httpPeer.get("reqOutTime")).isEqualTo(1_260_000_000.0);
    assertThat(httpPeer.get("reqInTime")).isEqualTo(900_000_000.0);
  }

  @Test
  void separatesHttpDbAndRpcDownstreamForServiceRelations() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    mockServiceARelationPeers(reader, true);
    when(reader.queryServiceSummaries(anyString())).thenReturn(List.of(
            new ServiceSummaryPoint("service-a", null, 208, 0, 900_000_000, 0)));
    when(reader.queryMetaServices(anyString())).thenReturn(List.of());

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> result = service.getServiceInstanceRelations(Map.of(
            "start", 1_780_652_100_000L,
            "end", 1_780_655_700_000L,
            "serviceId", "a1b2c3d4e5f67890"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> downflow = (List<Map<String, Object>>) result.get("downflowServiceStats");

    assertThat(downflow.stream().filter(row -> "service.rpc".equals(row.get("componentType"))).count())
            .isEqualTo(1);
    assertThat(downflow.stream()
            .filter(row -> "service.rpc".equals(row.get("componentType")))
            .map(row -> row.get("serviceId"))
            .findFirst()
            .orElseThrow()).isEqualTo("b1c2d3e4f5a67890");
  }

  @Test
  void separatesHttpAndDbDownstreamForServiceRelations() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    mockServiceARelationPeers(reader, false);
    when(reader.queryServiceSummaries(anyString())).thenReturn(List.of(
            new ServiceSummaryPoint("service-a", null, 208, 0, 900_000_000, 0)));
    when(reader.queryMetaServices(anyString())).thenReturn(List.of());

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> result = service.getServiceInstanceRelations(Map.of(
            "start", 1_780_652_100_000L,
            "end", 1_780_655_700_000L,
            "serviceId", "a1b2c3d4e5f67890"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> downflow = (List<Map<String, Object>>) result.get("downflowServiceStats");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> names = (List<Map<String, Object>>) result.get("serviceId2Name");

    assertThat(downflow).hasSize(2);
    assertThat(downflow.stream().filter(row -> "service.http".equals(row.get("componentType"))).count())
            .isEqualTo(1);
    assertThat(downflow.stream().filter(row -> "service.db".equals(row.get("componentType"))).count())
            .isEqualTo(1);
    assertThat(downflow.stream()
            .filter(row -> "service.http".equals(row.get("componentType")))
            .map(row -> row.get("serviceId"))
            .findFirst()
            .orElseThrow()).isEqualTo("b1c2d3e4f5a67890");
    assertThat(names.stream()
            .filter(row -> "dad537de7e10e096".equals(row.get("serviceId")))
            .map(row -> row.get("serviceName"))
            .findFirst()
            .orElseThrow()).isEqualTo("mysql");
  }

  @Test
  void buildsServiceInstanceRelationsFromHttpPeers() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    mockDemoOrderHttpRpcPeers(reader);
    when(reader.queryServiceSummaries(anyString())).thenReturn(List.of(
            new ServiceSummaryPoint("demo-order", null, 30, 1, 900_000_000, 0)));
    when(reader.queryMetaServices(anyString())).thenReturn(List.of(
            MetaServicePoint.minimal("demo-order", "Order Service"),
            MetaServicePoint.minimal("demo-gateway", "Gateway"),
            MetaServicePoint.minimal("demo-pay", "Pay Service")));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> result = service.getServiceInstanceRelations(Map.of(
            "start", 1_780_652_100_000L,
            "end", 1_780_655_700_000L,
            "serviceId", "464a0a08964a061e"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> upflow = (List<Map<String, Object>>) result.get("upflowServiceStats");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> downflow = (List<Map<String, Object>>) result.get("downflowServiceStats");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> names = (List<Map<String, Object>>) result.get("serviceId2Name");

    assertThat(upflow).hasSize(1);
    assertThat(upflow.get(0).get("serviceId")).isEqualTo("9b8a9c29874af555");
    assertThat(upflow.get(0).get("reqOutCnt")).isEqualTo(20L);
    assertThat(upflow.get(0).get("reqInCnt")).isEqualTo(20L);
    assertThat(upflow.get(0).get("componentType")).isEqualTo("service.http");
    assertThat(downflow).hasSize(1);
    assertThat(downflow.get(0).get("serviceId")).isEqualTo("5531560ada6ec064");
    assertThat(downflow.get(0).get("componentType")).isEqualTo("service.http");
    assertThat(names).extracting(row -> row.get("serviceId"))
            .containsExactlyInAnyOrder("9b8a9c29874af555", "464a0a08964a061e", "5531560ada6ec064");
    assertThat(result.get("reqCnt")).isEqualTo(30.0 / 3600.0);
  }

  @Test
  void buildsVirtualDbUpstreamRelationsWithMatchingInOutCounts() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryDbDownstream(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      if (sql.contains("metric_service_db") && sql.contains("GROUP BY `srcService`")) {
        if (sql.contains("LOWER(COALESCE(`dbType`,'')) LIKE '%elastic%'")) {
          return List.of();
        }
        if (sql.contains("[mysql]demo_apm") || sql.contains("c72cc83a8831e407")) {
          if (sql.contains("`isIn` = '1'") || sql.contains("`isOut` = '1'")) {
            return List.of(
                    new DbDownstreamPoint("9bf61532d56eb7b5", "service-a", 47, 0, 2.0),
                    new DbDownstreamPoint("5457a0119281bb98", "service-b", 94, 0, 3.0));
          }
        }
      }
      return List.of();
    });
    when(reader.queryServiceSummaries(anyString())).thenReturn(List.of(
            new ServiceSummaryPoint("[mysql]demo_apm", "c72cc83a8831e407", 141, 0, 500_000_000, 0)));
    when(reader.queryMetaServices(anyString())).thenReturn(List.of(
            MetaServicePoint.minimal("c72cc83a8831e407", "[mysql]demo_apm"),
            MetaServicePoint.minimal("9bf61532d56eb7b5", "service-a"),
            MetaServicePoint.minimal("5457a0119281bb98", "service-b")));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> result = service.getServiceInstanceRelations(Map.of(
            "start", 1_780_652_100_000L,
            "end", 1_780_655_700_000L,
            "serviceId", "c72cc83a8831e407"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> upflow = (List<Map<String, Object>>) result.get("upflowServiceStats");
    List<Map<String, Object>> dbUpflow = upflow.stream()
            .filter(row -> "service.db".equals(row.get("componentType")))
            .toList();
    assertThat(dbUpflow).hasSize(2);
    Map<String, Object> serviceA = dbUpflow.stream()
            .filter(row -> "9bf61532d56eb7b5".equals(row.get("serviceId")))
            .findFirst()
            .orElseThrow();
    Map<String, Object> serviceB = dbUpflow.stream()
            .filter(row -> "5457a0119281bb98".equals(row.get("serviceId")))
            .findFirst()
            .orElseThrow();
    assertThat(serviceA.get("componentType")).isEqualTo("service.db");
    assertThat(serviceA.get("reqOutCnt")).isEqualTo(47L);
    assertThat(serviceA.get("reqInCnt")).isEqualTo(47L);
    assertThat(serviceB.get("reqOutCnt")).isEqualTo(94L);
    assertThat(serviceB.get("reqInCnt")).isEqualTo(94L);
  }

  @Test
  void buildsVirtualDbUpstreamRelationsByServiceName() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryDbDownstream(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      if (sql.contains("metric_service_db") && sql.contains("GROUP BY `srcService`")) {
        if (sql.contains("LOWER(COALESCE(`dbType`,'')) LIKE '%elastic%'")) {
          return List.of();
        }
        if (sql.contains("[mysql]demo_apm") || sql.contains("c72cc83a8831e407")) {
          if (sql.contains("`isIn` = '1'") || sql.contains("`isOut` = '1'")) {
            return List.of(
                    new DbDownstreamPoint("9bf61532d56eb7b5", "service-a", 47, 0, 2.0),
                    new DbDownstreamPoint("5457a0119281bb98", "service-b", 94, 0, 3.0));
          }
        }
      }
      return List.of();
    });
    when(reader.queryServiceSummaries(anyString())).thenReturn(List.of(
            new ServiceSummaryPoint("[mysql]demo_apm", "c72cc83a8831e407", 141, 0, 500_000_000, 0)));
    when(reader.queryMetaServices(anyString())).thenReturn(List.of(
            MetaServicePoint.minimal("c72cc83a8831e407", "[mysql]demo_apm"),
            MetaServicePoint.minimal("9bf61532d56eb7b5", "service-a"),
            MetaServicePoint.minimal("5457a0119281bb98", "service-b")));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> result = service.getServiceInstanceRelations(Map.of(
            "fromTime", "2026-06-05 21:45:00",
            "toTime", "2026-06-05 22:00:00",
            "serviceName", "[mysql]demo_apm"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> upflow = (List<Map<String, Object>>) result.get("upflowServiceStats");
    List<Map<String, Object>> dbUpflow = upflow.stream()
            .filter(row -> "service.db".equals(row.get("componentType")))
            .toList();
    assertThat(dbUpflow).hasSize(2);
    assertThat(dbUpflow).extracting(row -> row.get("serviceId"))
            .containsExactlyInAnyOrder("9bf61532d56eb7b5", "5457a0119281bb98");
  }

  @Test
  void buildsServiceInstanceRelationsFromTopologyWhenComponentPeersMissing() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    mockEmptyComponentPeerQueries(reader);
    when(reader.queryServiceFlow(anyString())).thenReturn(List.of(
            new ApmQueryModels.ServiceFlowEdge(
                    "demo-order", "demo-pay", 5, 0, 0, null, null)));
    when(reader.queryServiceSummaries(anyString())).thenReturn(List.of());

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> result = service.getServiceInstanceRelations(Map.of(
            "serviceId", PortalServiceIdResolver.normalize("demo-order"),
            "start", 1_780_652_100_000L,
            "end", 1_780_655_700_000L));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> downflow = (List<Map<String, Object>>) result.get("downflowServiceStats");
    assertThat(downflow).hasSize(1);
    assertThat(downflow.get(0).get("serviceId")).isEqualTo(PortalServiceIdResolver.normalize("demo-pay"));
    assertThat(result.get("reqCnt")).isEqualTo(0.0);
  }

  @Test
  void buildsRelationListForNextDirection() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryServiceFlow(anyString())).thenReturn(List.of(
            new ApmQueryModels.ServiceFlowEdge(
                    "demo-order", "demo-pay", 10, 1, 0, null, null)));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    Map<String, Object> result = service.relationList(Map.of(
            "serviceId", PortalServiceIdResolver.normalize("demo-order"),
            "direction", "next",
            "fromTime", "2026-06-01 11:00:00",
            "toTime", "2026-06-01 13:00:00"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> list = (List<Map<String, Object>>) result.get("list");
    assertThat(list).hasSize(1);
    assertThat(list.get(0).get("serviceId")).isEqualTo(PortalServiceIdResolver.normalize("demo-pay"));
  }

  @Test
  void buildsServiceInstanceListWithTimestampRange() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryServiceInstanceSummaries(anyString())).thenReturn(List.of(
            new ApmQueryModels.ServiceInstanceSummaryPoint(
                    "inst-a", "host-a", "host-a", 42L, "demo", "pod-a", null, null, "java")));
    when(reader.queryMetaServices(anyString())).thenReturn(List.of(
            MetaServicePoint.minimal("9bf61532d56eb7b5", "Service A")));
    when(reader.queryServiceSummaries(anyString())).thenReturn(List.of(
            new ServiceSummaryPoint("service-a", "9bf61532d56eb7b5", 42, 0, 1_000_000_000, 0)));
    when(reader.queryDistinctCount(anyString())).thenReturn(0L);
    when(reader.queryStringMap(anyString(), org.mockito.ArgumentMatchers.eq("map_key"), org.mockito.ArgumentMatchers.eq("map_value")))
            .thenReturn(Map.of());
    when(reader.queryIntMap(anyString(), org.mockito.ArgumentMatchers.eq("map_key"), org.mockito.ArgumentMatchers.eq("map_value")))
            .thenReturn(Map.of("service-a", 1));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    List<Map<String, Object>> rows = service.getServiceInstance(Map.of(
            "start", 1_780_652_100_000L,
            "end", 1_780_655_700_000L,
            "serviceId", "9bf61532d56eb7b5"));

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("serviceInstance")).isEqualTo("inst-a");
    assertThat(rows.get(0).get("serviceCall")).isEqualTo(42L);
    assertThat(rows.get(0).get("hostName")).isEqualTo("host-a");
    @SuppressWarnings("unchecked")
    Map<String, Object> entity = (Map<String, Object>) rows.get(0).get("traceServiceEntity");
    assertThat(entity.get("serviceId")).isEqualTo("9bf61532d56eb7b5");
    assertThat(entity.get("name")).isEqualTo("Service A");
  }

  @Test
  void buildsBasicAllServicesFromMetaTable() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryMetaServices(anyString())).thenReturn(List.of(
            ApmQueryModels.MetaServicePoint.minimal("svc-1", "Order Service"),
            ApmQueryModels.MetaServicePoint.minimal("svc-2", "")));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    List<Map<String, Object>> rows = service.basicAllServices(Map.of("ignoreTime", 1));

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).get("id")).isEqualTo("svc-1");
    assertThat(rows.get(0).get("name")).isEqualTo("Order Service");
    assertThat(rows.get(0).get("service")).isEqualTo("Order Service");
    assertThat(rows.get(0).get("service_type")).isEqualTo("web");
    assertThat(rows.get(1).get("id")).isEqualTo("svc-2");
    assertThat(rows.get(1).get("name")).isEqualTo("svc-2");
  }

  @Test
  void basicServicesIgnoresLiteralNullServiceNameFilter() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryMetaServices(anyString())).thenReturn(List.of(
            ApmQueryModels.MetaServicePoint.minimal("demo-order", "Demo Order")));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    List<Map<String, Object>> rows = service.basicServices(Map.of("ignoreTime", 1, "serviceName", "null"));

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("id")).isEqualTo("demo-order");
  }

  @Test
  void basicServicesWithIgnoreTimeUsesMetaTableOnly() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryMetaServices(anyString())).thenReturn(List.of(
            ApmQueryModels.MetaServicePoint.minimal("demo-order", "Demo Order")));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    List<Map<String, Object>> rows = service.basicServices(Map.of("ignoreTime", 1));

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("id")).isEqualTo("demo-order");
    assertThat(rows.get(0).get("name")).isEqualTo("Demo Order");
    verify(reader, never()).queryDistinctTags(anyString());
  }

  @Test
  void basicServicesWithoutIgnoreTimeUsesMetricDistinct() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryDistinctTags(anyString())).thenReturn(List.of("demo-order"));
    when(reader.queryMetaServices(anyString())).thenReturn(List.of(
            ApmQueryModels.MetaServicePoint.minimal("demo-order", "Demo Order")));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    List<Map<String, Object>> rows = service.basicServices(Map.of(
            "fromTime", "2026-06-04 11:00:00",
            "toTime", "2026-06-04 12:00:00",
            "ignoreTime", 0));

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("id")).isEqualTo("demo-order");
    assertThat(rows.get(0).get("name")).isEqualTo("Demo Order");
  }

  @Test
  void basicServicesWithIgnoreTimeReturnsEmptyWhenMetaEmpty() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryMetaServices(anyString())).thenReturn(List.of());

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    List<Map<String, Object>> rows = service.basicServices(Map.of("ignoreTime", 1));

    assertThat(rows).isEmpty();
    verify(reader, never()).queryDistinctTags(anyString());
  }

  @Test
  void basicServicesFallsBackToMetaWhenMetricsEmptyInWindow() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryDistinctTags(anyString())).thenReturn(List.of());
    when(reader.queryMetaServices(anyString())).thenReturn(List.of(
            ApmQueryModels.MetaServicePoint.minimal("demo-order", "Demo Order")));

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    List<Map<String, Object>> rows = service.basicServices(Map.of(
            "fromTime", "2026-06-04 11:00:00",
            "toTime", "2026-06-04 12:00:00",
            "ignoreTime", 0));

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("id")).isEqualTo("demo-order");
  }

  @Test
  void basicAllServicesReturnsEmptyWhenMetaEmpty() throws Exception {
    ApmReadRepository reader = mock(ApmReadRepository.class);
    when(reader.queryMetaServices(anyString())).thenReturn(List.of());

    ServicePortalService service = TestStorageSupport.servicePortalService(reader);
    List<Map<String, Object>> rows = service.basicAllServices(Map.of("ignoreTime", 1));

    assertThat(rows).isEmpty();
    verify(reader, never()).queryDistinctTags(anyString());
  }
}
