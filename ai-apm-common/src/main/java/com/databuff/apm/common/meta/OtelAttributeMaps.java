package com.databuff.apm.common.meta;

import com.databuff.apm.common.model.DcSpan;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Parse OTLP attribute maps stored on spans / metric lines.
 * <p>
 * Ingest pipeline model: {@link DcSpan#metaMap} is the single working copy of the OTel
 * attributes; {@link DcSpan#meta} (the JSON string) is materialized only at IO boundaries
 * (encoder / decoder / web read path). There is no dirty flag — the map is always truth,
 * and {@link #materialize(DcSpan)} re-encodes it on demand.
 */
public final class OtelAttributeMaps {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };
    private static final ThreadLocal<StringBuilder> ENCODE_BUF =
            ThreadLocal.withInitial(() -> new StringBuilder(512));
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private OtelAttributeMaps() {
    }

    public static Map<String, String> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> parsed = JSON.readValue(json, STRING_MAP);
            return parsed == null ? Map.of() : parsed;
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    /**
     * Return the span's working attribute map, lazily building it from {@link DcSpan#meta}
     * on first access. The returned map is mutable and owned by the span (single-threaded
     * per trace); callers may read or mutate it directly. Once built, the map is the single
     * source of truth — external mutations of {@link DcSpan#meta} are not re-parsed.
     */
    public static Map<String, String> parse(DcSpan span) {
        if (span == null) {
            return Map.of();
        }
        if (span.metaMap != null) {
            return span.metaMap;
        }
        Map<String, String> parsed = new LinkedHashMap<>(parse(span.meta));
        span.metaMap = parsed;
        return parsed;
    }

    /** Alias of {@link #parse(DcSpan)} for readability at call sites that treat it as the meta source. */
    public static Map<String, String> meta(DcSpan span) {
        return parse(span);
    }

    /** Attach a pre-built attribute map as the span's working copy (convert path). */
    public static void cache(DcSpan span, Map<String, String> attributes) {
        if (span == null) {
            return;
        }
        span.metaMap = attributes == null ? new LinkedHashMap<>() : attributes;
        span.meta = null;
        span.metaRevision++;
        span.analysisCache = null;
    }

    /** Replace the span's working map (force). */
    public static void replace(DcSpan span, Map<String, String> attributes) {
        if (span == null) {
            return;
        }
        span.metaMap = attributes == null || attributes.isEmpty() ? new LinkedHashMap<>() : attributes;
        span.meta = null;
        span.metaRevision++;
        span.analysisCache = null;
    }

    /**
     * Materialize {@link DcSpan#meta} from the working map, if a working map exists.
     * Called at IO boundaries (encoder / test fillBytes). No-op when the span only has a
     * meta string (e.g. decoded from Doris) and no working map was built.
     */
    public static void materialize(DcSpan span) {
        if (span == null || span.metaMap == null) {
            return;
        }
        span.meta = encode(span.metaMap);
    }

    /**
     * Encode attribute map to a compact JSON object string.
     * Hand-rolled (no {@link ObjectMapper}) for the ingest Stream Load hot path — profiling
     * showed {@code writeValueAsString} as a top alloc/CPU frame when materializing every span.
     */
    public static String encode(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        StringBuilder sb = ENCODE_BUF.get();
        sb.setLength(0);
        appendJsonObject(sb, attributes);
        return sb.toString();
    }

    /** Append {@code {"k":"v",...}} for maps that are already known non-null/non-empty. */
    private static void appendJsonObject(StringBuilder sb, Map<String, String> attributes) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, String> e : attributes.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (key == null || value == null) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            appendJsonString(sb, key);
            sb.append(':');
            appendJsonString(sb, value);
        }
        sb.append('}');
    }

    private static void appendJsonString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append('\\').append('"');
                case '\\' -> sb.append('\\').append('\\');
                case '\b' -> sb.append('\\').append('b');
                case '\f' -> sb.append('\\').append('f');
                case '\n' -> sb.append('\\').append('n');
                case '\r' -> sb.append('\\').append('r');
                case '\t' -> sb.append('\\').append('t');
                default -> {
                    if (c < 0x20) {
                        sb.append('\\').append('u');
                        sb.append(HEX[(c >> 12) & 0xf]);
                        sb.append(HEX[(c >> 8) & 0xf]);
                        sb.append(HEX[(c >> 4) & 0xf]);
                        sb.append(HEX[c & 0xf]);
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    public static String firstNonBlank(Map<String, String> attributes, String... keys) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            String value = attributes.get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    // ----------------------------------------------------------------------
    // In-place mutation API (ingest fill hot path).
    //
    // These operate directly on the span's working map. No defensive copy, no dirty flag:
    // the map is truth, and materialize() re-encodes at the boundary. Each mutation bumps
    // metaRevision and clears analysisCache so SpanAnalysis never serves stale results.
    // ----------------------------------------------------------------------

    /** Set {@code key} to {@code value} only if current value is missing or blank. */
    public static boolean putIfBlank(DcSpan span, String key, String value) {
        if (span == null || key == null) {
            return false;
        }
        if (value == null || value.isBlank()) {
            return false;
        }
        Map<String, String> m = parse(span);
        String current = m.get(key);
        if (current != null && !current.isBlank()) {
            return false;
        }
        m.put(key, value);
        bumpRevision(span);
        return true;
    }

    /** Force-set {@code key} to {@code value} (overwrites existing). */
    public static void put(DcSpan span, String key, String value) {
        if (span == null || key == null || value == null) {
            return;
        }
        parse(span).put(key, value);
        bumpRevision(span);
    }

    /** Group multiple conditional writes; revision bumped once if anything changed. */
    public static void edit(DcSpan span, Consumer<MetaEditor> editor) {
        if (span == null || editor == null) {
            return;
        }
        DefaultMetaEditor e = new DefaultMetaEditor(parse(span));
        editor.accept(e);
        if (e.changed) {
            bumpRevision(span);
        }
    }

    /** Editor handle for {@link #edit(DcSpan, Consumer)}. */
    public interface MetaEditor {
        void put(String key, String value);
        boolean putIfBlank(String key, String value);
        boolean isBlank(String key);
    }

    private static final class DefaultMetaEditor implements MetaEditor {
        private final Map<String, String> meta;
        private boolean changed;

        DefaultMetaEditor(Map<String, String> meta) {
            this.meta = meta;
        }

        @Override
        public void put(String key, String value) {
            if (value == null) {
                return;
            }
            meta.put(key, value);
            changed = true;
        }

        @Override
        public boolean putIfBlank(String key, String value) {
            if (value == null || value.isBlank()) {
                return false;
            }
            String current = meta.get(key);
            if (current != null && !current.isBlank()) {
                return false;
            }
            meta.put(key, value);
            changed = true;
            return true;
        }

        @Override
        public boolean isBlank(String key) {
            String value = meta.get(key);
            return value == null || value.isBlank();
        }
    }

    private static void bumpRevision(DcSpan span) {
        span.metaRevision++;
        span.analysisCache = null;
    }
}
