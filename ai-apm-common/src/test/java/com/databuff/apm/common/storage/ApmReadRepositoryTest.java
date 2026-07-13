package com.databuff.apm.common.storage;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApmReadRepositoryTest {

    @Test
    void rejectsNullDataSource() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ApmReadRepository(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void accessGateRunsBeforeBorrowingConnection() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        DorisAccessGate gate = () -> {
            throw new java.sql.SQLException("blocked");
        };
        ApmReadRepository reader = new ApmReadRepository(dataSource, gate);

        Assertions.assertThatThrownBy(reader::connection)
                .isInstanceOf(java.sql.SQLException.class)
                .hasMessage("blocked");
    }

    @Test
    void parsesTrafficLightRows() throws Exception {
        ApmReadRepository reader = new ApmReadRepository(mockDataSource()) {
            @Override
            public Connection connection() throws java.sql.SQLException {
                try {
                    return mockConnection();
                } catch (Exception e) {
                    throw new java.sql.SQLException(e);
                }
            }
        };

        assertThat(reader.queryTrafficLight("select 1")).hasSize(1);
        reader.close();
    }

    @Test
    void returnsEmptyListWhenNoRows() throws Exception {
        ApmReadRepository reader = new ApmReadRepository(mockDataSource()) {
            @Override
            public Connection connection() throws java.sql.SQLException {
                try {
                    Connection connection = mock(Connection.class);
                    Statement statement = mock(Statement.class);
                    ResultSet resultSet = mock(ResultSet.class);
                    when(connection.createStatement()).thenReturn(statement);
                    when(statement.executeQuery(anyString())).thenReturn(resultSet);
                    when(resultSet.next()).thenReturn(false);
                    return connection;
                } catch (Exception e) {
                    throw new java.sql.SQLException(e);
                }
            }
        };
        assertThat(reader.queryTrafficLight("select 1")).isEmpty();
    }

    @Test
    void parsesSpanSummaries() throws Exception {
        ApmReadRepository reader = new ApmReadRepository(mockDataSource()) {
            @Override
            public Connection connection() throws java.sql.SQLException {
                try {
                    Connection connection = mock(Connection.class);
                    Statement statement = mock(Statement.class);
                    ResultSet resultSet = mock(ResultSet.class);
                    when(connection.createStatement()).thenReturn(statement);
                    when(statement.executeQuery(anyString())).thenReturn(resultSet);
                    when(resultSet.next()).thenReturn(true, false);
                    when(resultSet.getString("trace_id")).thenReturn("t1");
                    when(resultSet.getString("span_id")).thenReturn("s1");
                    when(resultSet.getString("service")).thenReturn("checkout");
                    when(resultSet.getString("service_id")).thenReturn("checkout-id");
                    when(resultSet.getString("parent_id")).thenReturn("0");
                    when(resultSet.getInt("is_parent")).thenReturn(1);
                    when(resultSet.getString("name")).thenReturn("GET /");
                    when(resultSet.getString("startTime")).thenReturn("2026-06-01 12:00:00");
                    when(resultSet.getLong("duration")).thenReturn(99L);
                    when(resultSet.getInt("error")).thenReturn(0);
                    when(resultSet.getString("serviceInstance")).thenReturn("inst-1");
                    when(resultSet.getString("resource")).thenReturn("GET /orders");
                    when(resultSet.getString("hostName")).thenReturn("host-1");
                    when(resultSet.getInt("meta_http_status_code")).thenReturn(500);
                    when(resultSet.wasNull()).thenReturn(false);
                    when(resultSet.getString("meta_error_type")).thenReturn("ServerError");
                    return connection;
                } catch (Exception e) {
                    throw new java.sql.SQLException(e);
                }
            }
        };
        Assertions.assertThat(reader.querySpanSummaries("select 1").get(0).service()).isEqualTo("checkout");
        Assertions.assertThat(reader.querySpanSummaries("select 1").get(0).metaHttpStatusCode()).isEqualTo(500);
    }

    @Test
    void parsesSpanDetails() throws Exception {
        ApmReadRepository reader = new ApmReadRepository(mockDataSource()) {
            @Override
            public Connection connection() throws java.sql.SQLException {
                try {
                    Connection connection = mock(Connection.class);
                    Statement statement = mock(Statement.class);
                    ResultSet resultSet = mock(ResultSet.class);
                    when(connection.createStatement()).thenReturn(statement);
                    when(statement.executeQuery(anyString())).thenReturn(resultSet);
                    when(resultSet.next()).thenReturn(true, false);
                    when(resultSet.getString("trace_id")).thenReturn("t1");
                    when(resultSet.getString("span_id")).thenReturn("s1");
                    when(resultSet.getString("parent_id")).thenReturn("0");
                    when(resultSet.getString("service")).thenReturn("checkout");
                    when(resultSet.getString("name")).thenReturn("GET /");
                    when(resultSet.getString("startTime")).thenReturn("2026-06-01 12:00:00");
                    when(resultSet.getLong("start")).thenReturn(1_748_784_000_000_000_000L);
                    when(resultSet.getLong("duration")).thenReturn(99L);
                    when(resultSet.getInt("error")).thenReturn(0);
                    when(resultSet.getString("hostName")).thenReturn("host-a");
                    return connection;
                } catch (Exception e) {
                    throw new java.sql.SQLException(e);
                }
            }
        };
        Assertions.assertThat(reader.querySpanDetails("select 1").get(0).hostName()).isEqualTo("host-a");
    }

    @Test
    void parsesServiceMetrics() throws Exception {
        ApmReadRepository reader = new ApmReadRepository(mockDataSource()) {
            @Override
            public Connection connection() throws java.sql.SQLException {
                try {
                    Connection connection = mock(Connection.class);
                    Statement statement = mock(Statement.class);
                    ResultSet resultSet = mock(ResultSet.class);
                    when(connection.createStatement()).thenReturn(statement);
                    when(statement.executeQuery(anyString())).thenReturn(resultSet);
                    when(resultSet.next()).thenReturn(true, false);
                    when(resultSet.getString("ts")).thenReturn("2026-06-01 12:00:00");
                    when(resultSet.getString("service")).thenReturn("demo");
                    when(resultSet.getLong("request_cnt")).thenReturn(10L);
                    when(resultSet.getLong("error_cnt")).thenReturn(1L);
                    when(resultSet.getDouble("avg_duration")).thenReturn(5.5);
                    return connection;
                } catch (Exception e) {
                    throw new java.sql.SQLException(e);
                }
            }
        };
        Assertions.assertThat(reader.queryServiceMetrics("select 1").get(0).requestCount()).isEqualTo(10L);
    }

    @Test
    void parsesErrorRateSnapshot() throws Exception {
        ApmReadRepository reader = new ApmReadRepository(mockDataSource()) {
            @Override
            public Connection connection() throws java.sql.SQLException {
                try {
                    Connection connection = mock(Connection.class);
                    Statement statement = mock(Statement.class);
                    ResultSet resultSet = mock(ResultSet.class);
                    when(connection.createStatement()).thenReturn(statement);
                    when(statement.executeQuery(anyString())).thenReturn(resultSet);
                    when(resultSet.next()).thenReturn(true);
                    when(resultSet.getLong("error_cnt")).thenReturn(2L);
                    when(resultSet.getLong("total_cnt")).thenReturn(10L);
                    return connection;
                } catch (Exception e) {
                    throw new java.sql.SQLException(e);
                }
            }
        };
        Assertions.assertThat(reader.queryErrorRate("select 1").errorRate()).isEqualTo(0.2);
    }

    @Test
    void returnsZeroErrorRateWhenNoRows() throws Exception {
        ApmReadRepository reader = new ApmReadRepository(mockDataSource()) {
            @Override
            public Connection connection() throws java.sql.SQLException {
                try {
                    Connection connection = mock(Connection.class);
                    Statement statement = mock(Statement.class);
                    ResultSet resultSet = mock(ResultSet.class);
                    when(connection.createStatement()).thenReturn(statement);
                    when(statement.executeQuery(anyString())).thenReturn(resultSet);
                    when(resultSet.next()).thenReturn(false);
                    return connection;
                } catch (Exception e) {
                    throw new java.sql.SQLException(e);
                }
            }
        };
        Assertions.assertThat(reader.queryErrorRate("select 1").errorRate()).isZero();
    }

    @Test
    void parsesRequestCount() throws Exception {
        ApmReadRepository reader = new ApmReadRepository(mockDataSource()) {
            @Override
            public Connection connection() throws java.sql.SQLException {
                try {
                    Connection connection = mock(Connection.class);
                    Statement statement = mock(Statement.class);
                    ResultSet resultSet = mock(ResultSet.class);
                    when(connection.createStatement()).thenReturn(statement);
                    when(statement.executeQuery(anyString())).thenReturn(resultSet);
                    when(resultSet.next()).thenReturn(true);
                    when(resultSet.getLong("total_cnt")).thenReturn(15L);
                    return connection;
                } catch (Exception e) {
                    throw new java.sql.SQLException(e);
                }
            }
        };
        assertThat(reader.queryRequestCount("select 1")).isEqualTo(15L);
    }

    @Test
    void returnsZeroRequestCountWhenNoRows() throws Exception {
        ApmReadRepository reader = new ApmReadRepository(mockDataSource()) {
            @Override
            public Connection connection() throws java.sql.SQLException {
                try {
                    Connection connection = mock(Connection.class);
                    Statement statement = mock(Statement.class);
                    ResultSet resultSet = mock(ResultSet.class);
                    when(connection.createStatement()).thenReturn(statement);
                    when(statement.executeQuery(anyString())).thenReturn(resultSet);
                    when(resultSet.next()).thenReturn(false);
                    return connection;
                } catch (Exception e) {
                    throw new java.sql.SQLException(e);
                }
            }
        };
        assertThat(reader.queryRequestCount("select 1")).isZero();
    }

    @Test
    void parsesTopologyEdges() throws Exception {
        ApmReadRepository reader = new ApmReadRepository(mockDataSource()) {
            @Override
            public Connection connection() throws java.sql.SQLException {
                try {
                    Connection connection = mock(Connection.class);
                    Statement statement = mock(Statement.class);
                    ResultSet resultSet = mock(ResultSet.class);
                    when(connection.createStatement()).thenReturn(statement);
                    when(statement.executeQuery(anyString())).thenReturn(resultSet);
                    when(resultSet.next()).thenReturn(true, false);
                    when(resultSet.getString("srcService")).thenReturn("gateway");
                    when(resultSet.getString("dstService")).thenReturn("checkout");
                    when(resultSet.getLong("call_cnt")).thenReturn(8L);
                    when(resultSet.getLong("error_cnt")).thenReturn(1L);
                    return connection;
                } catch (Exception e) {
                    throw new java.sql.SQLException(e);
                }
            }
        };
        Assertions.assertThat(reader.queryTopologyEdges("select 1").get(0).dstService()).isEqualTo("checkout");
    }

    @Test
    void parsesServiceFlowEdges() throws Exception {
        ApmReadRepository reader = new ApmReadRepository(mockDataSource()) {
            @Override
            public Connection connection() throws java.sql.SQLException {
                try {
                    Connection connection = mock(Connection.class);
                    Statement statement = mock(Statement.class);
                    ResultSet resultSet = mock(ResultSet.class);
                    when(connection.createStatement()).thenReturn(statement);
                    when(statement.executeQuery(anyString())).thenReturn(resultSet);
                    when(resultSet.next()).thenReturn(true, false);
                    when(resultSet.getString("src_service")).thenReturn("gateway");
                    when(resultSet.getString("src_service_id")).thenReturn("gw-id");
                    when(resultSet.getString("dst_service")).thenReturn("checkout");
                    when(resultSet.getString("dst_service_id")).thenReturn("co-id");
                    when(resultSet.getLong("call_cnt")).thenReturn(8L);
                    when(resultSet.getLong("error_cnt")).thenReturn(1L);
                    when(resultSet.getDouble("avg_duration")).thenReturn(12.5);
                    return connection;
                } catch (Exception e) {
                    throw new java.sql.SQLException(e);
                }
            }
        };
        Assertions.assertThat(reader.queryServiceFlow("select 1").get(0).avgDuration()).isEqualTo(12.5);
    }

    @Test
    void parsesHttpEndpoints() throws Exception {
        ApmReadRepository reader = new ApmReadRepository(mockDataSource()) {
            @Override
            public Connection connection() throws java.sql.SQLException {
                try {
                    Connection connection = mock(Connection.class);
                    Statement statement = mock(Statement.class);
                    ResultSet resultSet = mock(ResultSet.class);
                    when(connection.createStatement()).thenReturn(statement);
                    when(statement.executeQuery(anyString())).thenReturn(resultSet);
                    when(resultSet.next()).thenReturn(true, false);
                    when(resultSet.getString("service_id")).thenReturn("svc-hash-1");
                    when(resultSet.getString("service")).thenReturn("checkout");
                    when(resultSet.getString("url")).thenReturn("/api/cart");
                    when(resultSet.getString("httpMethod")).thenReturn("GET");
                    when(resultSet.getString("httpCode")).thenReturn("200");
                    when(resultSet.getLong("request_cnt")).thenReturn(10L);
                    when(resultSet.getLong("error_cnt")).thenReturn(1L);
                    when(resultSet.getDouble("avg_duration")).thenReturn(5.5);
                    return connection;
                } catch (Exception e) {
                    throw new java.sql.SQLException(e);
                }
            }
        };
        Assertions.assertThat(reader.queryHttpEndpoints("select 1").get(0).serviceId()).isEqualTo("svc-hash-1");
        Assertions.assertThat(reader.queryHttpEndpoints("select 1").get(0).url()).isEqualTo("/api/cart");
    }

    @Test
    void parsesHttpLatencyBuckets() throws Exception {
        ApmReadRepository reader = new ApmReadRepository(mockDataSource()) {
            @Override
            public Connection connection() throws java.sql.SQLException {
                try {
                    Connection connection = mock(Connection.class);
                    Statement statement = mock(Statement.class);
                    ResultSet resultSet = mock(ResultSet.class);
                    when(connection.createStatement()).thenReturn(statement);
                    when(statement.executeQuery(anyString())).thenReturn(resultSet);
                    when(resultSet.next()).thenReturn(true, false);
                    when(resultSet.getString("durationRange")).thenReturn("100-200ms");
                    when(resultSet.getLong("request_cnt")).thenReturn(8L);
                    when(resultSet.getLong("error_cnt")).thenReturn(0L);
                    return connection;
                } catch (Exception e) {
                    throw new java.sql.SQLException(e);
                }
            }
        };
        Assertions.assertThat(reader.queryHttpLatencyBuckets("select 1").get(0).durationRange()).isEqualTo("100-200ms");
    }

    @Test
    void borrowsConnectionFromDataSource() throws Exception {
        Connection open = mockConnection();
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(open);
        ApmReadRepository reader = new ApmReadRepository(dataSource);
        assertThat(reader.connection()).isSameAs(open);
        reader.close();
    }

    private static DataSource mockDataSource() throws Exception {
        Connection connection = mockConnection();
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        return dataSource;
    }

    private static Connection mockConnection() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("ts")).thenReturn("2026-06-01 12:00:00");
        when(resultSet.getString("service")).thenReturn("checkout");
        when(resultSet.getLong("error_cnt")).thenReturn(2L);
        when(resultSet.getLong("total_cnt")).thenReturn(20L);
        return connection;
    }
}
