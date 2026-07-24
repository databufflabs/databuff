package com.databuff.apm.ingest.trace;

import com.databuff.apm.common.flow.ServiceFlowSpanRules;
import com.databuff.apm.common.meta.OtelAttributeMaps;
import com.databuff.apm.common.meta.SpanDirectionUtil;
import com.databuff.apm.common.meta.VirtualServiceResolver;
import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.util.ServiceKeyUtil;
import com.databuff.apm.common.serde.DCSpanJsonDecoder;
import com.databuff.apm.common.serde.DCSpanJsonEncoder;
import com.databuff.apm.common.serde.DcSpanUtil;
import com.databuff.apm.common.serde.SpanDorisJsonRow;
import com.databuff.apm.common.storage.DorisJsonRow;
import com.databuff.apm.common.trace.TraceParentUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Step 4 · 在同一 trace 内根据 parent-child 填充上下游服务关系。
 * <p>
 * fill 结果用于：
 * <ul>
 *   <li>service.flow 指标（src_service → dst_service）</li>
 *   <li>dc_span 表的 src/dst/isIn/isOut 列</li>
 * </ul>
 */
public final class FillPathAndRelationUtil {

    private FillPathAndRelationUtil() {
    }

    /** 测试/兼容：从 bytes 解码后再 fill。生产路径应直接使用 {@link #fillRelations(List)}。 */
    public static List<DcSpan> fillBytes(List<byte[]> spanBytes) throws IOException {
        List<DcSpan> spans = new ArrayList<>(spanBytes.size());
        for (byte[] bytes : spanBytes) {
            spans.add(DCSpanJsonDecoder.decode(bytes, true));
        }
        fillRelations(spans);
        for (DcSpan span : spans) {
            OtelAttributeMaps.materialize(span);
        }
        return spans;
    }

    public static void fillRelations(List<DcSpan> spans) {
        Map<String, DcSpan> bySpanId = new HashMap<>();
        Map<String, List<DcSpan>> childrenByParent = new HashMap<>();
        for (DcSpan span : spans) {
            bySpanId.put(span.span_id, span);
            if (span.parent_id != null && !span.parent_id.isBlank()) {
                childrenByParent.computeIfAbsent(span.parent_id, ignored -> new ArrayList<>()).add(span);
            }
        }
        for (DcSpan span : spans) {
            // Legacy ingest pipeline: isIn/isOut from span name + kind before relation fill
            SpanDirectionUtil.applyNameBasedDirection(span);
            if (TraceParentUtil.isRootParentId(span.parent_id)) {
                span.is_parent = 1;
                if (isOutboundCaller(span)) {
                    // 调用别人：src=自身（含 root CLIENT / EventStream）
                    applyOutboundCallerSrc(span);
                } else {
                    // 接收请求且无对端信息：不填
                    span.srcService = null;
                    span.srcServiceId = null;
                    span.srcServiceInstance = null;
                }
                continue;
            }
            span.is_parent = 0;
            DcSpan parent = bySpanId.get(span.parent_id);
            if (parent != null) {
                // 子 span 默认：src=父服务（入口侧「别人」），dst=本服务
                span.srcService = parent.service;
                span.srcServiceId = parent.serviceId;
                span.srcServiceInstance = parent.serviceInstance;
                span.dstService = span.service;
                span.dstServiceId = span.serviceId;
                span.dstServiceInstance = span.serviceInstance;
                writeClientPeerMeta(span, parent);
                if (!parent.service.equals(span.service)) {
                    span.isIn = 1;
                    parent.isOut = 1;
                }
            }
            // CLIENT / PRODUCER：调用别人 → src 覆盖为自身；并标 isOut
            if (isOutboundCaller(span)) {
                applyOutboundCallerSrc(span);
            }
        }
        Set<String> tracedServices = collectTracedServices(spans);
        linkOutboundRpcHttpPeers(spans, tracedServices);
        propagateTraceContext(spans, bySpanId, childrenByParent);
    }

    private static Set<String> collectTracedServices(List<DcSpan> spans) {
        Set<String> services = new HashSet<>();
        for (DcSpan span : spans) {
            if (span.service != null && !span.service.isBlank()) {
                services.add(VirtualServiceResolver.normalizePeerHost(span.service));
            }
        }
        return services;
    }

    /**
     * Link CLIENT HTTP/RPC spans to internal downstream services via child SERVER spans or
     * {@code server.service} meta; legacy portal compatibility ({@code HttpRpcClientOutProcessor#initServerService}).
     */
    private static void linkOutboundRpcHttpPeers(List<DcSpan> spans, Set<String> tracedServices) {
        Map<String, List<DcSpan>> childrenByParent = new HashMap<>();
        for (DcSpan span : spans) {
            if (span.parent_id == null || span.parent_id.isBlank()) {
                continue;
            }
            childrenByParent.computeIfAbsent(span.parent_id, ignored -> new ArrayList<>()).add(span);
        }
        for (DcSpan span : spans) {
            if (!"SPAN_KIND_CLIENT".equals(span.type)) {
                continue;
            }
            if (!DcSpanUtil.isRpcSpan(span) && !DcSpanUtil.isHttpSpan(span)) {
                continue;
            }
            DcSpan peer = findInternalServerChild(span, childrenByParent.get(span.span_id));
            if (peer != null) {
                applyInternalPeer(span, peer);
                continue;
            }
            Map<String, String> meta = OtelAttributeMaps.parse(span);
            String serverService = OtelAttributeMaps.firstNonBlank(meta, "server.service");
            if (serverService != null
                    && VirtualServiceResolver.isTracedInternalPeer(serverService, tracedServices)) {
                applyInternalPeer(span, serverService.trim(), ServiceKeyUtil.of(serverService.trim()), "");
            }
        }
    }

    private static DcSpan findInternalServerChild(DcSpan client, List<DcSpan> children) {
        if (children == null || children.isEmpty()) {
            return null;
        }
        for (DcSpan child : children) {
            if (!"SPAN_KIND_SERVER".equals(child.type)) {
                continue;
            }
            if (child.service == null || child.service.isBlank()) {
                continue;
            }
            if (child.service.equals(client.service)) {
                continue;
            }
            return child;
        }
        return null;
    }

    private static void applyInternalPeer(DcSpan client, DcSpan server) {
        applyInternalPeer(
                client,
                server.service,
                server.serviceId,
                server.serviceInstance == null ? "" : server.serviceInstance);
    }

    private static void applyInternalPeer(
            DcSpan client, String service, String serviceId, String serviceInstance) {
        client.dstService = service;
        client.dstServiceId = serviceId;
        client.dstServiceInstance = serviceInstance;
        writeServerPeerMeta(client, service, serviceInstance);
    }

    /** Persist caller identity on child spans for UI / agent-tag compatibility. */
    private static void writeClientPeerMeta(DcSpan child, DcSpan parent) {
        if (child == null || parent == null || parent.service == null || parent.service.isBlank()) {
            return;
        }
        OtelAttributeMaps.putIfBlank(child, "client.service", parent.service.trim());
        // Prefer caller instance (UI「客户端服务实例」), then peer address tags.
        String clientIp = firstNonBlank(
                parent.serviceInstance,
                OtelAttributeMaps.firstNonBlank(
                        OtelAttributeMaps.parse(child), "net.peer.name", "network.peer.address", "client.address"));
        OtelAttributeMaps.putIfBlank(child, "client.ip", clientIp);
    }

    /**
     * Persist callee identity on CLIENT spans ({@code server.service}/{@code server.ip}) so portal
     * outbound detail can read peer info without relying on Databuff-agent-only tags.
     */
    private static void writeServerPeerMeta(DcSpan client, String service, String serviceInstance) {
        if (client == null || service == null || service.isBlank()) {
            return;
        }
        OtelAttributeMaps.putIfBlank(client, "server.service", service.trim());
        String serverIp = firstNonBlank(
                serviceInstance,
                OtelAttributeMaps.firstNonBlank(
                        OtelAttributeMaps.parse(client), "server.ip", "network.peer.address", "server.address", "net.peer.name"));
        OtelAttributeMaps.putIfBlank(client, "server.ip", serverIp);
    }

    private static boolean isOutboundCaller(DcSpan span) {
        return span != null
                && ("SPAN_KIND_CLIENT".equals(span.type) || "SPAN_KIND_PRODUCER".equals(span.type));
    }

    /** 调用别人：src=自身，并标记 isOut；DB 出站额外填 dst。 */
    private static void applyOutboundCallerSrc(DcSpan span) {
        span.isOut = 1;
        if (span.service != null && !span.service.isBlank()) {
            span.srcService = span.service;
            span.srcServiceId = span.serviceId;
            span.srcServiceInstance = span.serviceInstance;
        }
        if (DcSpanUtil.isDbSpan(span)) {
            String peer = DcSpanUtil.resolveDbPeer(span, OtelAttributeMaps.parse(span));
            span.dstService = peer;
            span.dstServiceId = ServiceKeyUtil.of(peer);
            span.dstServiceInstance = "";
            span.isIn = 1;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static void propagateTraceContext(
            List<DcSpan> spans,
            Map<String, DcSpan> bySpanId,
            Map<String, List<DcSpan>> childrenByParent) {
        Set<String> visited = new HashSet<>();
        for (DcSpan span : spans) {
            if (span == null || visited.contains(span.span_id)) {
                continue;
            }
            DcSpan root = findRoot(span, bySpanId);
            propagateTraceContextFrom(root, root, null, childrenByParent, visited);
        }
    }

    private static void propagateTraceContextFrom(
            DcSpan span,
            DcSpan root,
            DcSpan inheritedEntry,
            Map<String, List<DcSpan>> childrenByParent,
            Set<String> visited) {
        if (span == null || !visited.add(span.span_id)) {
            return;
        }
        DcSpan currentEntry = inheritedEntry != null
                ? inheritedEntry
                : (isServiceLocalEntry(span, serviceKey(span)) ? span : null);
        applyTraceContext(span, root, currentEntry);
        for (DcSpan child : childrenByParent.getOrDefault(span.span_id, List.of())) {
            DcSpan childEntry = sameService(child, serviceKey(span)) ? currentEntry : null;
            propagateTraceContextFrom(child, root, childEntry, childrenByParent, visited);
        }
    }

    private static void applyTraceContext(DcSpan span, DcSpan root, DcSpan entry) {
        OtelAttributeMaps.edit(span, e -> {
            if (root != null && e.isBlank("root.resource")) {
                String rootResource = entryResource(root);
                if (rootResource != null && !rootResource.isBlank()) {
                    e.put("root.resource", rootResource);
                    String rootType = entryComponentType(root);
                    if (rootType != null && !rootType.isBlank()) {
                        e.put("root.type", rootType);
                    }
                }
            }
            if (e.isBlank("entry.resource")) {
                DcSpan effectiveEntry = entry != null ? entry : (DcSpanUtil.isServiceEntrySpan(span) ? span : null);
                if (effectiveEntry != null) {
                    String entryResource = entryResource(effectiveEntry);
                    if (entryResource != null && !entryResource.isBlank()) {
                        e.put("entry.resource", entryResource);
                    }
                }
            }
        });
    }

    private static DcSpan findServiceEntry(DcSpan span, Map<String, DcSpan> bySpanId) {
        String serviceKey = serviceKey(span);
        DcSpan current = span;
        DcSpan entry = null;
        while (current != null) {
            if (isServiceLocalEntry(current, serviceKey)) {
                entry = current;
            }
            String parentId = current.parent_id;
            if (parentId == null || parentId.isBlank()) {
                break;
            }
            DcSpan parent = bySpanId.get(parentId);
            if (parent == null || !sameService(parent, serviceKey)) {
                break;
            }
            current = parent;
        }
        if (entry != null) {
            return entry;
        }
        return DcSpanUtil.isServiceEntrySpan(span) ? span : null;
    }

    private static boolean isServiceLocalEntry(DcSpan span, String serviceKey) {
        if (!sameService(span, serviceKey) || ServiceFlowSpanRules.isVirtualSpan(span)) {
            return false;
        }
        return DcSpanUtil.isServiceEntrySpan(span);
    }

    private static String serviceKey(DcSpan span) {
        String serviceId = nullToEmpty(span.serviceId);
        return serviceId.isBlank() ? nullToEmpty(span.service) : serviceId;
    }

    private static boolean sameService(DcSpan span, String serviceKey) {
        if (serviceKey == null || serviceKey.isBlank()) {
            return true;
        }
        String serviceId = nullToEmpty(span.serviceId);
        if (!serviceId.isBlank() && serviceKey.equals(serviceId)) {
            return true;
        }
        return serviceKey.equals(nullToEmpty(span.service));
    }

    private static DcSpan findRoot(DcSpan span, Map<String, DcSpan> bySpanId) {
        DcSpan current = span;
        while (current.parent_id != null && !current.parent_id.isBlank()) {
            DcSpan parent = bySpanId.get(current.parent_id);
            if (parent == null) {
                break;
            }
            current = parent;
        }
        return current;
    }

    private static String entryResource(DcSpan root) {
        if (DcSpanUtil.isDbSpan(root) || DcSpanUtil.isRedisSpan(root) || DcSpanUtil.isMqSpan(root)) {
            return "";
        }
        if (DcSpanUtil.isHttpSpan(root)) {
            return DcSpanUtil.normalizeHttpUrl(root.metaHttpUrl != null && !root.metaHttpUrl.isBlank()
                    ? root.metaHttpUrl
                    : nullToEmpty(root.resource));
        }
        return root.resource == null ? "" : root.resource;
    }

    private static String entryComponentType(DcSpan root) {
        if (DcSpanUtil.isHttpSpan(root)) {
            return "service.http";
        }
        if (DcSpanUtil.isRpcSpan(root)) {
            return "service.rpc";
        }
        if (DcSpanUtil.isDbSpan(root)) {
            return "service.db";
        }
        return "";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * fill 完成后挂 lazy 行：Stream Load flush 时才 {@link DCSpanJsonEncoder#encodeTo}，
     * 避免先囤整批 {@code byte[]} 再 join。
     */
    public static List<DorisJsonRow> lazyEncodeFilled(List<DcSpan> spans) {
        List<DorisJsonRow> out = new ArrayList<>(spans.size());
        for (DcSpan span : spans) {
            out.add(new SpanDorisJsonRow(span));
        }
        return out;
    }

    /** @deprecated use {@link #lazyEncodeFilled}; kept for tests that need eager bytes. */
    public static List<byte[]> encodeFilled(List<DcSpan> spans) throws IOException {
        List<byte[]> out = new ArrayList<>(spans.size());
        for (DcSpan span : spans) {
            out.add(DCSpanJsonEncoder.encode(span));
        }
        return out;
    }
}
