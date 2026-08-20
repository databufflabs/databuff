package com.databuff.apm.web.portal;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmTraceUnicodeTest {

    /** Runtime backslash + u + hex; split so javac does not treat it as a source escape. */
    private static String u(String hex) {
        return "\\" + "u" + hex;
    }

    @Test
    void decodesEscapesWhenPrefixPresent() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("gen.ai.prompt", u("4e2d") + u("6587"));
        meta.put("http.body", "{\"content\":\"" + u("4e2d") + u("6587") + "\"}");
        meta.put("http.response.body", u("4e2d") + u("6587"));

        LlmTraceUnicode.decodeMetaIfLlmSpan(meta);

        assertThat(meta.get("gen.ai.prompt")).isEqualTo("中文");
        assertThat(meta.get("http.body")).isEqualTo("{\"content\":\"中文\"}");
        assertThat(meta.get("http.response.body")).isEqualTo("中文");
    }

    @Test
    void leavesPlainChineseUnchanged() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("gen_ai.completion", "已经是中文");
        meta.put("mixed", "明文中文 and " + u("4E2D") + u("6587"));

        LlmTraceUnicode.decodeMetaIfLlmSpan(meta);

        assertThat(meta.get("gen_ai.completion")).isEqualTo("已经是中文");
        assertThat(meta.get("mixed")).isEqualTo("明文中文 and 中文");

        LlmTraceUnicode.decodeMetaIfLlmSpan(meta);

        assertThat(meta.get("gen_ai.completion")).isEqualTo("已经是中文");
        assertThat(meta.get("mixed")).isEqualTo("明文中文 and 中文");
    }

    @Test
    void doesNotDecodeWhenPrefixMissing() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("http.body", u("4e2d") + u("6587"));
        meta.put("db.statement", "SELECT " + u("4e2d"));

        LlmTraceUnicode.decodeMetaIfLlmSpan(meta);

        assertThat(meta.get("http.body")).isEqualTo(u("4e2d") + u("6587"));
        assertThat(meta.get("db.statement")).isEqualTo("SELECT " + u("4e2d"));
    }

    @Test
    void recognizesAllThreePrefixes() {
        assertThat(LlmTraceUnicode.hasLlmAttributePrefix("gen_ai.prompt")).isTrue();
        assertThat(LlmTraceUnicode.hasLlmAttributePrefix("gen.ai.response")).isTrue();
        assertThat(LlmTraceUnicode.hasLlmAttributePrefix("llm.model")).isTrue();
        assertThat(LlmTraceUnicode.hasLlmAttributePrefix("http.url")).isFalse();
        assertThat(LlmTraceUnicode.hasLlmAttributePrefix("llm")).isFalse();
        assertThat(LlmTraceUnicode.hasLlmAttributePrefix("x.gen_ai.foo")).isFalse();
    }

    @Test
    void acceptsUpperAndLowerHexAndLeavesBrokenSequences() {
        assertThat(LlmTraceUnicode.decodeUnicodeEscapes(u("4E2D") + u("4e2d"))).isEqualTo("中中");
        assertThat(LlmTraceUnicode.decodeUnicodeEscapes(u("4e2"))).isEqualTo(u("4e2"));
        assertThat(LlmTraceUnicode.decodeUnicodeEscapes(u("ZZZZ"))).isEqualTo(u("ZZZZ"));
        assertThat(LlmTraceUnicode.decodeUnicodeEscapes(u("4e2X") + " rest")).isEqualTo(u("4e2X") + " rest");
        assertThat(LlmTraceUnicode.decodeUnicodeEscapes("no escapes")).isEqualTo("no escapes");
        assertThat(LlmTraceUnicode.decodeUnicodeEscapes(null)).isNull();
    }

    @Test
    void decodesOnlyOnce() {
        String once = LlmTraceUnicode.decodeUnicodeEscapes(u("005c") + u("0075") + "4e2d");
        assertThat(once).isEqualTo(u("4e2d"));
        assertThat(LlmTraceUnicode.decodeUnicodeEscapes(once)).isEqualTo("中");
    }
}
