package com.databuff.apm.web.storage;

import com.databuff.apm.web.config.ApmStorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DorisReadQueryServiceExecutionTest {

    private DataSource dataSource;
    private Connection connection;
    private Statement statement;
    private DorisAvailability availability;
    private DorisReadQueryService service;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        availability = mock(DorisAvailability.class);
        service = new DorisReadQueryService(
                dataSource, availability, new ApmStorageProperties("metric", "trace", "config"));
    }

    @Test
    void executesSelectAndMapsResultSet() throws Exception {
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(2);
        when(meta.getColumnLabel(1)).thenReturn("id");
        when(meta.getColumnLabel(2)).thenReturn("");
        when(meta.getColumnName(2)).thenReturn("name");
        ResultSet rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(meta);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getObject(1)).thenReturn("a", "b");
        when(rs.getObject(2)).thenReturn("x", "y");
        when(statement.execute(anyString())).thenReturn(true);
        when(statement.getResultSet()).thenReturn(rs);

        DorisReadQueryService.QueryResult result = service.query("SELECT * FROM t", 100);

        assertThat(result.columns()).containsExactly("id", "name");
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.database()).isEqualTo("config");
        assertThat(result.sql()).isEqualTo("SELECT * FROM t");
        verify(connection).setCatalog("config");
        verify(statement).setMaxRows(100);
        verify(statement).setQueryTimeout(30);
    }

    @Test
    void returnsEmptyWhenStatementHasNoResultSet() throws Exception {
        when(statement.execute(anyString())).thenReturn(false);

        DorisReadQueryService.QueryResult result = service.query("SELECT 1", null);

        assertThat(result.rows()).isEmpty();
        assertThat(result.rowCount()).isZero();
    }

    @Test
    void capsRowsToRequestedLimit() throws Exception {
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("id");
        ResultSet rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(meta);
        when(rs.next()).thenReturn(true, true, true, false);
        when(rs.getObject(1)).thenReturn("a", "b", "c");
        when(statement.execute(anyString())).thenReturn(true);
        when(statement.getResultSet()).thenReturn(rs);

        DorisReadQueryService.QueryResult result = service.query("SELECT * FROM t", 2);

        assertThat(result.rows()).hasSize(2);
    }

    @Test
    void wrapsConnectionFailureAsIllegalArgumentException() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("pool exhausted"));

        assertThatThrownBy(() -> service.query("SELECT 1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Doris query failed")
                .hasMessageContaining("pool exhausted");
    }

    @Test
    void wrapsUnavailableAsIllegalStateException() throws Exception {
        org.mockito.Mockito.doThrow(new SQLException("Doris unavailable: x", "08001", 2000))
                .when(availability).beforeConnection();

        assertThatThrownBy(() -> service.query("SELECT 1", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Doris unavailable: x");
        verify(dataSource, never()).getConnection();
    }

    @Test
    void queryResultToMapExposesAllFields() {
        DorisReadQueryService.QueryResult result = new DorisReadQueryService.QueryResult(
                List.of("id"), List.of(List.of(1)), 1, "SELECT 1", "config");

        Map<String, Object> map = result.toMap();

        assertThat(map)
                .containsEntry("database", "config")
                .containsEntry("columns", List.of("id"))
                .containsEntry("rowCount", 1)
                .containsEntry("sql", "SELECT 1");
    }

    @Test
    void rejectsBlankSql() {
        assertThatThrownBy(() -> DorisReadQueryService.normalizeSql(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sql is required");
        assertThatThrownBy(() -> DorisReadQueryService.normalizeSql("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sql is required");
        assertThatThrownBy(() -> DorisReadQueryService.normalizeSql("-- only a comment"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sql is required");
    }

    @Test
    void rejectsNonReadOnlyLeadingKeyword() {
        assertThatThrownBy(() -> DorisReadQueryService.validateReadOnly(
                DorisReadQueryService.normalizeSql("ALTER TABLE t ADD COLUMN x INT")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only read-only SQL is allowed");
        assertThatThrownBy(() -> DorisReadQueryService.validateReadOnly(
                DorisReadQueryService.normalizeSql("  UPDATE t SET x = 1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UPDATE");
    }

    @Test
    void metadataLeadingStatementsSkipKeywordDenylist() {
        org.assertj.core.api.Assertions.assertThatCode(() -> DorisReadQueryService.validateReadOnly(
                DorisReadQueryService.normalizeSql("DESCRIBE t DROP COLUMN x"))).doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> DorisReadQueryService.validateReadOnly(
                DorisReadQueryService.normalizeSql("EXPLAIN SELECT * FROM t"))).doesNotThrowAnyException();
    }
}
