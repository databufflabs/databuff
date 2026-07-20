package com.databuff.apm.common.storage;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApmConfigRepositoryCoverageTest {

    @Test
    void schemaReadyFlagsDelegateToTableReady() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(true);

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        assertThat(repository.eventRuleSchemaReady()).isTrue();
        assertThat(repository.eventSchemaReady()).isTrue();
        assertThat(repository.alarmPolicySchemaReady()).isTrue();
        assertThat(repository.aiMessageSchemaReady()).isTrue();
        assertThat(repository.aiSessionSchemaReady()).isTrue();
        assertThat(repository.metricCoreSchemaReady()).isTrue();
        assertThat(repository.alarmSchemaReady()).isTrue();
        assertThat(repository.notifyChannelSchemaReady()).isTrue();
    }

    @Test
    void loadLlmModelsMapsNullableInts() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("provider_code")).thenReturn("openai");
        when(rs.getString("model_id")).thenReturn("gpt-4o-mini");
        when(rs.getString("display_name")).thenReturn("Mini");
        when(rs.getInt("context_window")).thenReturn(0);
        when(rs.wasNull()).thenReturn(true, false);
        when(rs.getInt("max_output_tokens")).thenReturn(4096);
        when(rs.getString("env_vars_json")).thenReturn("{}");
        when(rs.getInt("is_default")).thenReturn(1);
        when(rs.getInt("enabled")).thenReturn(1);

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        List<ApmConfigRepository.LlmModelRow> rows = repository.loadLlmModels();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).contextWindow()).isNull();
        assertThat(rows.get(0).maxOutputTokens()).isEqualTo(4096);
    }

    @Test
    void replaceLlmModelsDeletesAndInsertsBatch() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        PreparedStatement deletePs = mock(PreparedStatement.class);
        PreparedStatement insertPs = mock(PreparedStatement.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.prepareStatement(contains("DELETE"))).thenReturn(deletePs);
        when(connection.prepareStatement(contains("INSERT"))).thenReturn(insertPs);

        ApmConfigRepository.LlmModelRow model = new ApmConfigRepository.LlmModelRow(
                "openai", "gpt-4o-mini", "Mini", 128000, 4096, "{}", true, true);
        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        repository.replaceLlmModels("openai", List.of(model));
        verify(deletePs).executeUpdate();
        verify(insertPs).executeBatch();
    }

    @Test
    void replaceLlmModelsSkipsInsertWhenEmpty() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        PreparedStatement deletePs = mock(PreparedStatement.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.prepareStatement(contains("DELETE"))).thenReturn(deletePs);

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        repository.replaceLlmModels("openai", List.of());
        verify(deletePs).executeUpdate();
    }

    @Test
    void countAndLoadAiSessions() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet countRs = mock(ResultSet.class);
        ResultSet listRs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(contains("COUNT"))).thenReturn(countRs);
        when(statement.executeQuery(contains("ORDER BY"))).thenReturn(listRs);
        when(countRs.next()).thenReturn(true);
        when(countRs.getLong("total")).thenReturn(3L);
        when(listRs.next()).thenReturn(true, false);
        when(listRs.getString("session_id")).thenReturn("sess-1");
        when(listRs.getString("user_id")).thenReturn("u1");
        when(listRs.getString("user_name")).thenReturn("alice");
        when(listRs.getString("agent")).thenReturn("gpt-4o-mini");
        when(listRs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.parse("2026-06-01T00:00:00Z")));
        when(listRs.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.parse("2026-06-01T01:00:00Z")));
        when(listRs.getInt("message_count")).thenReturn(4);
        when(listRs.getString("first_user_message")).thenReturn("hello");

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        assertThat(repository.countAiSessions()).isEqualTo(3L);
        assertThat(repository.loadRecentAiSessions(10, 5)).hasSize(1);
    }

    @Test
    void loadRecentEventsAndEventById() throws Exception {
        Instant now = Instant.parse("2026-06-01T12:00:00Z");
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet listRs = mock(ResultSet.class);
        ResultSet byIdRs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(contains("WHERE id"))).thenReturn(ps);
        when(statement.executeQuery(anyString())).thenReturn(listRs);
        when(ps.executeQuery()).thenReturn(byIdRs);
        when(listRs.next()).thenReturn(true, false);
        stubEventRowByName(listRs, "E1", now);
        when(byIdRs.next()).thenReturn(true);
        stubEventRowByName(byIdRs, "E1", now);

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        assertThat(repository.loadRecentEvents(5)).hasSize(1);
        assertThat(repository.loadEventById("E1")).map(ApmConfigRepository.EventRow::id).contains("E1");
    }

    @Test
    void loadEventByIdEmpty() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        assertThat(repository.loadEventById("missing")).isEmpty();
    }

    @Test
    void listEventsByAlarmIdRejectsBlank() throws Exception {
        ApmConfigRepository repository = new ApmConfigRepository(Mockito.mock(ApmReadRepository.class), "databuff");
        assertThat(repository.listEventsByAlarmId(" ", "open")).isEmpty();
        assertThat(repository.listEventsByAlarmIds(null, "open")).isEmpty();
        assertThat(repository.listEventsByAlarmIds(List.of(" ", ""), "open")).isEmpty();
    }

    @Test
    void listEventsByAlarmIdsGroupsJoinedRows() throws Exception {
        Instant now = Instant.parse("2026-06-01T12:00:00Z");
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.prepareStatement(contains("alarm_id IN"))).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString(1)).thenReturn("ALM-1");
        stubEventRowByIndex(rs, 2, "E1", now);

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        Map<String, List<ApmConfigRepository.EventRow>> grouped =
                repository.listEventsByAlarmIds(List.of("ALM-1"), "trigger");
        assertThat(grouped).containsKey("ALM-1");
        assertThat(grouped.get("ALM-1").get(0).id()).isEqualTo("E1");
    }

    @Test
    void upsertAlarmEventAndCountEvents() throws Exception {
        Instant from = Instant.parse("2026-06-01T00:00:00Z");
        Instant to = Instant.parse("2026-06-01T23:59:59Z");
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        PreparedStatement linkPs = mock(PreparedStatement.class);
        PreparedStatement countPs = mock(PreparedStatement.class);
        ResultSet countRs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.prepareStatement(contains("alarm_event"))).thenReturn(linkPs);
        when(connection.prepareStatement(contains("COUNT"))).thenReturn(countPs);
        when(countPs.executeQuery()).thenReturn(countRs);
        when(countRs.next()).thenReturn(true);
        when(countRs.getLong(1)).thenReturn(7L);

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        repository.upsertAlarmEvent(new ApmConfigRepository.AlarmEventRow(
                "ALM-1", "E1", Instant.parse("2026-06-01T12:00:00Z")));
        assertThat(repository.countEventsByRuleAndService(1L, "checkout", from, to, "trigger")).isEqualTo(7L);
        verify(linkPs).executeUpdate();
    }

    @Test
    void alarmPolicyCrud() throws Exception {
        Instant updated = Instant.parse("2026-06-01T12:00:00Z");
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        PreparedStatement upsertPs = mock(PreparedStatement.class);
        PreparedStatement deletePs = mock(PreparedStatement.class);
        PreparedStatement loadPs = mock(PreparedStatement.class);
        ResultSet loadRs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.prepareStatement(contains("INSERT INTO"))).thenReturn(upsertPs);
        when(connection.prepareStatement(contains("DELETE FROM"))).thenReturn(deletePs);
        when(connection.prepareStatement(contains("WHERE policy_type"))).thenReturn(loadPs);
        when(loadPs.executeQuery()).thenReturn(loadRs);
        when(loadRs.next()).thenReturn(true, false);
        when(loadRs.getString("policy_type")).thenReturn("service");
        when(loadRs.getLong("policy_id")).thenReturn(9L);
        when(loadRs.getString("policy_name")).thenReturn("svc policy");
        when(loadRs.getInt("enabled")).thenReturn(1);
        when(loadRs.getString("body_json")).thenReturn("{}");
        when(loadRs.getTimestamp("updated_at")).thenReturn(Timestamp.from(updated));

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        ApmConfigRepository.AlarmPolicyRow row = new ApmConfigRepository.AlarmPolicyRow(
                "service", 9L, "svc policy", true, "{}", updated);
        repository.upsertAlarmPolicy(row);
        repository.deleteAlarmPolicy("service", 9L);
        assertThat(repository.loadAlarmPolicies("service").get(0).policyName()).isEqualTo("svc policy");
    }

    @Test
    void loadAndUpsertAlarms() throws Exception {
        Instant triggered = Instant.parse("2026-06-01T12:00:00Z");
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement upsertPs = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(contains("INSERT INTO"))).thenReturn(upsertPs);
        when(statement.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("id")).thenReturn("ALM-9");
        when(rs.getLong("policy_id")).thenReturn(1L);
        when(rs.getString("service")).thenReturn("checkout");
        when(rs.getString("detection_way")).thenReturn("threshold");
        when(rs.getString("level")).thenReturn("critical");
        when(rs.getString("message")).thenReturn("breached");
        when(rs.getString("status")).thenReturn("open");
        when(rs.getTimestamp("triggered_at")).thenReturn(Timestamp.from(triggered));
        when(rs.getTimestamp("resolved_at")).thenReturn(null);

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        assertThat(repository.loadRecentAlarms(5).get(0).id()).isEqualTo("ALM-9");
        repository.upsertAlarm(new ApmConfigRepository.AlarmRow(
                "ALM-9", 1L, "checkout", "threshold", "critical", "breached", "open", triggered, null));
        verify(upsertPs).executeUpdate();
    }

    @Test
    void metricCoreCountLoadUpsert() throws Exception {
        Instant updated = Instant.parse("2026-06-01T12:00:00Z");
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement upsertPs = mock(PreparedStatement.class);
        ResultSet countRs = mock(ResultSet.class);
        ResultSet listRs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(contains("INSERT INTO"))).thenReturn(upsertPs);
        when(statement.executeQuery(contains("COUNT"))).thenReturn(countRs);
        when(statement.executeQuery(contains("ORDER BY id"))).thenReturn(listRs);
        when(countRs.next()).thenReturn(true);
        when(countRs.getLong("cnt")).thenReturn(2L);
        when(listRs.next()).thenReturn(true, false);
        when(listRs.getLong("id")).thenReturn(1L);
        when(listRs.getString("type1")).thenReturn("apm");
        when(listRs.getString("type2")).thenReturn("service");
        when(listRs.getString("type3")).thenReturn("req");
        when(listRs.getString("app")).thenReturn("demo");
        when(listRs.getString("database_name")).thenReturn("databuff");
        when(listRs.getString("measurement")).thenReturn("svc.req");
        when(listRs.getString("doris_table")).thenReturn("metric_svc");
        when(listRs.getString("description")).thenReturn("desc");
        when(listRs.getString("tag_key_json")).thenReturn("{}");
        when(listRs.getString("tag_value_json")).thenReturn("{}");
        when(listRs.getString("fields_json")).thenReturn("{}");
        when(listRs.getInt("enabled")).thenReturn(1);
        when(listRs.getInt("builtin")).thenReturn(1);
        when(listRs.getTimestamp("updated_at")).thenReturn(Timestamp.from(updated));

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        assertThat(repository.countMetricCoreRows()).isEqualTo(2L);
        List<ApmConfigRepository.MetricCoreConfigRow> rows = repository.loadMetricCoreRows();
        assertThat(rows).hasSize(1);
        repository.upsertMetricCoreRow(rows.get(0));
        verify(upsertPs).executeUpdate();
    }

    @Test
    void upsertEventPersistsRow() throws Exception {
        Instant triggered = Instant.parse("2026-06-01T12:00:00Z");
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.prepareStatement(contains("INSERT INTO"))).thenReturn(ps);

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        repository.upsertEvent(new ApmConfigRepository.EventRow(
                "E9", 1L, "cpu", "checkout", "threshold", "warn", "trigger", "msg", "g1", false, triggered));
        verify(ps).executeUpdate();
    }

    @Test
    void recordConstructorsExposeFields() {
        Instant now = Instant.now();
        assertThat(new ApmConfigRepository.LlmProviderRow(
                "openai", "OpenAI", "https://api.openai.com", true, "cipher", "gpt-4o-mini", "openai")
                .providerCode()).isEqualTo("openai");
        assertThat(new ApmConfigRepository.NotifyChannelRow(1L, "webhook", "https://x", true).webhookUrl())
                .isEqualTo("https://x");
        assertThat(new ApmConfigRepository.MetricCoreConfigRow(
                1L, "t1", "t2", "t3", "app", "db", "m", "table", "d", "{}", "{}", "{}", true, true, now)
                .measurement()).isEqualTo("m");
    }

    private static void stubEventRowByName(ResultSet rs, String id, Instant triggered) throws SQLException {
        when(rs.getString("id")).thenReturn(id);
        when(rs.getLong("rule_id")).thenReturn(1L);
        when(rs.getString("rule_name")).thenReturn("cpu");
        when(rs.getString("service")).thenReturn("checkout");
        when(rs.getString("detection_way")).thenReturn("threshold");
        when(rs.getString("level")).thenReturn("warn");
        when(rs.getString("status")).thenReturn("trigger");
        when(rs.getString("message")).thenReturn("msg");
        when(rs.getString("group_key")).thenReturn("g1");
        when(rs.getInt("silenced")).thenReturn(0);
        when(rs.getTimestamp("triggered_at")).thenReturn(Timestamp.from(triggered));
        when(rs.getString(1)).thenReturn(id);
        when(rs.getLong(2)).thenReturn(1L);
        when(rs.getString(3)).thenReturn("cpu");
        when(rs.getString(4)).thenReturn("checkout");
        when(rs.getString(5)).thenReturn("threshold");
        when(rs.getString(6)).thenReturn("warn");
        when(rs.getString(7)).thenReturn("trigger");
        when(rs.getString(8)).thenReturn("msg");
        when(rs.getString(9)).thenReturn("g1");
        when(rs.getInt(10)).thenReturn(0);
        when(rs.getTimestamp(11)).thenReturn(Timestamp.from(triggered));
    }

    private static void stubEventRowByIndex(ResultSet rs, int from, String id, Instant triggered) throws SQLException {
        when(rs.getString(from)).thenReturn(id);
        when(rs.getLong(from + 1)).thenReturn(1L);
        when(rs.getString(from + 2)).thenReturn("cpu");
        when(rs.getString(from + 3)).thenReturn("checkout");
        when(rs.getString(from + 4)).thenReturn("threshold");
        when(rs.getString(from + 5)).thenReturn("warn");
        when(rs.getString(from + 6)).thenReturn("trigger");
        when(rs.getString(from + 7)).thenReturn("msg");
        when(rs.getString(from + 8)).thenReturn("g1");
        when(rs.getInt(from + 9)).thenReturn(0);
        when(rs.getTimestamp(from + 10)).thenReturn(Timestamp.from(triggered));
    }
}
