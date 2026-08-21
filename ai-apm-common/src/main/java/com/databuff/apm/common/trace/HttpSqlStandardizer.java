package com.databuff.apm.common.trace;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL / HTTP request-line normalization for third-party trace sources (SkyWalking, OTel without agent config).
 * <p>
 * Modes mirror legacy portal {@code sql_normalized_type} / {@code url_path_normalized_type}:
 * {@code -1} no change; {@code 0} replace values that start with a digit; {@code 1} replace values containing a digit.
 * SQL identifiers are never treated as values merely because their names contain digits.
 * <p>
 * When SQL values are replaced with {@code ?}, the original values are collected in order for
 * OTel {@code db.query.parameter.<index>} (0-based).
 */
public final class HttpSqlStandardizer {

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
        if (mode != 0 && mode != 1) {
            return new SqlNormalizeResult(sql, List.of());
        }
        return new SqlLiteralScanner(sql, mode).normalize();
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
        if (trimmed.length() >= 2
                && ((trimmed.startsWith("'") && trimmed.endsWith("'"))
                || (trimmed.startsWith("\"") && trimmed.endsWith("\"")))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * A deliberately small SQL lexer. It recognizes comments, quoted regions and literal tokens,
     * but does not try to parse SQL grammar. This keeps normalization conservative while ensuring
     * replacements and collected parameters are emitted in the same left-to-right pass.
     */
    private static final class SqlLiteralScanner {
        private final String sql;
        private final int mode;
        private final List<String> parameters = new ArrayList<>();
        private final StringBuilder normalized;

        private SqlLiteralScanner(String sql, int mode) {
            this.sql = sql;
            this.mode = mode;
            this.normalized = new StringBuilder(sql.length());
        }

        private SqlNormalizeResult normalize() {
            int index = 0;
            while (index < sql.length()) {
                int commentEnd = commentEnd(index);
                if (commentEnd >= 0) {
                    normalized.append(sql, index, commentEnd);
                    index = commentEnd;
                    continue;
                }

                char current = sql.charAt(index);
                if (current == '\'' || current == '"' || current == '`') {
                    int quotedEnd = quotedEnd(index, current);
                    if (quotedEnd < 0) {
                        return unchanged();
                    }
                    if (current == '\'') {
                        appendLiteral(sql.substring(index, quotedEnd));
                    } else {
                        // Double quotes and backticks can denote identifiers, so leave them untouched.
                        normalized.append(sql, index, quotedEnd);
                    }
                    index = quotedEnd;
                    continue;
                }

                if (isIdentifierStart(current)) {
                    int identifierEnd = identifierEnd(index);
                    if (identifierEnd < sql.length() && sql.charAt(identifierEnd) == '\'') {
                        int quotedEnd = quotedEnd(identifierEnd, '\'');
                        if (quotedEnd < 0) {
                            return unchanged();
                        }
                        appendPrefixedLiteral(index, identifierEnd, quotedEnd);
                        index = quotedEnd;
                        continue;
                    }

                    if (sql.regionMatches(true, index, "IN", 0, 2)
                            && identifierEnd - index == 2) {
                        int openParen = skipWhitespace(identifierEnd);
                        if (openParen < sql.length() && sql.charAt(openParen) == '(') {
                            int closeParen = matchingParen(openParen);
                            if (closeParen < 0) {
                                return unchanged();
                            }
                            if (appendCollapsedInList(index, openParen, closeParen)) {
                                index = closeParen + 1;
                                continue;
                            }
                        }
                    }

                    normalized.append(sql, index, identifierEnd);
                    index = identifierEnd;
                    continue;
                }

                int numberEnd = numberEnd(index);
                if (numberEnd > index) {
                    appendLiteral(sql.substring(index, numberEnd));
                    index = numberEnd;
                    continue;
                }

                normalized.append(current);
                index++;
            }
            return new SqlNormalizeResult(normalized.toString(), parameters);
        }

        private void appendLiteral(String raw) {
            if (shouldReplaceValue(raw, mode)) {
                normalized.append('?');
                parameters.add(parameterValue(raw));
            } else {
                normalized.append(raw);
            }
        }

        private void appendPrefixedLiteral(int prefixStart, int quoteStart, int quotedEnd) {
            String quoted = sql.substring(quoteStart, quotedEnd);
            if (shouldReplaceValue(quoted, mode)) {
                normalized.append('?');
                parameters.add(parameterValue(quoted));
            } else {
                normalized.append(sql, prefixStart, quotedEnd);
            }
        }

        private boolean appendCollapsedInList(int keywordStart, int openParen, int closeParen) {
            List<String> items = simpleInListItems(openParen + 1, closeParen);
            if (items.size() < 2 || !items.stream().allMatch(item -> isReplaceableLiteral(item, mode))) {
                return false;
            }
            normalized.append(sql, keywordStart, openParen + 1).append('?').append(')');
            parameters.add(sql.substring(openParen + 1, closeParen).trim());
            return true;
        }

        private List<String> simpleInListItems(int start, int end) {
            List<String> items = new ArrayList<>();
            int itemStart = start;
            int index = start;
            while (index < end) {
                char current = sql.charAt(index);
                if (current == '\'' || current == '"' || current == '`') {
                    int quotedEnd = quotedEnd(index, current);
                    if (quotedEnd < 0 || quotedEnd > end) {
                        return List.of();
                    }
                    index = quotedEnd;
                    continue;
                }
                if (current == '(' || current == ')') {
                    return List.of();
                }
                if (current == ',') {
                    String item = sql.substring(itemStart, index).trim();
                    if (item.isEmpty()) {
                        return List.of();
                    }
                    items.add(item);
                    itemStart = index + 1;
                }
                index++;
            }
            String last = sql.substring(itemStart, end).trim();
            if (last.isEmpty()) {
                return List.of();
            }
            items.add(last);
            return items;
        }

        private boolean isReplaceableLiteral(String value, int replaceMode) {
            if (value.startsWith("'")
                    && HttpSqlStandardizer.quotedEnd(value, 0, '\'') == value.length()) {
                return shouldReplaceValue(value, replaceMode);
            }
            return HttpSqlStandardizer.numberEnd(value, 0) == value.length()
                    && shouldReplaceValue(value, replaceMode);
        }

        private int commentEnd(int start) {
            if (sql.startsWith("--", start) || sql.charAt(start) == '#') {
                int newline = sql.indexOf('\n', start + 1);
                return newline < 0 ? sql.length() : newline;
            }
            if (sql.startsWith("/*", start)) {
                int close = sql.indexOf("*/", start + 2);
                return close < 0 ? sql.length() : close + 2;
            }
            return -1;
        }

        private int quotedEnd(int start, char quote) {
            return HttpSqlStandardizer.quotedEnd(sql, start, quote);
        }

        private int matchingParen(int openParen) {
            int depth = 1;
            int index = openParen + 1;
            while (index < sql.length()) {
                int commentEnd = commentEnd(index);
                if (commentEnd >= 0) {
                    index = commentEnd;
                    continue;
                }
                char current = sql.charAt(index);
                if (current == '\'' || current == '"' || current == '`') {
                    int quotedEnd = quotedEnd(index, current);
                    if (quotedEnd < 0) {
                        return -1;
                    }
                    index = quotedEnd;
                    continue;
                }
                if (current == '(') {
                    depth++;
                } else if (current == ')' && --depth == 0) {
                    return index;
                }
                index++;
            }
            return -1;
        }

        private int identifierEnd(int start) {
            int index = start + 1;
            while (index < sql.length() && isIdentifierPart(sql.charAt(index))) {
                index++;
            }
            return index;
        }

        private int skipWhitespace(int start) {
            int index = start;
            while (index < sql.length() && Character.isWhitespace(sql.charAt(index))) {
                index++;
            }
            return index;
        }

        private int numberEnd(int start) {
            return HttpSqlStandardizer.numberEnd(sql, start);
        }

        private SqlNormalizeResult unchanged() {
            return new SqlNormalizeResult(sql, List.of());
        }
    }

    private static int quotedEnd(String value, int start, char quote) {
        int index = start + 1;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '\\' && index + 1 < value.length()) {
                index += 2;
                continue;
            }
            if (current == quote) {
                if (index + 1 < value.length() && value.charAt(index + 1) == quote) {
                    index += 2;
                    continue;
                }
                return index + 1;
            }
            index++;
        }
        return -1;
    }

    private static int numberEnd(String value, int start) {
        if (start >= value.length()) {
            return start;
        }
        int index = start;
        char first = value.charAt(index);
        if ((first == '+' || first == '-') && index + 1 < value.length()
                && Character.isDigit(value.charAt(index + 1))) {
            index++;
        } else if (!Character.isDigit(first)
                && !(first == '.' && index + 1 < value.length()
                && Character.isDigit(value.charAt(index + 1)))) {
            return start;
        }
        if (start > 0 && isIdentifierPart(value.charAt(start - 1))) {
            return start;
        }
        if (index + 1 < value.length() && value.charAt(index) == '0'
                && (value.charAt(index + 1) == 'x' || value.charAt(index + 1) == 'X')) {
            index += 2;
            int digitsStart = index;
            while (index < value.length() && isHexDigit(value.charAt(index))) {
                index++;
            }
            if (digitsStart == index
                    || (index < value.length() && isIdentifierPart(value.charAt(index)))) {
                return start;
            }
            return index;
        }
        while (index < value.length() && Character.isDigit(value.charAt(index))) {
            index++;
        }
        if (index < value.length() && value.charAt(index) == '.') {
            index++;
            while (index < value.length() && Character.isDigit(value.charAt(index))) {
                index++;
            }
        }
        if (index < value.length() && (value.charAt(index) == 'e' || value.charAt(index) == 'E')) {
            int exponentStart = index;
            index++;
            if (index < value.length() && (value.charAt(index) == '+' || value.charAt(index) == '-')) {
                index++;
            }
            int digitsStart = index;
            while (index < value.length() && Character.isDigit(value.charAt(index))) {
                index++;
            }
            if (digitsStart == index) {
                index = exponentStart;
            }
        }
        if (index < value.length() && isIdentifierPart(value.charAt(index))) {
            return start;
        }
        return index;
    }

    private static boolean isIdentifierStart(char value) {
        return Character.isLetter(value) || value == '_' || value == '$';
    }

    private static boolean isIdentifierPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$';
    }

    private static boolean isHexDigit(char value) {
        return Character.isDigit(value)
                || (value >= 'a' && value <= 'f')
                || (value >= 'A' && value <= 'F');
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
