package com.databuff.apm.web.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DorisReadQueryServiceTest {

    @Test
    void acceptsReadOnlyStatements() {
        DorisReadQueryService.validateReadOnly(DorisReadQueryService.normalizeSql("SELECT 1"));
        DorisReadQueryService.validateReadOnly(DorisReadQueryService.normalizeSql("show tables"));
        DorisReadQueryService.validateReadOnly(DorisReadQueryService.normalizeSql("DESCRIBE config_ai_tool"));
        DorisReadQueryService.validateReadOnly(DorisReadQueryService.normalizeSql(
                "WITH t AS (SELECT 1 AS x) SELECT * FROM t"));
        DorisReadQueryService.validateReadOnly(DorisReadQueryService.normalizeSql(
                "SHOW CREATE TABLE config_ai_tool"));
    }

    @Test
    void allowsForbiddenWordsInsideStringLiterals() {
        assertThatCode(() -> DorisReadQueryService.validateReadOnly(
                DorisReadQueryService.normalizeSql(
                        "SELECT * FROM config_ai_tool WHERE name LIKE '%SET%'")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsWritesAndMultiStatements() {
        assertThatThrownBy(() -> DorisReadQueryService.validateReadOnly(
                DorisReadQueryService.normalizeSql("DELETE FROM config_ai_tool")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DorisReadQueryService.validateReadOnly(
                DorisReadQueryService.normalizeSql("INSERT INTO t VALUES (1)")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DorisReadQueryService.validateReadOnly(
                DorisReadQueryService.normalizeSql("SELECT * FROM t INTO OUTFILE '/tmp/x'")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DorisReadQueryService.normalizeSql("SELECT 1; SELECT 2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("single");
    }

    @Test
    void stripsCommentsAndTrailingSemicolon() {
        String sql = DorisReadQueryService.normalizeSql("""
                /* note */
                SELECT tool_id -- comment
                FROM config_ai_tool;
                """);
        assertThat(sql).isEqualToIgnoringWhitespace("SELECT tool_id FROM config_ai_tool");
    }

    @Test
    void capsMaxRows() {
        assertThat(DorisReadQueryService.normalizeMaxRows(null))
                .isEqualTo(DorisReadQueryService.DEFAULT_MAX_ROWS);
        assertThat(DorisReadQueryService.normalizeMaxRows(50)).isEqualTo(50);
        assertThat(DorisReadQueryService.normalizeMaxRows(99999))
                .isEqualTo(DorisReadQueryService.HARD_MAX_ROWS);
    }
}
