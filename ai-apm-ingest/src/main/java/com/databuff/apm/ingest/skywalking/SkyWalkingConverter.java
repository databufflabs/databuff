package com.databuff.apm.ingest.skywalking;

import com.databuff.apm.common.meta.OtelAttributeMaps;
import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.serde.DcSpanUtil;
import com.databuff.apm.common.time.ApmTimeZones;
import com.databuff.apm.common.trace.TraceSpanNames;
import com.databuff.apm.common.trace.TraceParentUtil;
import com.databuff.apm.common.util.ServiceKeyUtil;
import com.databuff.apm.ingest.trace.remote.TraceDataSources;
import org.apache.skywalking.apm.network.common.v3.KeyStringValuePair;
import org.apache.skywalking.apm.network.language.agent.v3.RefType;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentReference;
import org.apache.skywalking.apm.network.language.agent.v3.SpanObject;
import org.apache.skywalking.apm.network.language.agent.v3.SpanType;
import org.apache.skywalking.apm.network.language.agent.v3.SpanLayer;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SkyWalking SegmentObject → {@link DcSpan} list. */
public final class SkyWalkingConverter {

    private static final DateTimeFormatter DATETIME = ApmTimeZones.WALL_CLOCK;

    public List<ConvertedTrace> convertSegment(SegmentObject segment) {
        if (segment == null || segment.getService().isBlank()) {
            return List.of();
        }
        String serviceName = segment.getService();
        String serviceKey = ServiceKeyUtil.of(serviceName);
        String serviceInstance = segment.getServiceInstance();
        String traceId = normalizeTraceId(segment.getTraceId());
        String segmentId = segment.getTraceSegmentId();
        Map<Integer, String> localSpanIds = new HashMap<>();
        List<ConvertedTrace> out = new ArrayList<>(segment.getSpansCount());
        for (SpanObject span : segment.getSpansList()) {
            try {
                DcSpan dc = buildSpan(serviceName, serviceKey, serviceInstance, traceId, segmentId, span, localSpanIds);
                if (dc != null) {
                    out.add(new ConvertedTrace(serviceKey, dc));
                }
            } catch (IOException ignored) {
                // skip malformed span
            }
        }
        return out;
    }

    private DcSpan buildSpan(
            String serviceName,
            String serviceKey,
            String serviceInstance,
            String traceId,
            String segmentId,
            SpanObject span,
            Map<Integer, String> localSpanIds) throws IOException {
        long start = span.getStartTime() * 1_000_000L;
        long end = span.getEndTime() * 1_000_000L;
        long duration = Math.max(0, end - start);
        Map<String, String> meta = tags(span.getTagsList());
        DcSpan dc = new DcSpan();
        long epochSec = start / 1_000_000_000L;
        dc.minutes = ApmTimeZones.wallClockMinuteBucket((epochSec / 60) * 60);
        dc.hours = ApmTimeZones.wallClockHourBucket((epochSec / 3600) * 3600);
        dc.serviceId = serviceKey;
        dc.service = serviceName;
        dc.serviceInstance = serviceInstance == null ? "" : serviceInstance;
        dc.resource = span.getOperationName();
        dc.name = TraceSpanNames.normalizeOtelName(span.getOperationName(), meta);
        dc.trace_id = traceId;
        dc.span_id = localSpanId(segmentId, span.getSpanId(), localSpanIds);
        dc.parent_id = resolveParentId(segmentId, span, localSpanIds);
        TraceParentUtil.applyIsParent(dc);
        dc.start = start;
        dc.end = end;
        dc.duration = duration;
        dc.startTime = DATETIME.format(Instant.ofEpochSecond(0, start));
        dc.error = span.getIsError() ? 1 : 0;
        dc.slow = duration > 500_000_000L ? 1 : 0;
        dc.type = mapSpanType(span.getSpanType());
        dc.isIn = 0;
        dc.isOut = 0;
        applyHttpAttributes(dc, meta, span);
        applyPeer(dc, meta, span.getPeer());
        dc.hostName = firstNonBlank(meta.get("host.name"), serviceInstance, "unknown");
        dc.host_id = dc.hostName;
        dc.metaErrorType = meta.get("error.type");
        meta.put(TraceDataSources.META_DATA_SOURCE, TraceDataSources.SKY_WALKING);
        normalizeSkyWalkingRpcMeta(span, meta);
        SkyWalkingMetaNormalizer.normalize(span, meta);
        applyNormalizedDbResource(dc, meta);
        dc.meta = OtelAttributeMaps.encode(meta);
        OtelAttributeMaps.cache(dc, meta);
        return dc;
    }

    private static String localSpanId(String segmentId, int spanId, Map<Integer, String> localSpanIds) {
        return localSpanIds.computeIfAbsent(spanId, id -> SkyWalkingIdUtil.spanId(segmentId, id));
    }

    private static void applyPeer(DcSpan dc, Map<String, String> meta, String peer) {
        if (peer == null || peer.isBlank()) {
            return;
        }
        PeerParts parts = parsePeer(peer);
        dc.metaPeerHostname = parts.hostname();
        if (parts.port() != null) {
            meta.put("server.address", parts.hostname());
            meta.put("server.port", parts.port());
        }
    }

    /** Split SkyWalking peer ({@code host:port}) into hostname + port for OTel-compatible meta. */
    static PeerParts parsePeer(String peer) {
        String trimmed = peer.trim();
        int colon = trimmed.lastIndexOf(':');
        if (colon > 0 && colon < trimmed.length() - 1) {
            String host = trimmed.substring(0, colon);
            String port = trimmed.substring(colon + 1);
            if (isAllDigits(port)) {
                return new PeerParts(host, port);
            }
        }
        return new PeerParts(trimmed, null);
    }

    private static boolean isAllDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return !value.isEmpty();
    }

    record PeerParts(String hostname, String port) {
    }

    private static String resolveParentId(String segmentId, SpanObject span, Map<Integer, String> localSpanIds) {
        for (SegmentReference ref : span.getRefsList()) {
            if (ref.getRefType() != RefType.CrossProcess || ref.getParentTraceSegmentId().isBlank()) {
                continue;
            }
            String parentSegmentId = ref.getParentTraceSegmentId();
            if (parentSegmentId.equals(segmentId)) {
                return localSpanId(segmentId, ref.getParentSpanId(), localSpanIds);
            }
            return SkyWalkingIdUtil.spanId(parentSegmentId, ref.getParentSpanId());
        }
        if (span.getParentSpanId() < 0) {
            return "";
        }
        return localSpanId(segmentId, span.getParentSpanId(), localSpanIds);
    }

    private static String mapSpanType(SpanType spanType) {
        return switch (spanType) {
            case Entry -> "SPAN_KIND_SERVER";
            case Exit -> "SPAN_KIND_CLIENT";
            case Local -> "SPAN_KIND_INTERNAL";
            case UNRECOGNIZED -> "SPAN_KIND_INTERNAL";
        };
    }

    private static void applyHttpAttributes(DcSpan dc, Map<String, String> meta, SpanObject span) {
        if (isSkyWalkingRpcSpan(span, meta)) {
            return;
        }
        dc.metaHttpMethod = firstNonBlank(meta.get("http.method"), meta.get("http.request.method"));
        dc.metaHttpStatusCode = parseInt(firstNonBlank(meta.get("http.status_code"), meta.get("status_code")));
        String url = firstNonBlank(meta.get("url"), meta.get("http.url"), meta.get("http.route"));
        if (url != null && !DcSpanUtil.isRpcProtocolUrl(url)) {
            dc.metaHttpUrl = DcSpanUtil.normalizeHttpUrl(url);
        }
    }

    private static boolean isSkyWalkingRpcSpan(SpanObject span, Map<String, String> meta) {
        if (span.getSpanLayer() == SpanLayer.RPCFramework) {
            return true;
        }
        if (span.getComponentId() > 0 && DcSpanUtil.rpcSystemFromSkyWalkingComponentId(span.getComponentId()) != null) {
            return true;
        }
        String url = meta.get("url");
        return url != null && DcSpanUtil.isRpcProtocolUrl(url);
    }

    private static void applyNormalizedDbResource(DcSpan dc, Map<String, String> meta) {
        String statement = OtelAttributeMaps.firstNonBlank(meta, "db.statement", "db.sql", "normalized.resource");
        if (statement != null && !statement.isBlank()) {
            dc.resource = statement;
        }
    }

    private static void normalizeSkyWalkingRpcMeta(SpanObject span, Map<String, String> meta) {
        if (span.getSpanLayer() != SpanLayer.Unknown) {
            meta.put("skywalking.spanLayer", span.getSpanLayer().name());
        }
        if (span.getComponentId() > 0) {
            meta.put("skywalking.componentId", String.valueOf(span.getComponentId()));
        }
        if (OtelAttributeMaps.firstNonBlank(meta, "rpc.system") != null) {
            return;
        }
        String rpcSystem = null;
        if (span.getComponentId() > 0) {
            rpcSystem = DcSpanUtil.rpcSystemFromSkyWalkingComponentId(span.getComponentId());
        }
        if (rpcSystem == null && span.getSpanLayer() == SpanLayer.RPCFramework) {
            rpcSystem = DcSpanUtil.resolveRpcSystem(meta, (String) null);
        }
        if (rpcSystem == null) {
            rpcSystem = DcSpanUtil.resolveRpcSystem(meta, span.getOperationName());
        }
        if (rpcSystem != null) {
            meta.put("rpc.system", rpcSystem);
        }
    }

    private static Map<String, String> tags(List<KeyStringValuePair> pairs) {
        if (pairs == null || pairs.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, String> out = new LinkedHashMap<>(pairs.size() + 4);
        for (KeyStringValuePair pair : pairs) {
            if (pair.getKey() != null && !pair.getKey().isBlank() && pair.getValue() != null) {
                out.put(pair.getKey(), pair.getValue());
            }
        }
        return out;
    }

    private static String normalizeTraceId(String traceId) {
        return traceId == null ? "" : traceId.trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Integer parseInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public record ConvertedTrace(String serviceKey, DcSpan span) {
    }
}
