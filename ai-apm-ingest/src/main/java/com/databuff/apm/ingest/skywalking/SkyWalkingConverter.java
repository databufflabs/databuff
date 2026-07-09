package com.databuff.apm.ingest.skywalking;

import com.databuff.apm.common.meta.OtelAttributeMaps;
import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.serde.DcSpanUtil;
import com.databuff.apm.common.time.ApmTimeZones;
import com.databuff.apm.common.trace.TraceSpanNames;
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
        List<ConvertedTrace> out = new ArrayList<>();
        for (SpanObject span : segment.getSpansList()) {
            try {
                DcSpan dc = buildSpan(serviceName, serviceKey, serviceInstance, traceId, segmentId, span);
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
            SpanObject span) throws IOException {
        long start = span.getStartTime() * 1_000_000L;
        long end = span.getEndTime() * 1_000_000L;
        long duration = Math.max(0, end - start);
        Map<String, String> tags = tags(span.getTagsList());
        DcSpan dc = new DcSpan();
        dc.minutes = toMinutesBucket(start);
        dc.hours = toHoursBucket(start);
        dc.serviceId = serviceKey;
        dc.service = serviceName;
        dc.serviceInstance = serviceInstance == null ? "" : serviceInstance;
        dc.resource = span.getOperationName();
        dc.name = TraceSpanNames.normalizeOtelName(span.getOperationName(), tags);
        dc.trace_id = traceId;
        dc.span_id = SkyWalkingIdUtil.spanId(segmentId, span.getSpanId());
        dc.parent_id = resolveParentId(segmentId, span);
        dc.is_parent = dc.parent_id == null || dc.parent_id.isBlank() ? 1 : 0;
        dc.start = start;
        dc.end = end;
        dc.duration = duration;
        dc.startTime = DATETIME.format(Instant.ofEpochSecond(0, start));
        dc.error = span.getIsError() ? 1 : 0;
        dc.slow = duration > 500_000_000L ? 1 : 0;
        dc.type = mapSpanType(span.getSpanType());
        dc.isIn = 0;
        dc.isOut = 0;
        applyHttpAttributes(dc, tags, span);
        applyPeer(dc, tags, span.getPeer());
        dc.hostName = firstNonBlank(tags.get("host.name"), serviceInstance, "unknown");
        dc.host_id = dc.hostName;
        dc.metaErrorType = tags.get("error.type");
        Map<String, String> meta = new LinkedHashMap<>(tags);
        meta.put(TraceDataSources.META_DATA_SOURCE, TraceDataSources.SKY_WALKING);
        normalizeSkyWalkingRpcMeta(span, meta);
        SkyWalkingMetaNormalizer.normalize(span, meta);
        applyNormalizedDbResource(dc, meta);
        dc.meta = OtelAttributeMaps.encode(meta);
        OtelAttributeMaps.cache(dc, meta);
        return dc;
    }

    private static void applyPeer(DcSpan dc, Map<String, String> tags, String peer) {
        if (peer == null || peer.isBlank()) {
            return;
        }
        PeerParts parts = parsePeer(peer);
        dc.metaPeerHostname = parts.hostname();
        if (parts.port() != null) {
            tags.put("server.address", parts.hostname());
            tags.put("server.port", parts.port());
        }
    }

    /** Split SkyWalking peer ({@code host:port}) into hostname + port for OTel-compatible meta. */
    static PeerParts parsePeer(String peer) {
        String trimmed = peer.trim();
        int colon = trimmed.lastIndexOf(':');
        if (colon > 0 && colon < trimmed.length() - 1) {
            String host = trimmed.substring(0, colon);
            String port = trimmed.substring(colon + 1);
            if (port.chars().allMatch(Character::isDigit)) {
                return new PeerParts(host, port);
            }
        }
        return new PeerParts(trimmed, null);
    }

    record PeerParts(String hostname, String port) {
    }

    private static String resolveParentId(String segmentId, SpanObject span) {
        for (SegmentReference ref : span.getRefsList()) {
            if (ref.getRefType() == RefType.CrossProcess && !ref.getParentTraceSegmentId().isBlank()) {
                return SkyWalkingIdUtil.spanId(ref.getParentTraceSegmentId(), ref.getParentSpanId());
            }
        }
        if (span.getParentSpanId() < 0) {
            return "";
        }
        return SkyWalkingIdUtil.spanId(segmentId, span.getParentSpanId());
    }

    private static String mapSpanType(SpanType spanType) {
        return switch (spanType) {
            case Entry -> "SPAN_KIND_SERVER";
            case Exit -> "SPAN_KIND_CLIENT";
            case Local -> "SPAN_KIND_INTERNAL";
            case UNRECOGNIZED -> "SPAN_KIND_INTERNAL";
        };
    }

    private static void applyHttpAttributes(DcSpan dc, Map<String, String> tags, SpanObject span) {
        if (isSkyWalkingRpcSpan(span, tags)) {
            return;
        }
        dc.metaHttpMethod = firstNonBlank(tags.get("http.method"), tags.get("http.request.method"));
        dc.metaHttpStatusCode = parseInt(firstNonBlank(tags.get("http.status_code"), tags.get("status_code")));
        String url = firstNonBlank(tags.get("url"), tags.get("http.url"), tags.get("http.route"));
        if (url != null && !DcSpanUtil.isRpcProtocolUrl(url)) {
            dc.metaHttpUrl = url;
        }
    }

    private static boolean isSkyWalkingRpcSpan(SpanObject span, Map<String, String> tags) {
        if (span.getSpanLayer() == SpanLayer.RPCFramework) {
            return true;
        }
        if (span.getComponentId() > 0 && DcSpanUtil.rpcSystemFromSkyWalkingComponentId(span.getComponentId()) != null) {
            return true;
        }
        String url = tags.get("url");
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
            rpcSystem = DcSpanUtil.resolveRpcSystem(meta, null);
        }
        if (rpcSystem == null) {
            rpcSystem = DcSpanUtil.resolveRpcSystem(meta, spanResource(span));
        }
        if (rpcSystem != null) {
            meta.put("rpc.system", rpcSystem);
        }
    }

    private static DcSpan spanResource(SpanObject span) {
        DcSpan placeholder = new DcSpan();
        placeholder.resource = span.getOperationName();
        placeholder.name = span.getOperationName();
        return placeholder;
    }

    private static Map<String, String> tags(List<KeyStringValuePair> pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        if (pairs == null) {
            return out;
        }
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

    private static long toMinutesBucket(long startNanos) {
        long epochSec = startNanos / 1_000_000_000L;
        long minute = epochSec / 60;
        return Long.parseLong(ApmTimeZones.formatBucket(minute * 60L, "yyyyMMddHHmm"));
    }

    private static long toHoursBucket(long startNanos) {
        long epochSec = startNanos / 1_000_000_000L;
        long hour = epochSec / 3600;
        return Long.parseLong(ApmTimeZones.formatBucket(hour * 3600L, "yyyyMMddHH"));
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
