package com.databuff.apm.common.meta;

import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.serde.DcSpanUtil;

/**
 * Resolves {@code isIn}/{@code isOut} from span {@code name} and OTLP kind, aligned with
 * Legacy portal {@code TraceMiddleProcessServiceImpl#addInAndOut} and component matchers
 * ({@code HttpServerComponentMatcher}, {@code HttpClientComponentMatcher}, …).
 */
public final class SpanDirectionUtil {

    private SpanDirectionUtil() {
    }

    public record Direction(int isIn, int isOut) {
        public static final Direction NONE = new Direction(0, 0);
    }

    /** Apply name/kind-based direction before relation fill (legacy ingest pipeline {@code addInAndOut}). */
    public static void applyNameBasedDirection(DcSpan span) {
        if (span == null) {
            return;
        }
        Direction direction = resolve(span);
        if (direction.isIn() == 0 && direction.isOut() == 0) {
            return;
        }
        span.isIn = direction.isIn();
        span.isOut = direction.isOut();
    }

    public static Direction resolve(DcSpan span) {
        if (span == null) {
            return Direction.NONE;
        }
        if (isDbOrRedisSpan(span)) {
            return new Direction(1, 1);
        }
        Direction http = httpDirection(span);
        if (http.isIn() != 0 || http.isOut() != 0) {
            return http;
        }
        if (isRpcSpan(span)) {
            // SERVER → isIn, CLIENT → isOut (legacy TRACE_IN_NAMES_RPC / TRACE_OUT_NAMES_RPC).
            return kindDirection(span, true, true);
        }
        if (isMqSpan(span)) {
            if (isMqConsume(span)) {
                return new Direction(1, 1);
            }
            // PRODUCER (OTel) and CLIENT Exit (SkyWalking) publishers; Callbacks stay NONE.
            if (isClientKind(span) || isProducerKind(span) || isMqPublish(span)) {
                return new Direction(0, 1);
            }
        }
        return Direction.NONE;
    }

    public static Direction httpDirection(DcSpan span) {
        if (!isHttpSpan(span)) {
            return Direction.NONE;
        }
        if (isServerKind(span)) {
            return new Direction(1, 0);
        }
        if (isClientKind(span)) {
            return new Direction(0, 1);
        }
        String name = spanName(span);
        if (name.startsWith("HTTP ")) {
            return new Direction(0, 1);
        }
        String url = span.metaHttpUrl != null ? span.metaHttpUrl.trim() : "";
        if (url.contains("://")) {
            return new Direction(0, 1);
        }
        return Direction.NONE;
    }

    public static void applyDirectionTags(DcSpan span, java.util.Map<String, String> tags) {
        Direction direction = resolve(span);
        if (direction.isIn() == 0 && direction.isOut() == 0) {
            tags.put("isIn", String.valueOf(span.isIn));
            tags.put("isOut", String.valueOf(span.isOut));
            return;
        }
        tags.put("isIn", String.valueOf(direction.isIn()));
        tags.put("isOut", String.valueOf(direction.isOut()));
    }

    private static Direction kindDirection(DcSpan span, boolean serverInbound, boolean clientOutbound) {
        if (isServerKind(span)) {
            return serverInbound ? new Direction(1, 0) : Direction.NONE;
        }
        if (isClientKind(span)) {
            return clientOutbound ? new Direction(0, 1) : Direction.NONE;
        }
        return Direction.NONE;
    }

    private static boolean isServerKind(DcSpan span) {
        return "SPAN_KIND_SERVER".equals(span.type);
    }

    private static boolean isClientKind(DcSpan span) {
        return "SPAN_KIND_CLIENT".equals(span.type);
    }

    private static boolean isProducerKind(DcSpan span) {
        return "SPAN_KIND_PRODUCER".equals(span.type);
    }

    private static boolean isHttpSpan(DcSpan span) {
        return !DcSpanUtil.hasDbSystem(span) && DcSpanUtil.isHttpSpan(span);
    }

    private static boolean isRpcSpan(DcSpan span) {
        return DcSpanUtil.isRpcSpan(span);
    }

    private static boolean isDbOrRedisSpan(DcSpan span) {
        if (isHttpSpan(span) || isRpcSpan(span)) {
            return false;
        }
        return hasDbSystem(span);
    }

    private static boolean hasDbSystem(DcSpan span) {
        return DcSpanUtil.hasDbSystem(span);
    }

    private static boolean isMqSpan(DcSpan span) {
        return DcSpanUtil.isMqSpan(span);
    }

    private static boolean isMqConsume(java.util.Map<String, String> meta) {
        String operation = OtelAttributeMaps.firstNonBlank(meta, "messaging.operation");
        if (operation == null) {
            return false;
        }
        String lower = operation.toLowerCase();
        return lower.contains("receive") || lower.contains("process");
    }

    private static boolean isMqPublish(java.util.Map<String, String> meta) {
        String operation = OtelAttributeMaps.firstNonBlank(meta, "messaging.operation");
        if (operation == null) {
            return false;
        }
        String lower = operation.toLowerCase();
        return lower.contains("publish") || lower.equals("send") || lower.endsWith(".send");
    }

    private static boolean isMqConsume(DcSpan span) {
        return isMqConsume(OtelAttributeMaps.parse(span));
    }

    private static boolean isMqPublish(DcSpan span) {
        return isMqPublish(OtelAttributeMaps.parse(span));
    }

    private static String spanName(DcSpan span) {
        if (span.name != null && !span.name.isBlank()) {
            return span.name.trim();
        }
        return span.resource != null ? span.resource.trim() : "";
    }
}
