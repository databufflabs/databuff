package com.databuff.apm.common.trace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpSqlStandardizerTest {

    @Test
    void standardizeSqlModeOneReplacesValuesContainingDigits() {
        String sql = "select * from dc_db where apiKey = HW274HYFH2492H "
                + "and startTriggerTime <= 1710224793 and lastTriggerTime >= 1710226306";

        assertThat(HttpSqlStandardizer.standardizeSql(sql, 1))
                .isEqualTo("select * from dc_db where apiKey = ? "
                        + "and startTriggerTime <= ? and lastTriggerTime >= ?");
    }

    @Test
    void standardizeSqlModeZeroKeepsStringLiteralsStartingWithLetters() {
        String sql = "select * from dc_db where apiKey = HW274HYFH2492H "
                + "and startTriggerTime <= 1710224793 and lastTriggerTime >= 1710226306";

        assertThat(HttpSqlStandardizer.standardizeSql(sql, 0))
                .isEqualTo("select * from dc_db where apiKey = HW274HYFH2492H "
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
}
