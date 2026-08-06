package com.databuff.apm.web.storage;

import com.databuff.apm.common.cluster.coordination.ClusterInstanceCoordinator;
import com.databuff.apm.common.storage.DorisConnectionConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DorisPlatformStatsSamplerFlowTest {

    private static final String PROM_TEXT = """
            # TYPE doris_be_cpu counter
            doris_be_cpu{device="cpu",mode="user"} 100
            # TYPE doris_be_process_fd_num_used gauge
            doris_be_process_fd_num_used 42
            """;

    private DataSource dataSource;
    private Connection connection;
    private Statement statement;
    private DorisAvailability dorisAvailability;
    private ClusterInstanceCoordinator coordinator;
    private HttpClient httpClient;
    private HttpResponse<String> httpResponse;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        dorisAvailability = mock(DorisAvailability.class);
        when(dorisAvailability.isUnavailable()).thenReturn(false);
        coordinator = mock(ClusterInstanceCoordinator.class);
        when(coordinator.isLeader()).thenReturn(true);
        when(coordinator.localNodeId()).thenReturn("web-1");
        httpClient = mock(HttpClient.class);
        httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);
    }

    private DorisPlatformStatsSampler sampler(boolean enabled) {
        DorisPlatformStatsSampler sampler = new DorisPlatformStatsSampler(
                dataSource,
                dorisAvailability,
                new DorisConnectionConfig("fe-1", 9030, 8030),
                coordinator,
                enabled);
        ReflectionTestUtils.setField(sampler, "httpClient", httpClient);
        return sampler;
    }

    private ResultSet backendsResultSet() throws SQLException {
        return backendsResultSet(
                new String[] {"true", "10.0.0.1", "8040", "10", "45.5", "3"},
                false, true, false);
    }

    private ResultSet backendsResultSet(String[] rowValues, Boolean... nextSequence) throws SQLException {
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(6);
        when(meta.getColumnLabel(1)).thenReturn("Alive");
        when(meta.getColumnLabel(2)).thenReturn("Host");
        when(meta.getColumnLabel(3)).thenReturn("HttpPort");
        when(meta.getColumnLabel(4)).thenReturn("TabletNum");
        when(meta.getColumnLabel(5)).thenReturn("UsedPct");
        when(meta.getColumnLabel(6)).thenReturn("RunningTasks");
        ResultSet rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(meta);
        when(rs.next()).thenReturn(true, nextSequence);
        if (rowValues != null) {
            for (int i = 0; i < rowValues.length; i++) {
                when(rs.getString(i + 1)).thenReturn(rowValues[i]);
            }
        }
        return rs;
    }

    private ResultSet frontendsResultSet() throws SQLException {
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(4);
        when(meta.getColumnLabel(1)).thenReturn("Alive");
        when(meta.getColumnLabel(2)).thenReturn("Host");
        when(meta.getColumnLabel(3)).thenReturn("HttpPort");
        when(meta.getColumnLabel(4)).thenReturn("IsMaster");
        ResultSet rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(meta);
        when(rs.next()).thenReturn(true, false, true, false);
        when(rs.getString(1)).thenReturn("true");
        when(rs.getString(2)).thenReturn("10.0.0.2");
        when(rs.getString(3)).thenReturn("8030");
        when(rs.getString(4)).thenReturn("false");
        return rs;
    }

    @Test
    void sampleSkipsWhenDisabled() throws Exception {
        DorisPlatformStatsSampler sampler = sampler(false);

        sampler.sample();

        verify(dataSource, never()).getConnection();
    }

    @Test
    void sampleSkipsWhenNotLeader() throws Exception {
        when(coordinator.isLeader()).thenReturn(false);
        DorisPlatformStatsSampler sampler = sampler(true);

        sampler.sample();

        verify(dataSource, never()).getConnection();
    }

    @Test
    void sampleSetsZeroUpAndReturnsWhenUnavailable() throws Exception {
        when(dorisAvailability.isUnavailable()).thenReturn(true);
        DorisPlatformStatsSampler sampler = sampler(true);

        sampler.sample();

        verify(dataSource, never()).getConnection();
    }

    @Test
    void sampleReturnsWhenConnectionFails() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("no connection"));
        DorisPlatformStatsSampler sampler = sampler(true);

        sampler.sample();

        verify(dataSource).getConnection();
    }

    @Test
    void sampleCollectsBackendAndFrontendTargetsAndScrapes() throws Exception {
        ResultSet beRs = backendsResultSet();
        ResultSet feRs = frontendsResultSet();
        when(statement.executeQuery("SHOW BACKENDS")).thenReturn(beRs);
        when(statement.executeQuery("SHOW FRONTENDS")).thenReturn(feRs);
        when(httpResponse.body()).thenReturn(PROM_TEXT);
        DorisPlatformStatsSampler sampler = sampler(true);

        sampler.sample();

        verify(statement).executeQuery("SHOW BACKENDS");
        verify(statement).executeQuery("SHOW FRONTENDS");
        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void sampleComputesCpuModeDeltaAcrossSamples() throws Exception {
        ResultSet beRs = backendsResultSet();
        ResultSet feRs = frontendsResultSet();
        when(statement.executeQuery("SHOW BACKENDS")).thenReturn(beRs);
        when(statement.executeQuery("SHOW FRONTENDS")).thenReturn(feRs);
        String second = """
                # TYPE doris_be_cpu counter
                doris_be_cpu{device="cpu",mode="user"} 150
                # TYPE doris_be_process_fd_num_used gauge
                doris_be_process_fd_num_used 42
                """;
        when(httpResponse.body()).thenReturn(PROM_TEXT, second, PROM_TEXT, second);
        DorisPlatformStatsSampler sampler = sampler(true);

        sampler.sample();
        sampler.sample();

        verify(httpClient, times(4)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void sampleIgnoresNon2xxScrape() throws Exception {
        ResultSet beRs = backendsResultSet();
        ResultSet feRs = frontendsResultSet();
        when(statement.executeQuery("SHOW BACKENDS")).thenReturn(beRs);
        when(statement.executeQuery("SHOW FRONTENDS")).thenReturn(feRs);
        when(httpResponse.statusCode()).thenReturn(500);
        when(httpResponse.body()).thenReturn("");
        DorisPlatformStatsSampler sampler = sampler(true);

        sampler.sample();

        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void sampleCatchesScrapeIoException() throws Exception {
        ResultSet beRs = backendsResultSet();
        ResultSet feRs = frontendsResultSet();
        when(statement.executeQuery("SHOW BACKENDS")).thenReturn(beRs);
        when(statement.executeQuery("SHOW FRONTENDS")).thenReturn(feRs);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("refused"));
        DorisPlatformStatsSampler sampler = sampler(true);

        sampler.sample();

        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void sampleBackendsSkipsWhenRequiredColumnsMissing() throws Exception {
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(2);
        when(meta.getColumnLabel(1)).thenReturn("Foo");
        when(meta.getColumnLabel(2)).thenReturn("Bar");
        ResultSet beRs = mock(ResultSet.class);
        when(beRs.getMetaData()).thenReturn(meta);
        when(beRs.next()).thenReturn(false);
        ResultSet feRs = frontendsResultSet();
        when(statement.executeQuery("SHOW BACKENDS")).thenReturn(beRs);
        when(statement.executeQuery("SHOW FRONTENDS")).thenReturn(feRs);
        when(httpResponse.body()).thenReturn(PROM_TEXT);
        DorisPlatformStatsSampler sampler = sampler(true);

        sampler.sample();

        verify(statement).executeQuery("SHOW BACKENDS");
        verify(httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void sampleBackendsSkipsEmptyHost() throws Exception {
        ResultSet beRs = backendsResultSet(new String[] {"true", "", "8040", "10", "45.5", "3"},
                false, true, false);
        ResultSet feRs = frontendsResultSet();
        when(statement.executeQuery("SHOW BACKENDS")).thenReturn(beRs);
        when(statement.executeQuery("SHOW FRONTENDS")).thenReturn(feRs);
        when(httpResponse.body()).thenReturn(PROM_TEXT);
        DorisPlatformStatsSampler sampler = sampler(true);

        sampler.sample();

        verify(httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void sampleBackendsExcludesDeadNodesFromScrape() throws Exception {
        ResultSet beRs = backendsResultSet(
                new String[] {"false", "10.0.0.9", "8040", "10", "45.5", "3"},
                false, true, false);
        ResultSet feRs = frontendsResultSet();
        when(statement.executeQuery("SHOW BACKENDS")).thenReturn(beRs);
        when(statement.executeQuery("SHOW FRONTENDS")).thenReturn(feRs);
        when(httpResponse.body()).thenReturn(PROM_TEXT);
        DorisPlatformStatsSampler sampler = sampler(true);

        sampler.sample();

        verify(httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }
}
