package com.databuff.apm.web.portal;

import com.databuff.apm.common.query.ApmQueryModels.ServiceFlowEdge;
import com.databuff.apm.common.util.PortalServiceIdResolver;

import static com.databuff.apm.common.util.PortalServiceIdResolver.normalize;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Global service topology from HTTP/RPC and virtual-service component metrics. */
@Service
public class GlobalTopologyPortalService {

    private final GlobalTopologyQueryService globalTopologyQueryService;
    private final ServiceAlarmCounter serviceAlarmCounter;

    public GlobalTopologyPortalService(
            GlobalTopologyQueryService globalTopologyQueryService,
            ServiceAlarmCounter serviceAlarmCounter) {
        this.globalTopologyQueryService = globalTopologyQueryService;
        this.serviceAlarmCounter = serviceAlarmCounter;
    }

    public Map<String, Object> graph(Map<String, Object> body) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        int limit = Math.min(ServicePortalService.intValue(body.get("limit"), 500), 500);
        List<ServiceFlowEdge> edges = globalTopologyQueryService.listEdges(from, to, limit);
        return buildGraph(edges, from, to);
    }

    public Map<String, Object> verticalTree(Map<String, Object> body) {
        String requestServiceId = ServicePortalService.stringValue(body.get("serviceId"), null);
        if (requestServiceId == null || requestServiceId.isBlank()) {
            return Map.of();
        }
        String serviceId = normalize(requestServiceId);

        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        int limit = Math.min(ServicePortalService.intValue(body.get("limit"), 500), 500);
        List<ServiceFlowEdge> edges = globalTopologyQueryService.listEdges(from, to, limit);

        Map<String, Map<String, Object>> serviceMap = new LinkedHashMap<>();
        Map<String, List<String>> serviceToServices = new LinkedHashMap<>();

        ensureNode(serviceMap, serviceId, null);

        for (ServiceFlowEdge edge : edges) {
            String srcId = resolveNodeId(edge.srcServiceId(), edge.srcService());
            String dstId = resolveNodeId(edge.dstServiceId(), edge.dstService());
            if (srcId.isBlank() || dstId.isBlank() || srcId.equals(dstId)) {
                continue;
            }

            if (serviceId.equals(dstId) || serviceId.equals(srcId)) {
                ensureNode(serviceMap, srcId, edge.srcService());
                ensureNode(serviceMap, dstId, edge.dstService());
                accumulateNode(serviceMap.get(srcId), edge.callCount(), edge.errorCount(), edge.avgDuration());
                accumulateNode(serviceMap.get(dstId), edge.callCount(), edge.errorCount(), edge.avgDuration());
            }

            if (serviceId.equals(dstId) && !serviceId.equals(srcId)) {
                serviceToServices.computeIfAbsent(srcId, key -> new ArrayList<>());
                if (!serviceToServices.get(srcId).contains(dstId)) {
                    serviceToServices.get(srcId).add(dstId);
                }
            }
            if (serviceId.equals(srcId) && !serviceId.equals(dstId)) {
                serviceToServices.computeIfAbsent(srcId, key -> new ArrayList<>());
                if (!serviceToServices.get(srcId).contains(dstId)) {
                    serviceToServices.get(srcId).add(dstId);
                }
            }
        }

        finalizeNodes(serviceMap);
        enrichAlarmCounts(serviceMap, from, to);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("services", List.copyOf(serviceMap.values()));
        data.put("serviceToServices", serviceToServices);
        data.put("serviceToProcesses", Map.of());
        data.put("processToHosts", Map.of());
        data.put("applications", List.of());
        data.put("applicationToServices", Map.of());
        data.put("processes", List.of());
        data.put("hosts", List.of());
        return data;
    }

    private Map<String, Object> buildGraph(List<ServiceFlowEdge> edges, long from, long to) {
        Map<String, Map<String, Object>> nodeMap = new LinkedHashMap<>();
        List<Map<String, Object>> serviceEdges = new ArrayList<>();

        for (ServiceFlowEdge edge : edges) {
            String srcId = resolveNodeId(edge.srcServiceId(), edge.srcService());
            String dstId = resolveNodeId(edge.dstServiceId(), edge.dstService());
            if (srcId.isBlank() || dstId.isBlank() || srcId.equals(dstId)) {
                continue;
            }

            ensureNode(nodeMap, srcId, edge.srcService());
            ensureNode(nodeMap, dstId, edge.dstService());

            long callCnt = edge.callCount();
            long errCnt = edge.errorCount();
            double errRate = callCnt > 0 ? (double) errCnt / callCnt : 0;
            double successRate = callCnt > 0 ? 1.0 - errRate : 1.0;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("src", srcId);
            row.put("dst", dstId);
            row.put("cnt", callCnt);
            row.put("error", errCnt);
            row.put("avgDuration", edge.avgDuration());
            row.put("errRate", errRate);
            row.put("successRate", successRate);
            serviceEdges.add(row);

            accumulateNode(nodeMap.get(srcId), callCnt, errCnt, edge.avgDuration());
            accumulateNode(nodeMap.get(dstId), callCnt, errCnt, edge.avgDuration());
        }

        finalizeNodes(nodeMap);
        enrichAlarmCounts(nodeMap, from, to);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("services", List.copyOf(nodeMap.values()));
        data.put("serviceEdges", serviceEdges);
        return data;
    }

    private void enrichAlarmCounts(Map<String, Map<String, Object>> nodeMap, long from, long to) {
        if (nodeMap.isEmpty()) {
            return;
        }
        Map<String, Long> alarmCountsByService = serviceAlarmCounter.countByService(from, to);
        for (Map<String, Object> node : nodeMap.values()) {
            long alarmCount = serviceAlarmCounter.resolve(
                    ServicePortalService.stringValue(node.get("id"), ""),
                    ServicePortalService.stringValue(node.get("name"), ""),
                    alarmCountsByService);
            node.put("alarmCount", alarmCount);
            node.put("errType", alarmCount > 0 ? 1 : 0);
        }
    }

    private static String resolveNodeId(String serviceId, String serviceName) {
        return PortalServiceIdResolver.resolve(serviceId, serviceName);
    }

    private static void ensureNode(Map<String, Map<String, Object>> nodeMap, String id, String name) {
        if (id == null || id.isBlank()) {
            return;
        }
        if (nodeMap.containsKey(id)) {
            if (name != null && !name.isBlank()) {
                Map<String, Object> existing = nodeMap.get(id);
                if (ServicePortalService.stringValue(existing.get("name"), "").isBlank()) {
                    existing.put("name", name);
                    existing.put("serviceName", name);
                    existing.put("service", name);
                    applyServiceType(existing, name);
                }
            }
            return;
        }
        String resolvedName = (name == null || name.isBlank()) ? id : name;
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("serviceId", id);
        node.put("name", resolvedName);
        node.put("serviceName", resolvedName);
        node.put("service", resolvedName);
        node.put("callCnt", 0);
        node.put("errCnt", 0);
        node.put("errRate", 0);
        node.put("avgLatency", 0);
        node.put("alarmCount", 0);
        node.put("errType", 0);
        applyServiceType(node, resolvedName);
        nodeMap.put(id, node);
    }

    private static void applyServiceType(Map<String, Object> node, String name) {
        String serviceType = inferServiceType(name);
        node.put("service_type", serviceType);
        node.put("type", inferSubType(name, serviceType));
    }

    private static String inferServiceType(String name) {
        if (name == null || !name.startsWith("[")) {
            return "default";
        }
        int end = name.indexOf(']');
        if (end <= 1) {
            return "default";
        }
        return switch (name.substring(1, end).toLowerCase()) {
            case "db" -> "db";
            case "redis", "cache" -> "cache";
            case "mq", "kafka", "rocketmq", "rabbitmq" -> "mq";
            case "peer", "remote" -> "remote";
            case "es", "elasticsearch" -> "db";
            case "config" -> "config";
            default -> "default";
        };
    }

    private static String inferSubType(String name, String serviceType) {
        if ("default".equals(serviceType) || name == null) {
            return "default";
        }
        int end = name.indexOf(']');
        if (end > 0 && end + 1 < name.length()) {
            String rest = name.substring(end + 1);
            int colon = rest.indexOf(':');
            String subType = colon > 0 ? rest.substring(0, colon) : rest;
            return subType.isBlank() ? serviceType : subType;
        }
        return serviceType;
    }

    private static void finalizeNodes(Map<String, Map<String, Object>> nodeMap) {
        for (Map<String, Object> node : nodeMap.values()) {
            long callCnt = ServicePortalService.intValue(node.get("callCnt"), 0);
            long errCnt = ServicePortalService.intValue(node.get("errCnt"), 0);
            double errRate = callCnt > 0 ? (double) errCnt / callCnt : 0;
            node.put("errRate", errRate);
            long latencyCallCnt = ServicePortalService.intValue(node.get("_latencyCallCnt"), 0);
            if (latencyCallCnt > 0) {
                double sumLatency = toDouble(node.get("_sumLatency"), 0);
                node.put("avgLatency", sumLatency / latencyCallCnt);
            } else {
                node.put("avgLatency", 0);
            }
            node.remove("_latencyCallCnt");
            node.remove("_sumLatency");
        }
    }

    private static void accumulateNode(Map<String, Object> node, long callCnt, long errCnt, double avgDuration) {
        if (node == null) {
            return;
        }
        node.put("callCnt", ServicePortalService.intValue(node.get("callCnt"), 0) + callCnt);
        node.put("errCnt", ServicePortalService.intValue(node.get("errCnt"), 0) + errCnt);
        if (callCnt > 0 && avgDuration > 0) {
            long latencyCallCnt = ServicePortalService.intValue(node.get("_latencyCallCnt"), 0) + callCnt;
            double sum = toDouble(node.get("_sumLatency"), 0) + avgDuration * callCnt;
            node.put("_latencyCallCnt", latencyCallCnt);
            node.put("_sumLatency", sum);
        }
    }

    private static double toDouble(Object value, double fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
