package com.databuff.apm.common.storage;

import com.databuff.apm.common.query.ApmQueryModels;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApmReadRepositoryQueryTest {

    @Test
    void parsesCallSpanCount() throws Exception {
        ApmReadRepository reader = reader(rs -> {
            when(rs.next()).thenReturn(true, false);
            when(rs.getLong("total_cnt")).thenReturn(42L);
        });
        assertThat(reader.queryCallSpanCount("select 1")).isEqualTo(42L);
    }

    @Test
    void parsesCallSpansWithNullableHttpStatus() throws Exception {
        ApmReadRepository reader = reader(rs -> {
            when(rs.next()).thenReturn(true, false);
            stubCallSpanRow(rs);
            when(rs.getInt("meta_http_status_code")).thenReturn(0);
            when(rs.wasNull()).thenReturn(true);
        });
        ApmQueryModels.CallSpanRow row = reader.queryCallSpans("select 1").get(0);
        assertThat(row.metaHttpStatusCode()).isNull();
        assertThat(row.service()).isEqualTo("checkout");
    }

    @Test
    void parsesServiceSummaries() throws Exception {
        ApmReadRepository reader = reader(rs -> {
            when(rs.next()).thenReturn(true, false);
            when(rs.getString("service")).thenReturn("checkout");
            when(rs.getString("service_id")).thenReturn("co-id");
            when(rs.getLong("request_cnt")).thenReturn(10L);
            when(rs.getLong("error_cnt")).thenReturn(1L);
            when(rs.getDouble("sum_duration_ns")).thenReturn(1000.0);
            when(rs.getDouble("max_duration_ns")).thenReturn(200.0);
        });
        assertThat(reader.queryServiceSummaries("select 1").get(0).serviceId()).isEqualTo("co-id");
    }

    @Test
    void parsesDbServiceSummaries() throws Exception {
        ApmReadRepository reader = reader(rs -> {
            when(rs.next()).thenReturn(true, false);
            when(rs.getString("service")).thenReturn("[mysql]db");
            when(rs.getString("service_id")).thenReturn("db-id");
            when(rs.getString("db_type")).thenReturn("mysql");
            when(rs.getLong("request_cnt")).thenReturn(5L);
            when(rs.getLong("error_cnt")).thenReturn(0L);
            when(rs.getLong("slow_cnt")).thenReturn(2L);
            when(rs.getDouble("sum_duration_ns")).thenReturn(500.0);
        });
        assertThat(reader.queryDbServiceSummaries("select 1").get(0).slowCount()).isEqualTo(2L);
    }

    @Test
    void parsesDbServiceSummaryMaxDuration() throws Exception {
        ApmReadRepository reader = reader(rs -> {
            when(rs.next()).thenReturn(true, false);
            when(rs.getString("service")).thenReturn("[mysql]db");
            when(rs.getString("service_id")).thenReturn("db-id");
            when(rs.getString("db_type")).thenReturn("mysql");
            when(rs.getLong("request_cnt")).thenReturn(5L);
            when(rs.getLong("error_cnt")).thenReturn(0L);
            when(rs.getLong("slow_cnt")).thenReturn(2L);
            when(rs.getDouble("sum_duration_ns")).thenReturn(500.0);
            when(rs.getDouble("max_duration_ns")).thenReturn(1000.0);
        });
        assertThat(reader.queryDbServiceSummaries("select 1").get(0).maxDurationNs()).isEqualTo(1000.0);
    }

    @Test
    void parsesDistinctCount() throws Exception {
        ApmReadRepository reader = reader(rs -> when(rs.next()).thenReturn(false));
        assertThat(reader.queryDistinctCount("select 1")).isZero();
    }

    @Test
    void parsesServiceTrendBuckets() throws Exception {
        ApmReadRepository reader = reader(rs -> {
            when(rs.next()).thenReturn(true, false);
            when(rs.getLong("bucket_epoch_sec")).thenReturn(1_700_000_000L);
            when(rs.getString("service")).thenReturn("svc");
            when(rs.getLong("request_cnt")).thenReturn(3L);
            when(rs.getLong("error_cnt")).thenReturn(1L);
            when(rs.getDouble("sum_duration_ns")).thenReturn(99.0);
        });
        assertThat(reader.queryServiceTrendBuckets("select 1").get(0).bucketEpochSec()).isEqualTo(1_700_000_000L);
    }

    @Test
    void parsesComponentTrendBucketsWithSlowCountColumn() throws Exception {
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(2);
        when(meta.getColumnLabel(1)).thenReturn("bucket_epoch_sec");
        when(meta.getColumnLabel(2)).thenReturn("slow_cnt");
        ApmReadRepository reader = reader(rs -> {
            when(rs.getMetaData()).thenReturn(meta);
            when(rs.next()).thenReturn(true, false);
            when(rs.getLong("bucket_epoch_sec")).thenReturn(100L);
            when(rs.getString("service")).thenReturn("svc");
            when(rs.getLong("request_cnt")).thenReturn(1L);
            when(rs.getLong("error_cnt")).thenReturn(0L);
            when(rs.getDouble("sum_duration_ns")).thenReturn(1.0);
            when(rs.getDouble("max_duration_ns")).thenReturn(2.0);
            when(rs.getDouble("min_duration_ns")).thenReturn(0.5);
            when(rs.getDouble("sum_read_rows")).thenReturn(10.0);
            when(rs.getDouble("sum_update_rows")).thenReturn(2.0);
            when(rs.getLong("slow_cnt")).thenReturn(4L);
        });
        assertThat(reader.queryComponentTrendBuckets("select 1").get(0).slowCount()).isEqualTo(4L);
    }

    @Test
    void parsesComponentTrendBucketsWithoutSlowCountColumn() throws Exception {
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("bucket_epoch_sec");
        ApmReadRepository reader = reader(rs -> {
            when(rs.getMetaData()).thenReturn(meta);
            when(rs.next()).thenReturn(true, false);
            when(rs.getLong("bucket_epoch_sec")).thenReturn(100L);
            when(rs.getString("service")).thenReturn("svc");
            when(rs.getLong("request_cnt")).thenReturn(1L);
            when(rs.getLong("error_cnt")).thenReturn(0L);
            when(rs.getDouble("sum_duration_ns")).thenReturn(1.0);
            when(rs.getDouble("max_duration_ns")).thenReturn(2.0);
            when(rs.getDouble("min_duration_ns")).thenReturn(0.5);
            when(rs.getDouble("sum_read_rows")).thenReturn(10.0);
            when(rs.getDouble("sum_update_rows")).thenReturn(2.0);
        });
        assertThat(reader.queryComponentTrendBuckets("select 1").get(0).slowCount()).isZero();
    }

    @Test
    void parsesMetricScalar() throws Exception {
        ApmReadRepository reader = reader(rs -> {
            when(rs.next()).thenReturn(true);
            when(rs.getDouble("metric_value")).thenReturn(12.5);
        });
        assertThat(reader.queryMetricScalar("select 1")).isEqualTo(12.5);
    }

    @Test
    void parsesServiceFlowEntryPoints() throws Exception {
        ApmReadRepository reader = reader(rs -> {
            when(rs.next()).thenReturn(true, false);
            when(rs.getString("service")).thenReturn("gateway");
            when(rs.getString("service_id")).thenReturn("gw");
            when(rs.getString("entry_path_id")).thenReturn("path-1");
        });
        assertThat(reader.queryServiceFlowEntryPoints("select 1").get(0).entrypointPathId()).isEqualTo("path-1");
    }

    @Test
    void parsesDistinctStringsSkipsBlankValues() throws Exception {
        ApmReadRepository reader = reader(rs -> {
            when(rs.next()).thenReturn(true, true, true, false);
            when(rs.getString("name")).thenReturn("a", " ", null);
        });
        assertThat(reader.queryDistinctStrings("select 1", "name")).containsExactly("a");
    }

    @Test
    void parsesServiceFlowTreeRowsAndIsInFlag() throws Exception {
        ApmReadRepository reader = reader(rs -> {
            when(rs.next()).thenReturn(true, true, false);
            when(rs.getString("path_id")).thenReturn("p1", "p2");
            when(rs.getString("parent_path_id")).thenReturn("0", "p1");
            when(rs.getString("service")).thenReturn("gw", "checkout");
            when(rs.getString("service_id")).thenReturn("gw-id", "co-id");
            when(rs.getString("resource")).thenReturn("/api", "POST /pay");
            when(rs.getString("is_in")).thenReturn("1", "0");
            when(rs.getLong("call_cnt")).thenReturn(5L, 3L);
            when(rs.getLong("error_cnt")).thenReturn(0L, 1L);
            when(rs.getLong("src_call")).thenReturn(1L, 0L);
            when(rs.getLong("sum_duration")).thenReturn(100L, 50L);
        });
        List<ApmQueryModels.ServiceFlowTreeRow> rows = reader.queryServiceFlowTreeRows("select 1");
        assertThat(rows.get(0).isIn()).isOne();
        assertThat(rows.get(1).isIn()).isZero();
    }

    @Test
    void parsesDbDownstream() throws Exception {
        ApmReadRepository reader = reader(rs -> {
            when(rs.next()).thenReturn(true, false);
            when(rs.getString("service_id")).thenReturn("db-id");
            when(rs.getString("service")).thenReturn("[mysql]db");
            when(rs.getLong("request_cnt")).thenReturn(8L);
            when(rs.getLong("error_cnt")).thenReturn(1L);
            when(rs.getDouble("avg_duration")).thenReturn(3.3);
        });
        assertThat(reader.queryDbDownstream("select 1").get(0).service()).contains("mysql");
    }

    @Test
    void parsesDbEndpoints() throws Exception {
        ApmReadRepository reader = reader(rs -> {
            when(rs.next()).thenReturn(true, false);
            when(rs.getString("service_id")).thenReturn("db-id");
            when(rs.getString("service")).thenReturn("[mysql]db");
            when(rs.getString("resource")).thenReturn("SELECT 1");
            when(rs.getString("sqlOperation")).thenReturn("SELECT");
            when(rs.getString("dbType")).thenReturn("mysql");
            when(rs.getString("sqlDatabase")).thenReturn("app");
            when(rs.getLong("request_cnt")).thenReturn(8L);
            when(rs.getLong("error_cnt")).thenReturn(1L);
            when(rs.getDouble("avg_duration")).thenReturn(3.3);
            when(rs.getDouble("sum_read_rows")).thenReturn(100.0);
            when(rs.getDouble("sum_update_rows")).thenReturn(0.0);
        });
        assertThat(reader.queryDbEndpoints("select 1").get(0).sqlDatabase()).isEqualTo("app");
    }

    @Test
    void parsesDbSlowSqlTop() throws Exception {
        ApmReadRepository reader = reader(rs -> {
            when(rs.next()).thenReturn(true, false);
            when(rs.getString("resource")).thenReturn("SELECT * FROM orders");
            when(rs.getLong("request_cnt")).thenReturn(2L);
            when(rs.getLong("error_cnt")).thenReturn(0L);
            when(rs.getDouble("avg_time_ns")).thenReturn(1.5);
            when(rs.getDouble("max_duration_ns")).thenReturn(3.0);
            when(rs.getDouble("min_duration_ns")).thenReturn(1.0);
            when(rs.getLong("src_service_cnt")).thenReturn(4L);
        });
        assertThat(reader.queryDbSlowSqlTop("select 1").get(0).srcServiceCnt()).isEqualTo(4L);
    }

    @Test
    void parsesComponentEndpointsWithOptionalTagColumns() throws Exception {
        ApmReadRepository reader = reader(rs -> {
            when(rs.next()).thenReturn(true, false);
            when(rs.getString("service_id")).thenReturn("es-id");
            when(rs.getString("service")).thenReturn("[elasticsearch]es");
            when(rs.getString("resource")).thenReturn("search");
            when(rs.getString("type")).thenReturn("http");
            when(rs.getString("statusCode")).thenReturn("200");
            doThrow(new SQLException("missing")).when(rs).getString("command");
            when(rs.getLong("request_cnt")).thenReturn(1L);
            when(rs.getLong("error_cnt")).thenReturn(0L);
            when(rs.getDouble("avg_duration")).thenReturn(1.0);
            when(rs.getDouble("sum_read_rows")).thenReturn(0.0);
            when(rs.getDouble("sum_update_rows")).thenReturn(0.0);
            when(rs.getDouble("sum_req_body_length")).thenReturn(10.0);
            when(rs.getDouble("sum_resp_body_length")).thenReturn(20.0);
            when(rs.getDouble("sum_delay")).thenReturn(0.0);
            when(rs.getDouble("sum_mq_body_length")).thenReturn(0.0);
        });
        ApmQueryModels.ComponentEndpointPoint point = reader.queryComponentEndpoints("select 1").get(0);
        assertThat(point.tags()).containsEntry("type", "http").containsEntry("statusCode", "200");
    }

    @Test
    void componentCallStatsEmptyWhenNoRows() throws Exception {
        ApmReadRepository reader = reader(rs -> when(rs.next()).thenReturn(false));
        assertThat(reader.queryComponentCallStats("select 1").requestCount()).isZero();
    }

    @Test
    void parsesComponentResourceRelations() throws Exception {
        ApmReadRepository reader = reader(rs -> {
            when(rs.next()).thenReturn(true, false);
            when(rs.getString("service_id")).thenReturn("svc-id");
            when(rs.getString("service")).thenReturn("checkout");
            when(rs.getString("url")).thenReturn("/pay");
            when(rs.getString("srcServiceId")).thenReturn("gw-id");
            when(rs.getString("srcService")).thenReturn("gateway");
            when(rs.getString("rootResource")).thenReturn("POST /pay");
            when(rs.getString("rootComponentType")).thenReturn("http");
            when(rs.getLong("all_cnt")).thenReturn(10L);
            when(rs.getLong("slow_cnt")).thenReturn(2L);
            when(rs.getLong("err_cnt")).thenReturn(1L);
            when(rs.getDouble("avg_time_ns")).thenReturn(5.0);
            when(rs.getDouble("max_time_ns")).thenReturn(9.0);
        });
        assertThat(reader.queryComponentResourceRelations("select 1", List.of(
                "service_id", "service", "url", "srcServiceId", "srcService", "rootResource", "rootComponentType"))
                .get(0).resource()).isEqualTo("/pay");
    }

    @Test
    void parsesExceptionListAndCount() throws Exception {
        ApmReadRepository reader = reader(rs -> {
            when(rs.next()).thenReturn(true, false);
            when(rs.getLong("ts")).thenReturn(1L);
            when(rs.getString("resource")).thenReturn("GET /");
            when(rs.getString("exceptionName")).thenReturn("NPE");
            when(rs.getString("service")).thenReturn("svc");
            when(rs.getString("service_id")).thenReturn("svc-id");
            when(rs.getString("service_instance")).thenReturn("inst");
            when(rs.getString("rootResource")).thenReturn("root");
            when(rs.getLong("err_cnt")).thenReturn(3L);
        });
        assertThat(reader.queryExceptionList("select 1").get(0).exceptionName()).isEqualTo("NPE");
        ApmReadRepository countReader = reader(rs -> {
            when(rs.next()).thenReturn(true);
            when(rs.getLong("total_cnt")).thenReturn(9L);
        });
        assertThat(countReader.queryExceptionListCount("select 1")).isEqualTo(9L);
    }

    @Test
    void parsesExceptionDistByServiceId() throws Exception {
        assertThat(distRow("serviceId").serviceId()).isEqualTo("svc-id");
    }

    @Test
    void parsesExceptionDistByServiceInstance() throws Exception {
        assertThat(distRow("serviceInstance").serviceInstance()).isEqualTo("inst");
    }

    @Test
    void parsesExceptionDistByResource() throws Exception {
        assertThat(distRow("resource").resource()).isEqualTo("/api");
    }

    @Test
    void parsesExceptionDistByCompositeGroup() throws Exception {
        assertThat(distRow("serviceId,serviceInstance").serviceInstance()).isEqualTo("inst");
    }

    @Test
    void parsesExceptionDistByExceptionName() throws Exception {
        assertThat(distRow("exceptionName").exceptionName()).isEqualTo("Timeout");
    }

    private ApmQueryModels.ExceptionDistPoint distRow(String groupBy) throws Exception {
        ApmReadRepository reader = reader(rs -> {
            when(rs.next()).thenReturn(true, false);
            when(rs.getLong("err_cnt")).thenReturn(5L);
            when(rs.getString("service_id")).thenReturn("svc-id");
            when(rs.getString("service_instance")).thenReturn("inst");
            when(rs.getString("resource")).thenReturn("/api");
            when(rs.getString("exception_name")).thenReturn("Timeout");
        });
        return reader.queryExceptionDist("select 1", groupBy).get(0);
    }

    @Test
    void parsesDistinctTags() throws Exception {
        ApmReadRepository reader = reader(rs -> {
            when(rs.next()).thenReturn(true, true, false);
            when(rs.getString("tag_value")).thenReturn("prod", " ");
        });
        assertThat(reader.queryDistinctTags("select 1")).containsExactly("prod");
    }

    @Test
    void parsesTopGroups() throws Exception {
        ApmReadRepository reader = reader(rs -> {
            when(rs.next()).thenReturn(true, true, false);
            when(rs.getString("group_value")).thenReturn("g1", "g2");
        });
        assertThat(reader.queryTopGroups("select 1")).containsExactly("g1", "g2");
    }

    @Test
    void parsesDistinctSrcServicesSkipsBlank() throws Exception {
        ApmReadRepository reader = reader(rs -> {
            when(rs.next()).thenReturn(true, true, false);
            when(rs.getString("srcService")).thenReturn("gateway", " ");
            when(rs.getString("srcServiceId")).thenReturn("gw-id", "x");
        });
        List<Map<String, String>> rows = reader.queryDistinctSrcServices("select 1");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("srcService")).isEqualTo("gateway");
    }

    @Test
    void parsesMetaServicesAndBooleanVirtualFlag() throws Exception {
        ApmReadRepository reader = reader(rs -> {
            when(rs.next()).thenReturn(true, true, false);
            when(rs.getString("id")).thenReturn(" ", "svc-id");
            when(rs.getString("name")).thenReturn("ignored", "Checkout");
            when(rs.getString("service")).thenReturn("ignored", "checkout");
            when(rs.getString("service_type")).thenReturn("web");
            when(rs.getString("apikey")).thenReturn("key");
            when(rs.getString("type")).thenReturn("java");
            when(rs.getString("technology")).thenReturn("jvm");
            when(rs.getString("language")).thenReturn("java");
            when(rs.getString("datasource")).thenReturn("OTLP");
            when(rs.getString("source")).thenReturn("k8s");
            when(rs.getString("fqdn")).thenReturn("host");
            when(rs.getString("container_service")).thenReturn("pod");
            when(rs.getObject("virtual_service")).thenReturn(1);
            when(rs.getString("describe")).thenReturn("desc");
            when(rs.getString("custom_tags")).thenReturn("{}");
            when(rs.getString("processRuntimeName")).thenReturn("OpenJDK");
            when(rs.getString("processRuntimeVersion")).thenReturn("17");
        });
        ApmQueryModels.MetaServicePoint point = reader.queryMetaServices("select 1").get(0);
        assertThat(point.id()).isEqualTo("svc-id");
        assertThat(point.virtualService()).isTrue();
    }

    @Test
    void parsesMetricSeriesWithNullValue() throws Exception {
        ApmReadRepository reader = reader(rs -> {
            when(rs.next()).thenReturn(true, false);
            when(rs.getLong("epoch_sec")).thenReturn(100L);
            when(rs.getDouble("metric_value")).thenReturn(0.0);
            when(rs.wasNull()).thenReturn(true);
        });
        assertThat(reader.queryMetricSeries("select 1").get(0).value()).isNull();
    }

    @Test
    void parsesServiceInstanceSummaries() throws Exception {
        ApmReadRepository reader = reader(rs -> {
            when(rs.next()).thenReturn(true, false);
            when(rs.getString("service_instance")).thenReturn("inst-1");
            when(rs.getString("host_name")).thenReturn("host");
            when(rs.getString("host_id")).thenReturn("hid");
            when(rs.getLong("call_cnt")).thenReturn(7L);
            when(rs.getString("k8s_namespace")).thenReturn("prod");
            when(rs.getString("k8s_pod_name")).thenReturn("pod");
            when(rs.getString("k8s_cluster_id")).thenReturn("cluster");
            when(rs.getString("container_id")).thenReturn("cid");
            when(rs.getString("process_name")).thenReturn("java");
        });
        assertThat(reader.queryServiceInstanceSummaries("select 1").get(0).k8sPodName()).isEqualTo("pod");
    }

    @Test
    void parsesStringMap() throws Exception {
        ApmReadRepository reader = reader(rs -> {
            when(rs.next()).thenReturn(true, true, false);
            when(rs.getString("k")).thenReturn("a", " ");
            when(rs.getString("v")).thenReturn("1", "2");
        });
        assertThat(reader.queryStringMap("select 1", "k", "v")).containsEntry("a", "1");
    }

    @Test
    void parsesIntMap() throws Exception {
        ApmReadRepository reader = reader(rs -> {
            when(rs.next()).thenReturn(true, true, false);
            when(rs.getString("k")).thenReturn("a", "b");
            when(rs.getInt("iv")).thenReturn(10, 0);
            when(rs.wasNull()).thenReturn(false, true);
        });
        assertThat(reader.queryIntMap("select 1", "k", "iv")).containsEntry("a", 10).doesNotContainKey("b");
    }

    @Test
    void parsesQueryRowsWithRowLimit() throws Exception {
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(2);
        when(meta.getColumnLabel(1)).thenReturn("id");
        when(meta.getColumnLabel(2)).thenReturn("name");
        ApmReadRepository reader = reader(rs -> {
            when(rs.getMetaData()).thenReturn(meta);
            when(rs.next()).thenReturn(true, true, false);
            when(rs.getObject(1)).thenReturn(1, 2);
            when(rs.getObject(2)).thenReturn("a", "b");
        });
        List<Map<String, Object>> rows = reader.queryRows("select 1", 1);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("id", 1).containsEntry("name", "a");
    }

    @Test
    void closesAutoCloseableDataSource() throws Exception {
        DataSource dataSource = mock(DataSource.class, org.mockito.Mockito.withSettings().extraInterfaces(AutoCloseable.class));
        ApmReadRepository reader = new ApmReadRepository(dataSource);
        reader.close();
        org.mockito.Mockito.verify((AutoCloseable) dataSource).close();
    }

    @Test
    void parsesSpanDetailsWithExtendedFields() throws Exception {
        ApmReadRepository reader = reader(rs -> {
            when(rs.next()).thenReturn(true, false);
            when(rs.getString("trace_id")).thenReturn("t1");
            when(rs.getString("span_id")).thenReturn("s1");
            when(rs.getString("parent_id")).thenReturn("0");
            when(rs.getString("service")).thenReturn("checkout");
            when(rs.getString("service_id")).thenReturn("co");
            when(rs.getString("name")).thenReturn("GET /");
            when(rs.getString("startTime")).thenReturn("2026-06-01 12:00:00");
            when(rs.getLong("start")).thenReturn(100L);
            when(rs.getLong("duration")).thenReturn(50L);
            when(rs.getInt("error")).thenReturn(1);
            when(rs.getString("hostName")).thenReturn("host");
            when(rs.getString("serviceInstance")).thenReturn("inst");
            when(rs.getString("resource")).thenReturn("/api");
            when(rs.getString("type")).thenReturn("http");
            when(rs.getInt("isIn")).thenReturn(1);
            when(rs.getInt("isOut")).thenReturn(0);
            when(rs.getString("meta")).thenReturn("{}");
            when(rs.getString("metrics")).thenReturn("{}");
            when(rs.getInt("meta_http_status_code")).thenReturn(500);
            when(rs.wasNull()).thenReturn(false);
            when(rs.getString("meta_http_method")).thenReturn("GET");
            when(rs.getString("meta_http_url")).thenReturn("/api");
            when(rs.getString("meta_error_type")).thenReturn("ServerError");
        });
        assertThat(reader.querySpanDetails("select 1").get(0).type()).isEqualTo("http");
    }

    private static void stubCallSpanRow(ResultSet rs) throws SQLException {
        when(rs.getString("trace_id")).thenReturn("t1");
        when(rs.getString("span_id")).thenReturn("s1");
        when(rs.getString("parent_id")).thenReturn("0");
        when(rs.getLong("start")).thenReturn(1L);
        when(rs.getLong("end")).thenReturn(2L);
        when(rs.getString("resource")).thenReturn("/api");
        when(rs.getLong("duration")).thenReturn(99L);
        when(rs.getInt("error")).thenReturn(0);
        when(rs.getInt("slow")).thenReturn(0);
        when(rs.getString("service")).thenReturn("checkout");
        when(rs.getString("service_id")).thenReturn("co-id");
        when(rs.getString("serviceInstance")).thenReturn("inst");
        when(rs.getString("srcService")).thenReturn(null);
        when(rs.getString("srcServiceId")).thenReturn(null);
        when(rs.getString("srcServiceInstance")).thenReturn(null);
        when(rs.getString("dstService")).thenReturn(null);
        when(rs.getString("dstServiceId")).thenReturn(null);
        when(rs.getString("dstServiceInstance")).thenReturn(null);
        when(rs.getInt("isIn")).thenReturn(0);
        when(rs.getInt("isOut")).thenReturn(1);
        when(rs.getString("name")).thenReturn("GET /");
        when(rs.getString("meta")).thenReturn("{}");
        when(rs.getString("metrics")).thenReturn("{}");
        when(rs.getString("meta_http_method")).thenReturn("GET");
        when(rs.getString("meta_http_url")).thenReturn("/api");
        when(rs.getString("meta_error_type")).thenReturn(null);
    }

    private static ApmReadRepository reader(ResultSetStub stub) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        stub.accept(rs);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rs);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        return new ApmReadRepository(dataSource);
    }

    @FunctionalInterface
    private interface ResultSetStub {
        void accept(ResultSet rs) throws Exception;
    }
}
