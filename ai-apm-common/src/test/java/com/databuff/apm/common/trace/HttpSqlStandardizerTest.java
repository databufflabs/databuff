package com.databuff.apm.common.trace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpSqlStandardizerTest {

    @Test
    void standardizeSqlModeOneReplacesValuesContainingDigits() {
        String sql = "select * from dc_db where apiKey = 'HW274HYFH2492H' "
                + "and startTriggerTime <= 1710224793 and lastTriggerTime >= 1710226306";

        assertThat(HttpSqlStandardizer.standardizeSql(sql, 1))
                .isEqualTo("select * from dc_db where apiKey = ? "
                        + "and startTriggerTime <= ? and lastTriggerTime >= ?");
    }

    @Test
    void standardizeSqlWithParametersCollectsReplacedLiterals() {
        String sql = "select * from dc_db where apiKey = 'HW274HYFH2492H' "
                + "and startTriggerTime <= 1710224793 and lastTriggerTime >= 1710226306";

        HttpSqlStandardizer.SqlNormalizeResult result =
                HttpSqlStandardizer.standardizeSqlWithParameters(sql, 1);

        assertThat(result.sql()).isEqualTo("select * from dc_db where apiKey = ? "
                + "and startTriggerTime <= ? and lastTriggerTime >= ?");
        assertThat(result.parameters())
                .containsExactly("HW274HYFH2492H", "1710224793", "1710226306");
    }

    @Test
    void standardizeSqlWithParametersModeMinusOneReturnsEmptyParams() {
        String sql = "SELECT id FROM demo_order WHERE id = 10001";
        HttpSqlStandardizer.SqlNormalizeResult result =
                HttpSqlStandardizer.standardizeSqlWithParameters(sql, -1);
        assertThat(result.sql()).isEqualTo(sql);
        assertThat(result.parameters()).isEmpty();
    }

    @Test
    void standardizeSqlModeZeroKeepsStringLiteralsStartingWithLetters() {
        String sql = "select * from dc_db where apiKey = 'HW274HYFH2492H' "
                + "and startTriggerTime <= 1710224793 and lastTriggerTime >= 1710226306";

        assertThat(HttpSqlStandardizer.standardizeSql(sql, 0))
                .isEqualTo("select * from dc_db where apiKey = 'HW274HYFH2492H' "
                        + "and startTriggerTime <= ? and lastTriggerTime >= ?");
    }

    @Test
    void standardizeSqlModeMinusOneLeavesSqlUntouched() {
        String sql = "SELECT id FROM demo_order WHERE id = 10001";
        assertThat(HttpSqlStandardizer.standardizeSql(sql, -1)).isEqualTo(sql);
    }

    @Test
    void standardizeSqlCollapsesLongInLists() {
        String sql = "SELECT id FROM t WHERE status IN ('2025-07-18 16:41:00', '2025-07-18 15:41:00')";
        assertThat(HttpSqlStandardizer.standardizeSql(sql, 1))
                .isEqualTo("SELECT id FROM t WHERE status IN (?)");
    }

    @Test
    void standardizeSqlHandlesQuotedCommaInFunctionArguments() {
        String sql = "SELECT CONCAT(',', name) FROM users WHERE id = 42";

        HttpSqlStandardizer.SqlNormalizeResult result =
                HttpSqlStandardizer.standardizeSqlWithParameters(sql, 1);

        assertThat(result.sql()).isEqualTo("SELECT CONCAT(',', name) FROM users WHERE id = ?");
        assertThat(result.parameters()).containsExactly("42");
    }

    @Test
    void standardizeSqlDoesNotSplitQuotedCommaContainingDigits() {
        String sql = "SELECT CONCAT('2026,08', name) FROM users WHERE id = 42";

        HttpSqlStandardizer.SqlNormalizeResult result =
                HttpSqlStandardizer.standardizeSqlWithParameters(sql, 1);

        assertThat(result.sql()).isEqualTo("SELECT CONCAT(?, name) FROM users WHERE id = ?");
        assertThat(result.parameters()).containsExactly("2026,08", "42");
    }

    @Test
    void standardizeSqlKeepsFunctionAndIdentifierNamesContainingDigits() {
        String sql = "SELECT MD5(5), COALESCE(col1, col2) FROM users";

        HttpSqlStandardizer.SqlNormalizeResult result =
                HttpSqlStandardizer.standardizeSqlWithParameters(sql, 1);

        assertThat(result.sql()).isEqualTo("SELECT MD5(?), COALESCE(col1, col2) FROM users");
        assertThat(result.parameters()).containsExactly("5");
    }

    @Test
    void standardizeSqlDoesNotTreatComparisonIdentifiersAsValues() {
        String sql = "SELECT * FROM t WHERE col1 = col2";

        HttpSqlStandardizer.SqlNormalizeResult result =
                HttpSqlStandardizer.standardizeSqlWithParameters(sql, 1);

        assertThat(result.sql()).isEqualTo(sql);
        assertThat(result.parameters()).isEmpty();
    }

    @Test
    void standardizeSqlReplacesWholeStringInsteadOfTextInsideIt() {
        HttpSqlStandardizer.SqlNormalizeResult result =
                HttpSqlStandardizer.standardizeSqlWithParameters("SELECT 'a=123' AS label FROM t", 1);

        assertThat(result.sql()).isEqualTo("SELECT ? AS label FROM t");
        assertThat(result.parameters()).containsExactly("a=123");
    }

    @Test
    void standardizeSqlCollectsParametersInPlaceholderOrder() {
        String sql = "SELECT CONCAT('x1', name) FROM users WHERE id = 42";

        HttpSqlStandardizer.SqlNormalizeResult result =
                HttpSqlStandardizer.standardizeSqlWithParameters(sql, 1);

        assertThat(result.sql()).isEqualTo("SELECT CONCAT(?, name) FROM users WHERE id = ?");
        assertThat(result.parameters()).containsExactly("x1", "42");
    }

    @Test
    void standardizeSqlKeepsCollapsedInListInParameterOrder() {
        String sql = "SELECT * FROM t WHERE a = 1 AND id IN (2, 3) AND b = 4";

        HttpSqlStandardizer.SqlNormalizeResult result =
                HttpSqlStandardizer.standardizeSqlWithParameters(sql, 1);

        assertThat(result.sql()).isEqualTo("SELECT * FROM t WHERE a = ? AND id IN (?) AND b = ?");
        assertThat(result.parameters()).containsExactly("1", "2, 3", "4");
    }

    @Test
    void standardizeSqlHandlesSignedDecimalAndExponentNumbers() {
        String sql = "SELECT * FROM t WHERE amount = -42.5 AND ratio = 1.2e-3";

        HttpSqlStandardizer.SqlNormalizeResult result =
                HttpSqlStandardizer.standardizeSqlWithParameters(sql, 1);

        assertThat(result.sql()).isEqualTo("SELECT * FROM t WHERE amount = ? AND ratio = ?");
        assertThat(result.parameters()).containsExactly("-42.5", "1.2e-3");
    }

    @Test
    void standardizeSqlIgnoresCommentsAndQuotedIdentifiers() {
        String sql = "SELECT \"col1\" FROM t -- keep 123\nWHERE id = 42 /* keep 456 */";

        HttpSqlStandardizer.SqlNormalizeResult result =
                HttpSqlStandardizer.standardizeSqlWithParameters(sql, 1);

        assertThat(result.sql())
                .isEqualTo("SELECT \"col1\" FROM t -- keep 123\nWHERE id = ? /* keep 456 */");
        assertThat(result.parameters()).containsExactly("42");
    }

    @Test
    void standardizeSqlFailsOpenForUnclosedQuotes() {
        String sql = "SELECT * FROM t WHERE label = 'broken 123";

        HttpSqlStandardizer.SqlNormalizeResult result =
                HttpSqlStandardizer.standardizeSqlWithParameters(sql, 1);

        assertThat(result.sql()).isEqualTo(sql);
        assertThat(result.parameters()).isEmpty();
    }

    @Test
    void standardizeHttpRequestLineModes() {
        String line = "GET /api/orders/12345?foo=bar HTTP/1.1";
        assertThat(HttpSqlStandardizer.standardizeHttpRequestLine(line, 1))
                .isEqualTo("GET /api/orders/? HTTP/1.1");
        assertThat(HttpSqlStandardizer.standardizeHttpRequestLine(line, -1)).isEqualTo(line);
        assertThat(HttpSqlStandardizer.standardizeHttpRequestLine(null, 1)).isNull();
    }
}
