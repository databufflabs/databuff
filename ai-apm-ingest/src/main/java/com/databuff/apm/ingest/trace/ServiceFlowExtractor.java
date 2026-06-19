package com.databuff.apm.ingest.trace;

import com.databuff.apm.common.flow.ServiceFlowPathIds;
import com.databuff.apm.common.flow.ServiceFlowSpanRules;
import com.databuff.apm.common.metric.MetricSchemaRegistry;
import com.databuff.apm.common.metric.TraceMetricMinuteBucket;
import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.model.OptimizedMetric;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds hierarchical {@code service.flow} metrics from a filled trace,
 * aligned with legacy portal {@code FillPathAndRelationUtil#generateTree}.
 */
public final class ServiceFlowExtractor {

    private ServiceFlowExtractor() {
    }

    public static List<OptimizedMetric> extractFromTrace(List<DcSpan> spans) {
        if (spans == null || spans.isEmpty()) {
            return List.of();
        }
        Map<String, List<DcSpan>> childrenByParent = new HashMap<>();
        Map<String, DcSpan> bySpanId = new HashMap<>();
        for (DcSpan span : spans) {
            bySpanId.put(span.span_id, span);
            String parentKey = parentKey(span.parent_id);
            childrenByParent.computeIfAbsent(parentKey, ignored -> new ArrayList<>()).add(span);
        }
        List<DcSpan> roots = childrenByParent.getOrDefault("0", List.of());
        if (roots.isEmpty()) {
            return List.of();
        }
        DcSpan root = roots.get(0);
        if (ServiceFlowSpanRules.isVirtualServiceSpan(root)) {
            return List.of();
        }
        if (spanIdToSpanMapSizeIsOneWithoutInbound(spans, root)) {
            return List.of();
        }
        long srcCall = root.is_parent == 1 && root.isIn == 1 ? 1L : 0L;
        String entryPathId = ServiceFlowPathIds.entryPathId(nullToEmpty(root.serviceId));
        String entryInterfacePathId = ServiceFlowPathIds.entryInterfacePathId(
                nullToEmpty(root.serviceId), ServiceFlowSpanRules.displayResource(root));
        List<OptimizedMetric> metrics = new ArrayList<>();
        generateTree(
                root,
                childrenByParent,
                bySpanId,
                0,
                metrics,
                srcCall,
                entryPathId,
                entryInterfacePathId,
                null,
                null,
                "",
                "",
                "");
        return metrics;
    }

    private static boolean spanIdToSpanMapSizeIsOneWithoutInbound(List<DcSpan> spans, DcSpan root) {
        return spans.size() == 1 && root.isIn != 1;
    }

    private static void generateTree(
            DcSpan current,
            Map<String, List<DcSpan>> childrenByParent,
            Map<String, DcSpan> bySpanId,
            int level,
            List<OptimizedMetric> metrics,
            long srcCall,
            String entryPathId,
            String entryInterfacePathId,
            String parentPathId,
            String parentInterfacePathId,
            String parentService,
            String parentServiceId,
            String parentResource) {
        String resource = ServiceFlowSpanRules.displayResource(current);
        String currentPathId = ServiceFlowPathIds.pathId(parentPathId, level, nullToEmpty(current.serviceId));
        String currentInterfacePathId = ServiceFlowPathIds.interfacePathId(
                parentInterfacePathId, level, nullToEmpty(current.serviceId), resource);
        metrics.add(toFlowMetric(
                current,
                entryPathId,
                parentPathId,
                currentPathId,
                entryInterfacePathId,
                parentInterfacePathId,
                currentInterfacePathId,
                parentService,
                parentServiceId,
                parentResource,
                resource,
                srcCall));

        List<DcSpan> children = new ArrayList<>();
        findChildren(current, current, childrenByParent, bySpanId, children);
        Map<String, List<DcSpan>> grouped = new HashMap<>();
        for (DcSpan child : children) {
            grouped.computeIfAbsent(nullToEmpty(child.serviceId), ignored -> new ArrayList<>()).add(child);
        }
        for (List<DcSpan> group : grouped.values()) {
            for (int i = 0; i < group.size(); i++) {
                DcSpan child = group.get(i);
                long childSrcCall = i == 0 ? 1L : 0L;
                generateTree(
                        child,
                        childrenByParent,
                        bySpanId,
                        level + 1,
                        metrics,
                        childSrcCall,
                        entryPathId,
                        entryInterfacePathId,
                        currentPathId,
                        currentInterfacePathId,
                        nullToEmpty(current.service),
                        nullToEmpty(current.serviceId),
                        ServiceFlowSpanRules.displayResource(current));
            }
        }
    }

    private static void findChildren(
            DcSpan inbound,
            DcSpan current,
            Map<String, List<DcSpan>> childrenByParent,
            Map<String, DcSpan> bySpanId,
            List<DcSpan> newChildren) {
        List<DcSpan> children = childrenByParent.get(current.span_id);
        if (children == null) {
            return;
        }
        for (DcSpan child : children) {
            if (ServiceFlowSpanRules.isVirtualSpan(child)) {
                // Component spans become virtual services after fill; attach them to the flow tree.
                if (ServiceFlowSpanRules.isVirtualServiceSpan(child) && child.isIn == 1) {
                    linkPeerRelation(inbound, child, bySpanId);
                    if (child.is_parent == 0) {
                        newChildren.add(child);
                    }
                } else {
                    findChildren(inbound, child, childrenByParent, bySpanId, newChildren);
                }
                continue;
            }
            if (child.isIn == 1) {
                linkPeerRelation(inbound, child, bySpanId);
                if (child.is_parent == 0) {
                    newChildren.add(child);
                }
            } else {
                findChildren(inbound, child, childrenByParent, bySpanId, newChildren);
            }
        }
        childrenByParent.remove(current.span_id);
    }

    private static void linkPeerRelation(DcSpan inbound, DcSpan child, Map<String, DcSpan> bySpanId) {
        if (child.srcServiceId == null || child.srcServiceId.isBlank()) {
            child.srcService = inbound.service;
            child.srcServiceId = inbound.serviceId;
            child.srcServiceInstance = inbound.serviceInstance;
        }
        if (inbound.dstServiceId == null || inbound.dstServiceId.isBlank()) {
            inbound.dstService = child.service;
            inbound.dstServiceId = child.serviceId;
            inbound.dstServiceInstance = child.serviceInstance;
        }
        DcSpan outbound = bySpanId.get(child.parent_id);
        if (outbound != null && outbound.isOut == 1 && (outbound.dstServiceId == null || outbound.dstServiceId.isBlank())) {
            outbound.dstService = child.service;
            outbound.dstServiceId = child.serviceId;
            outbound.dstServiceInstance = child.serviceInstance;
        }
    }

    private static OptimizedMetric toFlowMetric(
            DcSpan span,
            String entryPathId,
            String parentPathId,
            String pathId,
            String entryInterfacePathId,
            String parentInterfacePathId,
            String interfacePathId,
            String parentService,
            String parentServiceId,
            String parentResource,
            String resource,
            long srcCall) {
        Map<String, String> tags = new java.util.LinkedHashMap<>();
        tags.put("entryInterfacePathId", nullToEmpty(entryInterfacePathId));
        tags.put("entryPathId", nullToEmpty(entryPathId));
        tags.put("interfacePathId", nullToEmpty(interfacePathId));
        tags.put("isIn", span.isIn == 1 ? "1" : "0");
        tags.put("parentInterfacePathId", nullToEmpty(parentInterfacePathId));
        tags.put("parentPathId", parentPathId == null ? "" : parentPathId);
        tags.put("parentResource", nullToEmpty(parentResource));
        tags.put("parentService", nullToEmpty(parentService));
        tags.put("parentServiceId", nullToEmpty(parentServiceId));
        tags.put("pathId", nullToEmpty(pathId));
        tags.put("resource", nullToEmpty(resource));
        tags.put("service", nullToEmpty(span.service));
        tags.put("serviceId", nullToEmpty(span.serviceId));
        long slow = span.slow;
        long duration = span.duration;
        long endNanos = span.end > 0 ? span.end : span.start;
        long minuteBucketNs = TraceMetricMinuteBucket.minuteBucketEpochNanosFromEndNanos(endNanos);
        long minuteBucketMs = minuteBucketNs / 1_000_000L;
        OptimizedMetric metric = new OptimizedMetric()
                .withTimestamp(minuteBucketNs)
                .withMeasurement("service.flow")
                .withTagValues(MetricSchemaRegistry.tagValuesFromMap("service.flow", tags))
                .withFieldValues(1L, span.error, slow, srcCall, duration);
        return metric.withTsId(TraceMetricMinuteBucket.aggregationTsId(metric, minuteBucketMs));
    }

    private static String parentKey(String parentId) {
        if (parentId == null || parentId.isBlank()) {
            return "0";
        }
        return parentId;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
