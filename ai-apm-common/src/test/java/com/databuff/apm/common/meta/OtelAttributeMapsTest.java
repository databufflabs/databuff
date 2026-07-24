package com.databuff.apm.common.meta;

import com.databuff.apm.common.model.DcSpan;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OtelAttributeMapsTest {

    @Test
    void parseBlankJsonReturnsEmpty() {
        assertThat(OtelAttributeMaps.parse((String) null)).isEmpty();
        assertThat(OtelAttributeMaps.parse("  ")).isEmpty();
        assertThat(OtelAttributeMaps.parse("{not-json")).isEmpty();
    }

    @Test
    void parseSpanCachesAttributes() {
        DcSpan span = new DcSpan();
        span.meta = "{\"service.name\":\"checkout\",\"host.name\":\"h1\"}";
        Map<String, String> first = OtelAttributeMaps.parse(span);
        Map<String, String> second = OtelAttributeMaps.parse(span);
        assertThat(first).containsEntry("service.name", "checkout");
        assertThat(second).isSameAs(first);
    }

    @Test
    void replaceAndMaterialize() {
        DcSpan span = new DcSpan();
        span.meta = "{\"k\":\"v\"}";
        Map<String, String> copy = new LinkedHashMap<>(OtelAttributeMaps.parse(span));
        copy.put("k2", "v2");
        OtelAttributeMaps.replace(span, copy);
        // replace clears meta; materialize re-encodes from the working map.
        assertThat(span.meta).isNull();
        OtelAttributeMaps.materialize(span);
        assertThat(span.meta).contains("k2");
    }

    @Test
    void encodeAndFirstNonBlank() {
        assertThat(OtelAttributeMaps.encode(Map.of())).isNull();
        assertThat(OtelAttributeMaps.encode(new LinkedHashMap<>(Map.of("a", "b")))).contains("a");
        Map<String, String> attrs = Map.of("service.name", "  checkout  ", "host.name", " ");
        assertThat(OtelAttributeMaps.firstNonBlank(attrs, "missing", "host.name", "service.name"))
                .isEqualTo("checkout");
    }

    @Test
    void encodeEscapesAndRoundTripsThroughParse() {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("http.method", "GET");
        attrs.put("quote", "say \"hi\"");
        attrs.put("path", "a\\b");
        attrs.put("multi", "line1\nline2\tend");
        attrs.put("ctrl", "\u0001");
        attrs.put("中文", "值");

        String json = OtelAttributeMaps.encode(attrs);
        assertThat(json).isEqualTo(
                "{\"http.method\":\"GET\",\"quote\":\"say \\\"hi\\\"\",\"path\":\"a\\\\b\","
                        + "\"multi\":\"line1\\nline2\\tend\",\"ctrl\":\"\\u0001\",\"中文\":\"值\"}");
        assertThat(OtelAttributeMaps.parse(json)).isEqualTo(attrs);
    }

    @Test
    void encodePreservesInsertionOrder() {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("z", "1");
        attrs.put("a", "2");
        assertThat(OtelAttributeMaps.encode(attrs)).isEqualTo("{\"z\":\"1\",\"a\":\"2\"}");
    }

    @Test
    void cacheSkipsNullSpan() {
        OtelAttributeMaps.cache(null, Map.of("k", "v"));
        OtelAttributeMaps.replace(null, Map.of());
        OtelAttributeMaps.materialize(null);
    }
}
