package com.databuff.apm.common.trace;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL / HTTP request-line normalization for third-party trace sources (SkyWalking, OTel without agent config).
 * <p>
 * Modes mirror legacy portal {@code sql_normalized_type} / {@code url_path_normalized_type}:
 * {@code -1} no change; {@code 0} replace values that start with a digit; {@code 1} replace values containing a digit.
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
        if (sql == null || mode == -1) {
            return sql;
        }

        String processed = processInClauses(sql, mode);
        processed = processComparisons(processed, mode);
        return processFunctionArgs(processed, mode);
    }

    private static String processInClauses(String sql, int mode) {
        StringBuffer result = new StringBuffer();
        Matcher matcher = IN_PATTERN.matcher(sql);

        while (matcher.find()) {
            String values = matcher.group(1);
            String replacement;

            if (shouldReplaceValues(values, mode)) {
                replacement = "IN (?)";
            } else {
                StringBuilder newValues = new StringBuilder();
                String[] items = values.split(LIST_SEPARATOR);

                for (String item : items) {
                    if (newValues.length() > 0) {
                        newValues.append(',');
                    }
                    newValues.append(shouldReplaceValue(item, mode) ? "?" : item);
                }
                replacement = "IN (" + newValues + ")";
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    private static String processComparisons(String sql, int mode) {
        StringBuffer result = new StringBuffer();
        Matcher matcher = COMPARISON_PATTERN.matcher(sql);

        while (matcher.find()) {
            String operator = matcher.group("operator");
            String value = matcher.group("value");
            String replacement = shouldReplaceValue(value, mode) ? operator + " ?" : matcher.group();
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    private static String processFunctionArgs(String sql, int mode) {
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
                newArgs.append(shouldReplaceValue(part, mode) ? "?" : part);
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
        String unquoted = value;
        if ((value.startsWith("'") && value.endsWith("'"))
                || (value.startsWith("\"") && value.endsWith("\""))) {
            unquoted = value.substring(1, value.length() - 1);
        }

        if (mode == 0) {
            return !unquoted.isEmpty() && Character.isDigit(unquoted.charAt(0));
        }
        if (mode == 1) {
            return containsDigit(unquoted);
        }
        return false;
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
