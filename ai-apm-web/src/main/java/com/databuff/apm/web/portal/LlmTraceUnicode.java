package com.databuff.apm.web.portal;

import java.util.Map;

/**
 * Query-time decode of backslash-u + 4 hex digits on LLM spans.
 * <p>
 * A span is LLM when any attribute name starts with {@code gen_ai.}, {@code gen.ai.},
 * or {@code llm.}. Then every string value on that span is scanned once. No JSON parse.
 */
public final class LlmTraceUnicode {

    private static final String[] PREFIXES = {"gen_ai.", "gen.ai.", "llm."};

    private LlmTraceUnicode() {
    }

    /** Decode all meta values in place when the span has an LLM attribute prefix. */
    public static void decodeMetaIfLlmSpan(Map<String, String> meta) {
        if (meta == null || meta.isEmpty() || !hasLlmAttributePrefix(meta)) {
            return;
        }
        for (Map.Entry<String, String> entry : meta.entrySet()) {
            String value = entry.getValue();
            if (value == null) {
                continue;
            }
            String decoded = decodeUnicodeEscapes(value);
            if (decoded != value) {
                entry.setValue(decoded);
            }
        }
    }

    static boolean hasLlmAttributePrefix(Map<String, String> meta) {
        for (String key : meta.keySet()) {
            if (hasLlmAttributePrefix(key)) {
                return true;
            }
        }
        return false;
    }

    static boolean hasLlmAttributePrefix(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        for (String prefix : PREFIXES) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    static String decodeUnicodeEscapes(String input) {
        if (input == null) {
            return input;
        }
        int first = input.indexOf('\\');
        if (first < 0) {
            return input;
        }
        StringBuilder out = new StringBuilder(input.length());
        out.append(input, 0, first);
        boolean changed = false;
        for (int i = first, n = input.length(); i < n; ) {
            if (i + 5 < n && input.charAt(i) == '\\' && input.charAt(i + 1) == 'u') {
                int code = parseHex4(input, i + 2);
                if (code >= 0) {
                    out.append((char) code);
                    i += 6;
                    changed = true;
                    continue;
                }
            }
            out.append(input.charAt(i));
            i++;
        }
        return changed ? out.toString() : input;
    }

    private static int parseHex4(String s, int offset) {
        int code = 0;
        for (int i = 0; i < 4; i++) {
            int digit = hexDigit(s.charAt(offset + i));
            if (digit < 0) {
                return -1;
            }
            code = (code << 4) | digit;
        }
        return code;
    }

    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }
}
