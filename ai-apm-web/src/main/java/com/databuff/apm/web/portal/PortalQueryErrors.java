package com.databuff.apm.web.portal;

import com.databuff.apm.web.config.common.CommonResponse;

import java.util.Map;

/**
 * Maps arbitrary query/storage failures to portal-friendly {@link CommonResponse#fail} envelopes.
 * Does not introduce a dedicated exception type — callers catch {@link Exception}/{@link Throwable}
 * at the API boundary and convert here.
 */
public final class PortalQueryErrors {

    private PortalQueryErrors() {
    }

    public static Map<String, Object> fail(String operation, Throwable error) {
        return CommonResponse.fail(500, userMessage(operation, error));
    }

    public static String userMessage(String operation, Throwable error) {
        String op = blankToDefault(operation, "查询");
        String raw = rootMessage(error);
        String lower = raw.toLowerCase();
        if (containsAny(lower, "version_graph", "fail to find path in version_graph", "missing_rowsets")) {
            return op + "失败：链路存储异常（Doris tablet 版本链损坏），请联系管理员修复后重试";
        }
        if (containsAny(lower, "succ replica num 0", "replica num 0", "not enough replica")) {
            return op + "失败：链路存储副本异常，请联系管理员检查 Doris 后重试";
        }
        if (containsAny(lower,
                "communications link failure",
                "connection refused",
                "connect timed out",
                "connection reset",
                "no route to host",
                "doris is unavailable",
                "storage unavailable")) {
            return op + "失败：存储服务暂时不可用，请稍后重试";
        }
        if (raw.isBlank()) {
            return op + "失败：存储查询异常，请稍后重试";
        }
        return op + "失败：" + truncate(raw, 180);
    }

    /** Propagate unchecked; wrap checked so callers need not declare throws. */
    public static RuntimeException propagate(Throwable error) {
        if (error instanceof RuntimeException runtime) {
            return runtime;
        }
        return new RuntimeException(error);
    }

    private static String rootMessage(Throwable error) {
        if (error == null) {
            return "";
        }
        Throwable current = error;
        String best = safeText(current.getMessage());
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
            String next = safeText(current.getMessage());
            if (!next.isBlank()) {
                best = next;
            }
        }
        return best;
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, Math.max(0, max - 1)) + "…";
    }
}
