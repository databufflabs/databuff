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
    void mutableCopyReplaceAndMaterialize() {
        DcSpan span = new DcSpan();
        span.meta = "{\"k\":\"v\"}";
        Map<String, String> copy = OtelAttributeMaps.mutableCopy(span);
        copy.put("k2", "v2");
        OtelAttributeMaps.replace(span, copy);
        assertThat(span.metaAttributesDirty).isTrue();
        OtelAttributeMaps.materialize(span);
        assertThat(span.meta).contains("k2");
        assertThat(span.metaAttributesDirty).isFalse();
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
    void cacheSkipsNullSpan() {
        OtelAttributeMaps.cache(null, Map.of("k", "v"));
        OtelAttributeMaps.replace(null, Map.of());
        OtelAttributeMaps.materialize(null);
    }
}
