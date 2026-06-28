package com.databuff.apm.web.ai.platform.api;

import com.databuff.apm.web.ai.platform.AiPlatformApiException;
import com.databuff.apm.web.ai.platform.expert.ExpertManagementService;
import com.databuff.apm.web.ai.platform.tool.AiToolDefinition;
import com.databuff.apm.web.ai.platform.tool.JavaBeanToolAllowlist;
import com.databuff.apm.web.ai.platform.tool.ToolManagementService;
import com.databuff.apm.web.ai.platform.tool.ToolType;
import com.databuff.apm.web.tools.local.CommonTools;
import com.databuff.apm.web.tools.local.DataTools;
import com.databuff.apm.web.tools.local.InspectTools;
import com.databuff.apm.web.tools.local.TimeTool;
import com.databuff.apm.web.tools.local.MetricQueryRequest;
import com.databuff.apm.web.tools.local.TrendChartSpec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ai/tools")
public class AiToolController {

    @Autowired
    private ToolManagementService toolManagementService;
    @Autowired
    private ExpertManagementService expertManagementService;
    @Autowired
    private CommonTools commonTools;
    @Autowired
    private DataTools dataTools;
    @Autowired
    private InspectTools inspectTools;
    @Autowired
    private TimeTool timeTool;
    @Autowired
    private ObjectMapper objectMapper;

    @GetMapping
    public List<AiToolDefinition> list() {
        return toolManagementService.list();
    }

    @GetMapping("/{toolId}")
    public AiToolDefinition get(@PathVariable String toolId) {
        return toolManagementService.find(toolId)
                .orElseThrow(() -> AiPlatformApiException.notFound("tool", toolId));
    }

    @GetMapping("/{toolId}/references")
    public Map<String, Object> references(@PathVariable String toolId) {
        toolManagementService.find(toolId)
                .orElseThrow(() -> AiPlatformApiException.notFound("tool", toolId));
        List<String> expertIds = expertManagementService.listExpertIdsReferencingTool(toolId);
        return Map.of("toolId", toolId, "expertIds", expertIds);
    }

    @PostMapping
    public AiToolDefinition create(@RequestBody SaveToolRequest request) {
        if (request == null || blank(request.toolId())) {
            throw AiPlatformApiException.badRequest("toolId is required");
        }
        if (toolManagementService.find(request.toolId()).isPresent()) {
            throw AiPlatformApiException.conflict("tool_exists", "tool already exists: " + request.toolId());
        }
        return toolManagementService.save(toDefinition(request, Instant.now()));
    }

    @PutMapping("/{toolId}")
    public AiToolDefinition update(@PathVariable String toolId, @RequestBody SaveToolRequest request) {
        AiToolDefinition existing = toolManagementService.find(toolId)
                .orElseThrow(() -> AiPlatformApiException.notFound("tool", toolId));
        SaveToolRequest merged = request == null ? new SaveToolRequest(
                toolId, existing.name(), existing.category(), existing.description(), existing.type(),
                existing.implementation(), existing.schemaJson(), existing.configJson(), existing.enabled())
                : request.withToolId(toolId);
        if (existing.builtIn()) {
            merged = merged.withImplementation(existing.implementation());
        }
        return toolManagementService.save(toDefinition(merged, existing.createdAt()));
    }

    @DeleteMapping("/{toolId}")
    public Map<String, Boolean> delete(@PathVariable String toolId) {
        ensureNotReferenced(toolId);
        if (!toolManagementService.delete(toolId)) {
            throw AiPlatformApiException.conflict("tool_protected", "built-in tool cannot be deleted: " + toolId);
        }
        return Map.of("deleted", true);
    }

    @PostMapping("/{toolId}/enable")
    public AiToolDefinition enable(@PathVariable String toolId) {
        return setEnabled(toolId, true);
    }

    @PostMapping("/{toolId}/disable")
    public AiToolDefinition disable(@PathVariable String toolId) {
        return setEnabled(toolId, false);
    }

    @PostMapping("/{toolId}/test")
    public Map<String, Object> test(@PathVariable String toolId, @RequestBody(required = false) TestToolRequest request) {
        AiToolDefinition tool = toolManagementService.find(toolId)
                .orElseThrow(() -> AiPlatformApiException.notFound("tool", toolId));
        if (tool.type() != ToolType.JAVA_BEAN) {
            throw AiPlatformApiException.badRequest("only JAVA_BEAN tools can be tested in this release");
        }
        TestToolRequest safeRequest = request == null ? TestToolRequest.empty() : request;
        String output = invokeJavaBeanTool(tool.implementation(), safeRequest);
        return Map.of("toolId", toolId, "ok", true, "output", output);
    }

    private AiToolDefinition setEnabled(String toolId, boolean enabled) {
        AiToolDefinition existing = toolManagementService.find(toolId)
                .orElseThrow(() -> AiPlatformApiException.notFound("tool", toolId));
        return toolManagementService.save(new AiToolDefinition(
                existing.toolId(), existing.name(), existing.category(), existing.description(), existing.type(),
                existing.implementation(), existing.schemaJson(), existing.configJson(),
                enabled, existing.builtIn(), existing.version(), existing.createdAt(), Instant.now()));
    }

    private void ensureNotReferenced(String toolId) {
        boolean referenced = expertManagementService.list().stream()
                .anyMatch(expert -> expert.toolIds().contains(toolId));
        if (referenced) {
            throw AiPlatformApiException.conflict(
                    "tool_in_use", "tool is referenced by one or more experts: " + toolId);
        }
    }

    private AiToolDefinition toDefinition(SaveToolRequest request, Instant createdAt) {
        ToolType type = request.type() == null ? ToolType.JAVA_BEAN : request.type();
        if (type == ToolType.JAVA_BEAN && !JavaBeanToolAllowlist.isAllowed(request.implementation())) {
            throw AiPlatformApiException.badRequest(
                    "JAVA_BEAN implementation must be allowlisted: " + JavaBeanToolAllowlist.implementations());
        }
        Instant now = Instant.now();
        return new AiToolDefinition(
                request.toolId(),
                request.name(),
                normalizeCategory(request.category()),
                request.description(),
                type,
                request.implementation(),
                request.schemaJson() == null ? "{}" : request.schemaJson(),
                request.configJson() == null ? "{}" : request.configJson(),
                request.enabled() == null || request.enabled(),
                false,
                0,
                createdAt == null ? now : createdAt,
                now);
    }

    private String invokeJavaBeanTool(String implementation, TestToolRequest request) {
        return switch (implementation) {
            case "commonTools.getCurrentTimeRange" -> toJson(commonTools.getCurrentTimeRange(request.rangeMinutes()));
            case "commonTools.getTimeRangeAroundTime" -> toJson(commonTools.getTimeRangeAroundTime(request.targetTime()));
            case "commonTools.drawTrendCharts" -> commonTools.drawTrendCharts(request.charts());
            case "timeTool.getCurrentTimeRange" -> toJson(timeTool.getCurrentTimeRange(request.rangeMinutes()));
            case "timeTool.getTimeRangeAroundTime" -> toJson(timeTool.getTimeRangeAroundTime(request.targetTime()));
            case "dataTools.queryServicesAll" -> dataTools.queryServicesAll(
                    request.keyword(), request.fromTime(), request.toTime());
            case "dataTools.queryServicesByServiceType" -> dataTools.queryServicesByServiceType(
                    firstNonBlank(request.serviceType(), request.service()),
                    request.keyword(),
                    request.size(),
                    request.fromTime(),
                    request.toTime());
            case "dataTools.queryServiceTopology" -> dataTools.queryServiceTopology(
                    request.serviceName(),
                    request.serviceInstance(),
                    request.fromTime(),
                    request.toTime());
            case "dataTools.queryTraceListByCondition" -> dataTools.queryTraceListByCondition(
                    request.srcServiceId(),
                    firstNonBlank(request.serviceId(), request.service()),
                    request.componentType(),
                    request.resource(),
                    request.direction(),
                    request.fromTime(),
                    request.toTime(),
                    request.size());
            case "dataTools.queryTraceDetail" -> dataTools.queryTraceDetail(request.traceId());
            case "dataTools.queryServiceAlarms" -> dataTools.queryServiceAlarms(
                    firstNonBlank(request.serviceId(), request.service()), request.status(),
                    request.fromTime(), request.toTime());
            case "dataTools.queryMetricData" -> dataTools.queryMetricData(
                    request.queryRequests(),
                    request.size());
            case "inspectTools.inspectService" -> inspectTools.inspectService(
                    firstNonBlank(request.serviceName(), request.service()));
            default -> throw AiPlatformApiException.badRequest("unsupported implementation: " + implementation);
        };
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw AiPlatformApiException.badRequest("failed to serialize tool output: " + ex.getMessage());
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String firstNonBlank(String first, String second) {
        if (!blank(first)) {
            return first;
        }
        return second;
    }

    public record SaveToolRequest(
            String toolId,
            String name,
            String category,
            String description,
            ToolType type,
            String implementation,
            String schemaJson,
            String configJson,
            Boolean enabled) {

        SaveToolRequest withToolId(String nextToolId) {
            return new SaveToolRequest(
                    nextToolId, name, category, description, type, implementation, schemaJson, configJson, enabled);
        }

        SaveToolRequest withImplementation(String nextImplementation) {
            return new SaveToolRequest(
                    toolId, name, category, description, type, nextImplementation, schemaJson, configJson, enabled);
        }
    }

    private static String normalizeCategory(String category) {
        return category == null || category.isBlank() ? "默认分类" : category.trim();
    }

    public record TestToolRequest(
            String service,
            String serviceType,
            String serviceId,
            String serviceName,
            String serviceInstance,
            String srcServiceId,
            String traceId,
            String keyword,
            String componentType,
            String resource,
            String direction,
            String metric,
            String measurement,
            String field,
            String tagsJson,
            List<MetricQueryRequest> queryRequests,
            String fromTime,
            String toTime,
            String queryType,
            String groupBy,
            String targetTime,
            Integer interval,
            Integer size,
            Integer status,
            Integer rangeMinutes,
            List<TrendChartSpec> charts) {

        static TestToolRequest empty() {
            return new TestToolRequest(
                    null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null);
        }
    }
}
