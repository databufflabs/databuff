package com.databuff.apm.common.trace;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL / HTTP request-line normalization for third-party trace sources (SkyWalking, OTel without agent config).
 * <p>
 * Modes mirror legacy portal {@code sql_normalized_type} / {@code url_path_normalized_type}:
 * {@code -1} no change; {@code 0} replace values that start with a digit; {@code 1} replace values containing a digit.
 * <p>
 * When SQL values are replaced with {@code ?}, the original values are collected in order for
 * OTel {@code db.query.parameter.<index>} (0-based).
 */
public final class HttpSqlStandardizer {

    private static final Pattern IN_PATTERN = Pattern.compile(
            "\\bIN\\s*\\(([^)]+)\\)",
            Pattern.CASE_INSENSITIVE);

    private static final String OPERATORS = "=|<>|!=|<=|>=|<|>";
    private static final String SINGLE_QUOTED = "'[^']*'";
    private static final String DOUBLE_QUOTED = "\"[^\"]*\"";
    private static final String INTEGER = "\\d+";
    private static final String DECIMAL = "\\d+\\.\\d+";
    private static final String IDENTIFIER = "[\\w$]+";

    private static final Pattern COMPARISON_PATTERN = Pattern.compile(
            "(?<operator>" + OPERATORS + ")" +
                    "\\s*" +
                    "(?<value>" +
                    SINGLE_QUOTED + "|" +
                    DOUBLE_QUOTED + "|" +
                    DECIMAL + "|" +
                    INTEGER + "|" +
                    IDENTIFIER +
                    ")",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FUNCTION_PATTERN = Pattern.compile(
            "\\b\\w+\\s*\\(([^)]+)\\)",
            Pattern.CASE_INSENSITIVE);

    private static final String LIST_SEPARATOR = "\\s*,\\s*";

    private HttpSqlStandardizer() {
    }

    /** Result of SQL normalize: sanitized statement + replaced literals in placeholder order. */
    public record SqlNormalizeResult(String sql, List<String> parameters) {
        public SqlNormalizeResult {
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
        }
    }

    public static String standardizeHttpRequestLine(String requestLine, int mode) {
        if (requestLine == null || mode == -1) {
            return requestLine;
        }

        String[] tokens = requestLine.split("\\s+", 3);
        if (tokens.length < 2) {
            return requestLine;
        }

        String method = tokens[0];
        String path = tokens[1];
        String version = tokens.length > 2 ? tokens[2] : null;

        String[] segments = path.split("/", -1);
        StringBuilder newPath = new StringBuilder();

        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            boolean shouldReplace = (mode == 0 && isPureDigit(segment))
                    || (mode == 1 && containsDigit(segment));

            if (i > 0) {
                newPath.append('/');
            }
            newPath.append(shouldReplace ? '?' : segment);
        }

        StringBuilder result = new StringBuilder();
        result.append(method).append(' ').append(newPath);
        if (version != null) {
            result.append(' ').append(version);
        }
        return result.toString();
    }

    public static String standardizeSql(String sql, int mode) {
        return standardizeSqlWithParameters(sql, mode).sql();
    }

    /**
     * Normalize SQL and collect replaced literal values for {@code db.query.parameter.N}.
     * When {@code mode == -1}, returns the original SQL and an empty parameter list.
     */
    public static SqlNormalizeResult standardizeSqlWithParameters(String sql, int mode) {
        if (sql == null || mode == -1) {
            return new SqlNormalizeResult(sql, List.of());
        }

        List<String> parameters = new ArrayList<>();
        String processed = processInClauses(sql, mode, parameters);
        processed = processComparisons(processed, mode, parameters);
        processed = processFunctionArgs(processed, mode, parameters);
        return new SqlNormalizeResult(processed, parameters);
    }

    private static String processInClauses(String sql, int mode, List<String> parameters) {
        StringBuffer result = new StringBuffer();
        Matcher matcher = IN_PATTERN.matcher(sql);

        while (matcher.find()) {
            String values = matcher.group(1);
            String replacement;

            if (shouldReplaceValues(values, mode)) {
                // Collapses to a single placeholder — one OTel parameter for that placeholder.
                parameters.add(parameterValue(values.trim()));
                replacement = "IN (?)";
            } else {
                StringBuilder newValues = new StringBuilder();
                String[] items = values.split(LIST_SEPARATOR);

                for (String item : items) {
                    if (newValues.length() > 0) {
                        newValues.append(',');
                    }
                    if (shouldReplaceValue(item, mode)) {
                        parameters.add(parameterValue(item.trim()));
                        newValues.append('?');
                    } else {
                        newValues.append(item);
                    }
                }
                replacement = "IN (" + newValues + ")";
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    private static String processComparisons(String sql, int mode, List<String> parameters) {
        StringBuffer result = new StringBuffer();
        Matcher matcher = COMPARISON_PATTERN.matcher(sql);

        while (matcher.find()) {
            String operator = matcher.group("operator");
            String value = matcher.group("value");
            if (shouldReplaceValue(value, mode)) {
                parameters.add(parameterValue(value));
                matcher.appendReplacement(result, Matcher.quoteReplacement(operator + " ?"));
            } else {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(result);

        return result.toString();
    }

    private static String processFunctionArgs(String sql, int mode, List<String> parameters) {
        StringBuffer result = new StringBuffer();
        Matcher matcher = FUNCTION_PATTERN.matcher(sql);

        while (matcher.find()) {
            String functionCall = matcher.group();
            String args = matcher.group(1);

            StringBuilder newArgs = new StringBuilder();
            String[] parts = args.split(LIST_SEPARATOR);

            for (String part : parts) {
                if (newArgs.length() > 0) {
                    newArgs.append(',');
                }
                if (shouldReplaceValue(part, mode)) {
                    parameters.add(parameterValue(part.trim()));
                    newArgs.append('?');
                } else {
                    newArgs.append(part);
                }
            }

            String replacement = functionCall.replace(args, newArgs.toString());
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    private static boolean shouldReplaceValues(String values, int mode) {
        if (values.length() > 50) {
            return true;
        }
        String[] items = values.split(LIST_SEPARATOR);
        for (String item : items) {
            if (shouldReplaceValue(item, mode)) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldReplaceValue(String value, int mode) {
        String unquoted = stripQuotes(value);

        if (mode == 0) {
            return !unquoted.isEmpty() && Character.isDigit(unquoted.charAt(0));
        }
        if (mode == 1) {
            return containsDigit(unquoted);
        }
        return false;
    }

    /** Unquoted literal for {@code db.query.parameter.N} (matches OTel / UI display). */
    private static String parameterValue(String raw) {
        return stripQuotes(raw.trim());
    }

    private static String stripQuotes(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if ((trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.length() >= 2)
                || (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2)) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static boolean isPureDigit(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (char c : value.toCharArray()) {
            if (!Character.isDigit(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsDigit(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isDigit(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
