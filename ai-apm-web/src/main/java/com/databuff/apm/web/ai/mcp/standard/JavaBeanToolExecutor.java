package com.databuff.apm.web.ai.mcp.standard;

import com.databuff.apm.web.ai.platform.AiPlatformApiException;
import com.databuff.apm.web.tools.local.CommonTools;
import com.databuff.apm.web.tools.local.DataTools;
import com.databuff.apm.web.tools.local.InspectTools;
import com.databuff.apm.web.tools.local.LogTools;
import com.databuff.apm.web.tools.local.MetricQueryRequest;
import com.databuff.apm.web.tools.local.PlatformTools;
import com.databuff.apm.web.tools.local.TimeTool;
import com.databuff.apm.web.tools.local.TrendChartSpec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class JavaBeanToolExecutor {

    private final CommonTools commonTools;
    private final DataTools dataTools;
    private final InspectTools inspectTools;
    private final LogTools logTools;
    private final TimeTool timeTool;
    private final PlatformTools platformTools;
    private final ObjectMapper objectMapper;

    public JavaBeanToolExecutor(
            CommonTools commonTools,
            DataTools dataTools,
            InspectTools inspectTools,
            LogTools logTools,
            TimeTool timeTool,
            PlatformTools platformTools,
            ObjectMapper objectMapper) {
        this.commonTools = commonTools;
        this.dataTools = dataTools;
        this.inspectTools = inspectTools;
        this.logTools = logTools;
        this.timeTool = timeTool;
        this.platformTools = platformTools;
        this.objectMapper = objectMapper;
    }

    public String invoke(String implementation, TestToolRequest request) {
        TestToolRequest safeRequest = request == null ? TestToolRequest.empty() : request;
        return switch (implementation) {
            case "commonTools.getCurrentTimeRange" -> toJson(commonTools.getCurrentTimeRange(safeRequest.rangeMinutes()));
            case "commonTools.getTimeRangeAroundTime" -> toJson(commonTools.getTimeRangeAroundTime(safeRequest.targetTime()));
            case "commonTools.drawTrendCharts" -> commonTools.drawTrendCharts(safeRequest.charts());
            case "timeTool.getCurrentTimeRange" -> toJson(timeTool.getCurrentTimeRange(safeRequest.rangeMinutes()));
            case "timeTool.getTimeRangeAroundTime" -> toJson(timeTool.getTimeRangeAroundTime(safeRequest.targetTime()));
            case "dataTools.queryServicesAll" -> dataTools.queryServicesAll(
                    safeRequest.keyword(), safeRequest.fromTime(), safeRequest.toTime());
            case "dataTools.queryServicesByServiceType" -> dataTools.queryServicesByServiceType(
                    firstNonBlank(safeRequest.serviceType(), safeRequest.service()),
                    safeRequest.keyword(),
                    safeRequest.size(),
                    safeRequest.fromTime(),
                    safeRequest.toTime());
            case "dataTools.queryServiceTopology" -> dataTools.queryServiceTopology(
                    safeRequest.serviceName(),
                    safeRequest.serviceInstance(),
                    safeRequest.fromTime(),
                    safeRequest.toTime());
            case "dataTools.queryTraceListByCondition" -> dataTools.queryTraceListByCondition(
                    safeRequest.srcServiceId(),
                    firstNonBlank(safeRequest.serviceId(), safeRequest.service()),
                    safeRequest.componentType(),
                    safeRequest.resource(),
                    safeRequest.direction(),
                    safeRequest.fromTime(),
                    safeRequest.toTime(),
                    safeRequest.size());
            case "dataTools.queryTraceDetail" -> dataTools.queryTraceDetail(safeRequest.traceId());
            case "dataTools.queryServiceAlarms" -> dataTools.queryServiceAlarms(
                    firstNonBlank(safeRequest.serviceId(), safeRequest.service()),
                    safeRequest.status(),
                    safeRequest.fromTime(),
                    safeRequest.toTime());
            case "dataTools.queryMetricData" -> dataTools.queryMetricData(
                    safeRequest.queryRequests(),
                    safeRequest.size());
            case "logTools.queryLogTrend" -> logTools.queryLogTrend(
                    safeRequest.fromTime(),
                    safeRequest.toTime(),
                    safeRequest.services(),
                    safeRequest.serviceIds(),
                    safeRequest.serviceInstances(),
                    safeRequest.severities(),
                    safeRequest.query(),
                    safeRequest.interval());
            case "logTools.queryLogDetail" -> logTools.queryLogDetail(
                    safeRequest.fromTime(),
                    safeRequest.toTime(),
                    safeRequest.services(),
                    safeRequest.serviceIds(),
                    safeRequest.serviceInstances(),
                    safeRequest.severities(),
                    safeRequest.query(),
                    safeRequest.offset(),
                    safeRequest.size());
            case "logTools.queryLogsByTraceId" -> logTools.queryLogsByTraceId(
                    safeRequest.traceId(),
                    safeRequest.severities(),
                    safeRequest.query(),
                    safeRequest.fromTime(),
                    safeRequest.toTime(),
                    safeRequest.offset(),
                    safeRequest.size());
            case "logTools.queryLogsBySpanId" -> logTools.queryLogsBySpanId(
                    safeRequest.spanId(),
                    safeRequest.traceId(),
                    safeRequest.severities(),
                    safeRequest.query(),
                    safeRequest.fromTime(),
                    safeRequest.toTime(),
                    safeRequest.offset(),
                    safeRequest.size());
            case "inspectTools.inspectService" -> inspectTools.inspectService(
                    firstNonBlank(safeRequest.serviceName(), safeRequest.service()));
            case "platformTools.queryDorisBusinessData" ->
                    platformTools.queryDorisBusinessData(safeRequest.sql(), safeRequest.maxRows());
            case "platformTools.querySelfMonitorMetrics" ->
                    platformTools.querySelfMonitorMetrics(
                    safeRequest.fromTime(),
                    safeRequest.toTime(),
                    safeRequest.mode(),
                    safeRequest.metrics(),
                    safeRequest.metricPrefixes(),
                    safeRequest.metricSuffixes(),
                    safeRequest.components(),
                    safeRequest.instances(),
                    safeRequest.dims(),
                    splitCsv(safeRequest.groupBy()),
                    firstNonBlank(safeRequest.value(), safeRequest.valueMode()),
                    safeRequest.stepSeconds(),
                    safeRequest.tag(),
                    firstNonNull(safeRequest.limit(), safeRequest.size()));
            default -> throw AiPlatformApiException.badRequest("unsupported implementation: " + implementation);
        };
    }

    public TestToolRequest fromArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return TestToolRequest.empty();
        }
        return objectMapper.convertValue(arguments, TestToolRequest.class);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw AiPlatformApiException.badRequest("failed to serialize tool output: " + ex.getMessage());
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private static Integer firstNonNull(Integer first, Integer second) {
        return first != null ? first : second;
    }

    private static List<String> splitCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (!text.contains(",")) {
            return List.of(text);
        }
        return java.util.Arrays.stream(text.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public record TestToolRequest(
            String service,
            String serviceType,
            String serviceId,
            String serviceName,
            String serviceInstance,
            String srcServiceId,
            String traceId,
            String spanId,
            String keyword,
            String query,
            String componentType,
            String resource,
            String direction,
            String metric,
            String measurement,
            String field,
            String tagsJson,
            List<MetricQueryRequest> queryRequests,
            List<String> services,
            List<String> serviceIds,
            List<String> serviceInstances,
            List<String> severities,
            String fromTime,
            String toTime,
            String queryType,
            String groupBy,
            String targetTime,
            Integer interval,
            Integer size,
            Integer offset,
            Integer status,
            Integer rangeMinutes,
            List<TrendChartSpec> charts,
            String sql,
            Integer maxRows,
            String mode,
            List<String> metrics,
            List<String> metricPrefixes,
            List<String> metricSuffixes,
            List<String> components,
            List<String> instances,
            List<String> dims,
            String value,
            String valueMode,
            Integer stepSeconds,
            String tag,
            Integer limit) {

        public static TestToolRequest empty() {
            return new TestToolRequest(
                    null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null,
                    null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null,
                    null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null);
        }
    }
}
