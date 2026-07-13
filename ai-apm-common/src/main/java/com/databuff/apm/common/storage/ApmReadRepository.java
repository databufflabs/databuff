package com.databuff.apm.common.storage;

import com.databuff.apm.common.query.ApmQueryModels;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Read-only JDBC access for web/query APIs (Doris-backed today, swappable later). */
public class ApmReadRepository implements AutoCloseable {

    private final DataSource dataSource;
    private final DorisAccessGate gate;

    public ApmReadRepository(DataSource dataSource) {
        this(dataSource, DorisAccessGate.alwaysAvailable());
    }

    public ApmReadRepository(DataSource dataSource, DorisAccessGate gate) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.gate = Objects.requireNonNull(gate);
    }

    /** Borrow a pooled connection; caller must close it (try-with-resources). */
    public Connection connection() throws SQLException {
        gate.beforeConnection();
        return dataSource.getConnection();
    }

    public List<ApmQueryModels.TrafficLightPoint> queryTrafficLight(String sql) throws SQLException {
        List<ApmQueryModels.TrafficLightPoint> points = new ArrayList<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                points.add(new ApmQueryModels.TrafficLightPoint(
                        rs.getString("ts"),
                        rs.getString("service"),
                        rs.getLong("error_cnt"),
                        rs.getLong("total_cnt")));
            }
        }
        return points;
    }

    public List<ApmQueryModels.SpanSummary> querySpanSummaries(String sql) throws SQLException {
        List<ApmQueryModels.SpanSummary> spans = new ArrayList<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                spans.add(new ApmQueryModels.SpanSummary(
                        rs.getString("trace_id"),
                        rs.getString("span_id"),
                        rs.getString("service"),
                        rs.getString("service_id"),
                        rs.getString("name"),
                        rs.getString("startTime"),
                        rs.getLong("duration"),
                        rs.getInt("error"),
                        nullToEmpty(rs.getString("serviceInstance")),
                        nullToEmpty(rs.getString("resource")),
                        nullToEmpty(rs.getString("hostName")),
                        readNullableInt(rs, "meta_http_status_code"),
                        rs.getString("meta_error_type"),
                        rs.getString("parent_id"),
                        readNullableInt(rs, "is_parent"),
                        nullToEmpty(rs.getString("meta_http_url"))));
            }
        }
        return spans;
    }

    public List<ApmQueryModels.SpanDetail> querySpanDetails(String sql) throws SQLException {
        List<ApmQueryModels.SpanDetail> spans = new ArrayList<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                spans.add(new ApmQueryModels.SpanDetail(
                        rs.getString("trace_id"),
                        rs.getString("span_id"),
                        rs.getString("parent_id"),
                        rs.getString("service"),
                        rs.getString("service_id"),
                        rs.getString("name"),
                        rs.getString("startTime"),
                        rs.getLong("start"),
                        rs.getLong("duration"),
                        rs.getInt("error"),
                        rs.getString("hostName"),
                        nullToEmpty(rs.getString("serviceInstance")),
                        nullToEmpty(rs.getString("resource")),
                        nullToEmpty(rs.getString("type")),
                        rs.getInt("isIn"),
                        rs.getInt("isOut"),
                        rs.getString("meta"),
                        rs.getString("metrics"),
                        readNullableInt(rs, "meta_http_status_code"),
                        rs.getString("meta_http_method"),
                        rs.getString("meta_http_url"),
                        rs.getString("meta_error_type")));
            }
        }
        return spans;
    }

    public long queryCallSpanCount(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) {
                return 0L;
            }
            return rs.getLong("total_cnt");
        }
    }

    public List<ApmQueryModels.CallSpanRow> queryCallSpans(String sql) throws SQLException {
        List<ApmQueryModels.CallSpanRow> spans = new ArrayList<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                spans.add(mapCallSpanRow(rs));
            }
        }
        return spans;
    }

    private static ApmQueryModels.CallSpanRow mapCallSpanRow(ResultSet rs) throws SQLException {
        return new ApmQueryModels.CallSpanRow(
                rs.getString("trace_id"),
                rs.getString("span_id"),
                rs.getString("parent_id"),
                rs.getLong("start"),
                rs.getLong("end"),
                nullToEmpty(rs.getString("resource")),
                rs.getLong("duration"),
                rs.getInt("error"),
                rs.getInt("slow"),
                nullToEmpty(rs.getString("service")),
                nullToEmpty(rs.getString("service_id")),
                nullToEmpty(rs.getString("serviceInstance")),
                nullToEmpty(rs.getString("srcService")),
                nullToEmpty(rs.getString("srcServiceId")),
                nullToEmpty(rs.getString("srcServiceInstance")),
                nullToEmpty(rs.getString("dstService")),
                nullToEmpty(rs.getString("dstServiceId")),
                nullToEmpty(rs.getString("dstServiceInstance")),
                rs.getInt("isIn"),
                rs.getInt("isOut"),
                nullToEmpty(rs.getString("name")),
                rs.getString("meta"),
                rs.getString("metrics"),
                readNullableInt(rs, "meta_http_status_code"),
                rs.getString("meta_http_method"),
                rs.getString("meta_http_url"),
                rs.getString("meta_error_type"));
    }

    public List<ApmQueryModels.ServiceMetricPoint> queryServiceMetrics(String sql) throws SQLException {
        List<ApmQueryModels.ServiceMetricPoint> points = new ArrayList<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                points.add(new ApmQueryModels.ServiceMetricPoint(
                        rs.getString("ts"),
                        rs.getString("service"),
                        rs.getLong("request_cnt"),
                        rs.getLong("error_cnt"),
                        rs.getDouble("avg_duration")));
            }
        }
        return points;
    }

    public List<ApmQueryModels.ServiceSummaryPoint> queryServiceSummaries(String sql) throws SQLException {
        List<ApmQueryModels.ServiceSummaryPoint> points = new ArrayList<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                points.add(new ApmQueryModels.ServiceSummaryPoint(
                        rs.getString("service"),
                        rs.getString("service_id"),
                        rs.getLong("request_cnt"),
                        rs.getLong("error_cnt"),
                        rs.getDouble("sum_duration_ns"),
                        rs.getDouble("max_duration_ns")));
            }
        }
        return points;
    }

    public List<ApmQueryModels.DbServiceSummaryPoint> queryDbServiceSummaries(String sql) throws SQLException {
        List<ApmQueryModels.DbServiceSummaryPoint> points = new ArrayList<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                points.add(new ApmQueryModels.DbServiceSummaryPoint(
                        rs.getString("service"),
                        rs.getString("service_id"),
                        rs.getString("db_type"),
                        rs.getLong("request_cnt"),
                        rs.getLong("error_cnt"),
                        rs.getLong("slow_cnt"),
                        rs.getDouble("sum_duration_ns")));
            }
        }
        return points;
    }

    public long queryDistinctCount(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) {
                return 0;
            }
            return rs.getLong("total_cnt");
        }
    }

    public List<ApmQueryModels.ServiceTrendBucketPoint> queryServiceTrendBuckets(String sql) throws SQLException {
        List<ApmQueryModels.ServiceTrendBucketPoint> points = new ArrayList<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                points.add(new ApmQueryModels.ServiceTrendBucketPoint(
                        rs.getLong("bucket_epoch_sec"),
                        rs.getString("service"),
                        rs.getLong("request_cnt"),
                        rs.getLong("error_cnt"),
                        rs.getDouble("sum_duration_ns")));
            }
        }
        return points;
    }

    public List<ApmQueryModels.ComponentTrendBucketPoint> queryComponentTrendBuckets(String sql) throws SQLException {
        List<ApmQueryModels.ComponentTrendBucketPoint> points = new ArrayList<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            java.sql.ResultSetMetaData meta = rs.getMetaData();
            boolean hasSlowCount = false;
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                if ("slow_cnt".equalsIgnoreCase(meta.getColumnLabel(i))) {
                    hasSlowCount = true;
                    break;
                }
            }
            while (rs.next()) {
                long slowCount = hasSlowCount ? rs.getLong("slow_cnt") : 0L;
                points.add(new ApmQueryModels.ComponentTrendBucketPoint(
                        rs.getLong("bucket_epoch_sec"),
                        rs.getString("service"),
                        rs.getLong("request_cnt"),
                        rs.getLong("error_cnt"),
                        rs.getDouble("sum_duration_ns"),
                        rs.getDouble("max_duration_ns"),
                        rs.getDouble("min_duration_ns"),
                        rs.getDouble("sum_read_rows"),
                        rs.getDouble("sum_update_rows"),
                        slowCount));
            }
        }
        return points;
    }

    public ApmQueryModels.ErrorRateSnapshot queryErrorRate(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) {
                return new ApmQueryModels.ErrorRateSnapshot(0, 0);
            }
            return new ApmQueryModels.ErrorRateSnapshot(rs.getLong("error_cnt"), rs.getLong("total_cnt"));
        }
    }

    public long queryRequestCount(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) {
                return 0;
            }
            return rs.getLong("total_cnt");
        }
    }

    public double queryMetricScalar(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) {
                return 0;
            }
            return rs.getDouble("metric_value");
        }
    }

    public List<ApmQueryModels.TopologyEdge> queryTopologyEdges(String sql) throws SQLException {
        List<ApmQueryModels.TopologyEdge> edges = new ArrayList<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                edges.add(new ApmQueryModels.TopologyEdge(
                        rs.getString("srcService"),
                        rs.getString("dstService"),
                        rs.getLong("call_cnt"),
                        rs.getLong("error_cnt")));
            }
        }
        return edges;
    }

    public List<ApmQueryModels.ServiceFlowEdge> queryServiceFlow(String sql) throws SQLException {
        List<ApmQueryModels.ServiceFlowEdge> edges = new ArrayList<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                edges.add(new ApmQueryModels.ServiceFlowEdge(
                        rs.getString("src_service"),
                        rs.getString("dst_service"),
                        rs.getLong("call_cnt"),
                        rs.getLong("error_cnt"),
                        rs.getDouble("avg_duration"),
                        rs.getString("src_service_id"),
                        rs.getString("dst_service_id")));
            }
        }
        return edges;
    }

    public List<ApmQueryModels.ServiceFlowEntryPoint> queryServiceFlowEntryPoints(String sql) throws SQLException {
        List<ApmQueryModels.ServiceFlowEntryPoint> points = new ArrayList<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                points.add(new ApmQueryModels.ServiceFlowEntryPoint(
                        rs.getString("service"),
                        rs.getString("service_id"),
                        rs.getString("entry_path_id")));
            }
        }
        return points;
    }

    public List<String> queryDistinctStrings(String sql, String column) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                String value = rs.getString(column);
                if (value != null && !value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    public List<ApmQueryModels.ServiceFlowTreeRow> queryServiceFlowTreeRows(String sql) throws SQLException {
        List<ApmQueryModels.ServiceFlowTreeRow> rows = new ArrayList<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(new ApmQueryModels.ServiceFlowTreeRow(
                        rs.getString("path_id"),
                        rs.getString("parent_path_id"),
                        rs.getString("service"),
                        rs.getString("service_id"),
                        rs.getString("resource"),
                        parseIsIn(rs.getString("is_in")),
                        rs.getLong("call_cnt"),
                        rs.getLong("error_cnt"),
                        rs.getLong("src_call"),
                        rs.getLong("sum_duration")));
            }
        }
        return rows;
    }

    private static int parseIsIn(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return "1".equals(value.trim()) ? 1 : 0;
    }

    public List<ApmQueryModels.HttpEndpointPoint> queryHttpEndpoints(String sql) throws SQLException {
        List<ApmQueryModels.HttpEndpointPoint> points = new ArrayList<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                points.add(new ApmQueryModels.HttpEndpointPoint(
                        rs.getString("service_id"),
                        rs.getString("service"),
                        rs.getString("url"),
                        rs.getString("httpMethod"),
                        rs.getString("httpCode"),
                        rs.getLong("request_cnt"),
                        rs.getLong("error_cnt"),
                        rs.getDouble("avg_duration")));
            }
        }
        return points;
    }

    public List<ApmQueryModels.DbDownstreamPoint> queryDbDownstream(String sql) throws SQLException {
        List<ApmQueryModels.DbDownstreamPoint> points = new ArrayList<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                points.add(new ApmQueryModels.DbDownstreamPoint(
                        rs.getString("service_id"),
                        rs.getString("service"),
                        rs.getLong("request_cnt"),
                        rs.getLong("error_cnt"),
                        rs.getDouble("avg_duration")));
            }
        }
        return points;
    }

    public List<ApmQueryModels.DbEndpointPoint> queryDbEndpoints(String sql) throws SQLException {
        List<ApmQueryModels.DbEndpointPoint> points = new ArrayList<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                points.add(new ApmQueryModels.DbEndpointPoint(
                        rs.getString("service_id"),
                        rs.getString("service"),
                        rs.getString("resource"),
                        rs.getString("sqlOperation"),
                        rs.getString("dbType"),
                        rs.getString("sqlDatabase"),
                        rs.getLong("request_cnt"),
                        rs.getLong("error_cnt"),
                        rs.getDouble("avg_duration"),
                        rs.getDouble("sum_read_rows"),
                        rs.getDouble("sum_update_rows")));
            }
        }
        return points;
    }

    public List<ApmQueryModels.DbSlowSqlTopPoint> queryDbSlowSqlTop(String sql) throws SQLException {
        List<ApmQueryModels.DbSlowSqlTopPoint> points = new ArrayList<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                points.add(new ApmQueryModels.DbSlowSqlTopPoint(
                        rs.getString("resource"),
                        rs.getLong("request_cnt"),
                        rs.getLong("error_cnt"),
                        rs.getDouble("avg_time_ns"),
                        rs.getDouble("max_duration_ns"),
                        rs.getDouble("min_duration_ns"),
                        rs.getLong("src_service_cnt")));
            }
        }
        return points;
    }

    public List<ApmQueryModels.ComponentEndpointPoint> queryComponentEndpoints(
            String sql) throws SQLException {
        List<ApmQueryModels.ComponentEndpointPoint> points = new ArrayList<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            java.util.Set<String> tagColumns = java.util.Set.of(
                    "type", "statusCode", "command", "topic", "group", "partition", "broker",
                    "indices", "method", "url", "operation", "config_type", "remoteType");
            while (rs.next()) {
                java.util.Map<String, String> tags = new java.util.LinkedHashMap<>();
                for (String column : tagColumns) {
                    try {
                        String value = rs.getString(column);
                        if (value != null && !value.isBlank()) {
                            tags.put(column, value);
                        }
                    } catch (SQLException ignored) {
                        // column not present in this query
                    }
                }
                points.add(new ApmQueryModels.ComponentEndpointPoint(
                        rs.getString("service_id"),
                        rs.getString("service"),
                        rs.getString("resource"),
                        tags,
                        rs.getLong("request_cnt"),
                        rs.getLong("error_cnt"),
                        rs.getDouble("avg_duration"),
                        rs.getDouble("sum_read_rows"),
                        rs.getDouble("sum_update_rows"),
                        rs.getDouble("sum_req_body_length"),
                        rs.getDouble("sum_resp_body_length"),
                        rs.getDouble("sum_delay"),
                        rs.getDouble("sum_mq_body_length")));
            }
        }
        return points;
    }

    public ApmQueryModels.ComponentCallStatsPoint queryComponentCallStats(
            String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) {
                return ApmQueryModels.ComponentCallStatsPoint.empty();
            }
            return new ApmQueryModels.ComponentCallStatsPoint(
                    rs.getLong("request_cnt"),
                    rs.getLong("error_cnt"),
                    rs.getDouble("sum_duration_ns"));
        }
    }

    public List<ApmQueryModels.ComponentResourceRelationPoint> queryComponentResourceRelations(
            String sql,
            java.util.List<String> groupByColumns) throws SQLException {
        List<ApmQueryModels.ComponentResourceRelationPoint> points = new ArrayList<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                String serviceId = null;
                String service = null;
                String resource = null;
                String srcServiceId = null;
                String srcService = null;
                String rootResource = null;
                String rootComponentType = null;
                for (String column : groupByColumns) {
                    String value = rs.getString(column);
                    switch (column) {
                        case "service_id" -> serviceId = value;
                        case "service" -> service = value;
                        case "url", "resource", "sqlContent" -> resource = value;
                        case "srcServiceId" -> srcServiceId = value;
                        case "srcService" -> srcService = value;
                        case "rootResource" -> rootResource = value;
                        case "rootComponentType" -> rootComponentType = value;
                        default -> {
                        }
                    }
                }
                points.add(new ApmQueryModels.ComponentResourceRelationPoint(
                        serviceId,
                        service,
                        resource,
                        srcServiceId,
                        srcService,
                        rootResource,
                        rootComponentType,
                        rs.getLong("all_cnt"),
                        rs.getLong("slow_cnt"),
                        rs.getLong("err_cnt"),
                        rs.getDouble("avg_time_ns"),
                        rs.getDouble("max_time_ns")));
            }
        }
        return points;
    }

    public List<ApmQueryModels.HttpLatencyBucketPoint> queryHttpLatencyBuckets(String sql) throws SQLException {
        List<ApmQueryModels.HttpLatencyBucketPoint> points = new ArrayList<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                points.add(new ApmQueryModels.HttpLatencyBucketPoint(
                        rs.getString("durationRange"),
                        rs.getLong("request_cnt"),
                        rs.getLong("error_cnt")));
            }
        }
        return points;
    }

    public List<ApmQueryModels.ExceptionListPoint> queryExceptionList(String sql) throws SQLException {
        List<ApmQueryModels.ExceptionListPoint> points = new ArrayList<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                points.add(new ApmQueryModels.ExceptionListPoint(
                        rs.getLong("ts"),
                        rs.getString("resource"),
                        rs.getString("exceptionName"),
                        rs.getString("service"),
                        rs.getString("service_id"),
                        rs.getString("service_instance"),
                        rs.getString("rootResource"),
                        rs.getLong("err_cnt")));
            }
        }
        return points;
    }

    public long queryExceptionListCount(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getLong("total_cnt");
            }
        }
        return 0L;
    }

    public List<ApmQueryModels.ExceptionDistPoint> queryExceptionDist(String sql, String groupBy)
            throws SQLException {
        List<ApmQueryModels.ExceptionDistPoint> points = new ArrayList<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                long errCnt = rs.getLong("err_cnt");
                String exceptionName = null;
                String serviceId = null;
                String serviceInstance = null;
                String resource = null;
                switch (groupBy) {
                    case "serviceId" -> serviceId = rs.getString("service_id");
                    case "serviceInstance" -> serviceInstance = rs.getString("service_instance");
                    case "resource", "rootResource" -> resource = rs.getString("resource");
                    case "serviceId,serviceInstance" -> {
                        serviceId = rs.getString("service_id");
                        serviceInstance = rs.getString("service_instance");
                    }
                    default -> exceptionName = rs.getString("exception_name");
                }
                points.add(new ApmQueryModels.ExceptionDistPoint(
                        exceptionName, serviceId, serviceInstance, resource, resource, errCnt));
            }
        }
        return points;
    }

    public List<String> queryDistinctTags(String sql) throws SQLException {
        List<String> tags = new ArrayList<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                String value = rs.getString("tag_value");
                if (value != null && !value.isBlank()) {
                    tags.add(value);
                }
            }
        }
        return tags;
    }

    public List<Map<String, String>> queryDistinctSrcServices(String sql) throws SQLException {
        List<Map<String, String>> rows = new ArrayList<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                String srcService = nullToEmpty(rs.getString("srcService"));
                if (srcService.isBlank()) {
                    continue;
                }
                rows.add(Map.of(
                        "srcService", srcService,
                        "srcServiceId", nullToEmpty(rs.getString("srcServiceId"))));
            }
        }
        return rows;
    }

    public List<ApmQueryModels.MetaServicePoint> queryMetaServices(String sql) throws SQLException {
        List<ApmQueryModels.MetaServicePoint> rows = new ArrayList<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                String id = rs.getString("id");
                if (id == null || id.isBlank()) {
                    continue;
                }
                rows.add(new ApmQueryModels.MetaServicePoint(
                        id,
                        rs.getString("name"),
                        rs.getString("service"),
                        rs.getString("service_type"),
                        rs.getString("apikey"),
                        rs.getString("type"),
                        rs.getString("technology"),
                        rs.getString("language"),
                        rs.getString("datasource"),
                        rs.getString("source"),
                        rs.getString("fqdn"),
                        rs.getString("container_service"),
                        readBoolean(rs, "virtual_service"),
                        rs.getString("describe"),
                        rs.getString("custom_tags"),
                        rs.getString("processRuntimeName"),
                        rs.getString("processRuntimeVersion")));
            }
        }
        return rows;
    }

    private static Boolean readBoolean(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(value.toString());
    }

    public List<ApmQueryModels.MetricSeriesPoint> queryMetricSeries(String sql) throws SQLException {
        List<ApmQueryModels.MetricSeriesPoint> points = new ArrayList<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                double metricValue = rs.getDouble("metric_value");
                points.add(new ApmQueryModels.MetricSeriesPoint(
                        rs.getLong("epoch_sec"),
                        rs.wasNull() ? null : metricValue));
            }
        }
        return points;
    }

    public List<String> queryTopGroups(String sql) throws SQLException {
        List<String> groups = new ArrayList<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                String value = rs.getString("group_value");
                if (value != null && !value.isBlank()) {
                    groups.add(value);
                }
            }
        }
        return groups;
    }

    public List<ApmQueryModels.ServiceInstanceSummaryPoint> queryServiceInstanceSummaries(String sql) throws SQLException {
        List<ApmQueryModels.ServiceInstanceSummaryPoint> points = new ArrayList<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                points.add(new ApmQueryModels.ServiceInstanceSummaryPoint(
                        nullToEmpty(rs.getString("service_instance")),
                        nullToEmpty(rs.getString("host_name")),
                        nullToEmpty(rs.getString("host_id")),
                        rs.getLong("call_cnt"),
                        rs.getString("k8s_namespace"),
                        rs.getString("k8s_pod_name"),
                        rs.getString("k8s_cluster_id"),
                        rs.getString("container_id"),
                        rs.getString("process_name")));
            }
        }
        return points;
    }

    public Map<String, String> queryStringMap(String sql, String keyColumn, String valueColumn) throws SQLException {
        Map<String, String> map = new LinkedHashMap<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                String key = rs.getString(keyColumn);
                String value = rs.getString(valueColumn);
                if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                    map.putIfAbsent(key, value);
                }
            }
        }
        return map;
    }

    public Map<String, Integer> queryIntMap(String sql, String keyColumn, String valueColumn) throws SQLException {
        Map<String, Integer> map = new LinkedHashMap<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                String key = rs.getString(keyColumn);
                int value = rs.getInt(valueColumn);
                if (key != null && !key.isBlank() && !rs.wasNull()) {
                    map.put(key, value);
                }
            }
        }
        return map;
    }

    public List<Map<String, Object>> queryRows(String sql, int maxRows) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        int limit = Math.max(1, Math.min(maxRows, 1000));
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            java.sql.ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();
            while (rs.next() && rows.size() < limit) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.put(meta.getColumnLabel(i), rs.getObject(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    @Override
    public void close() throws SQLException {
        if (dataSource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                throw new SQLException("Failed to close data source", e);
            }
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static Integer readNullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
