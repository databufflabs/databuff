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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApmConfigRepositoryTest {

    @Test
    void schemaReadyWhenTableQueryable() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(true);

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        assertThat(repository.schemaReady()).isTrue();
    }

    @Test
    void tableReadyWhenTableExistsButEmpty() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        assertThat(repository.tableReady(DorisTableNames.CONFIG_AI_TOOL)).isTrue();
    }

    @Test
    void schemaNotReadyOnFailure() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        when(reader.connection()).thenThrow(new SQLException("down"));
        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        assertThat(repository.schemaReady()).isFalse();
    }

    @Test
    void maxEventRuleIdEmpty() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        assertThat(repository.maxEventRuleId()).isEmpty();
    }

    @Test
    void loadLlmProvidersMapsRows() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("provider_code")).thenReturn("openai");
        when(rs.getString("display_name")).thenReturn("OpenAI");
        when(rs.getString("base_url")).thenReturn("https://api.openai.com/v1");
        when(rs.getInt("enabled")).thenReturn(1);
        when(rs.getString("api_key_cipher")).thenReturn("c2s=");
        when(rs.getString("default_model")).thenReturn("gpt-4o-mini");
        when(rs.getString("api_type")).thenReturn("openai-completions");

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        List<ApmConfigRepository.LlmProviderRow> rows = repository.loadLlmProviders();
        Assertions.assertThat(rows).hasSize(1);
        assertThat(rows.get(0).providerCode()).isEqualTo("openai");
        verify(statement).executeQuery(org.mockito.ArgumentMatchers.contains("config_llm_provider"));
    }

    @Test
    void upsertAndDeleteEventRule() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.prepareStatement(org.mockito.ArgumentMatchers.contains("config_event_rule"))).thenReturn(ps);

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        repository.upsertEventRule(new ApmConfigRepository.EventRuleRow(
                1L, "rule", "singleMetric", "threshold", "svc", "error_rate", 0.1, "gt", true, null));
        repository.deleteEventRule(1L);
        verify(ps, org.mockito.Mockito.atLeastOnce()).executeUpdate();
    }

    @Test
    void loadEventRulesMapsRows() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getLong("id")).thenReturn(9L);
        when(rs.getString("rule_name")).thenReturn("demo");
        when(rs.getString("classify")).thenReturn("singleMetric");
        when(rs.getString("detection_way")).thenReturn("threshold");
        when(rs.getString("service")).thenReturn("demo-order");
        when(rs.getString("metric")).thenReturn("error_rate");
        when(rs.getDouble("threshold")).thenReturn(0.05);
        when(rs.getString("comparator")).thenReturn("gt");
        when(rs.getInt("enabled")).thenReturn(1);
        when(rs.getString("query_json")).thenReturn(null);

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        List<ApmConfigRepository.EventRuleRow> rows = repository.loadEventRules();
        Assertions.assertThat(rows).hasSize(1);
        assertThat(rows.get(0).id()).isEqualTo(9L);
    }

    @Test
    void upsertLlmProvider() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.prepareStatement(org.mockito.ArgumentMatchers.contains("config_llm_provider"))).thenReturn(ps);

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        repository.upsertLlmProvider(new ApmConfigRepository.LlmProviderRow(
                "openai", "OpenAI", "https://api.openai.com/v1", true, null, "gpt-4o-mini"));
        verify(ps).executeUpdate();
    }

    @Test
    void tableReadyChecksNamedTable() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(contains("config_ai_message"))).thenReturn(rs);
        when(rs.next()).thenReturn(true);

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        assertThat(repository.aiMessageSchemaReady()).isTrue();
    }

    @Test
    void upsertAiMessage() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.prepareStatement(org.mockito.ArgumentMatchers.contains("config_ai_message"))).thenReturn(ps);

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        Instant now = Instant.now();
        repository.upsertAiMessage(new ApmConfigRepository.AiMessageRow(
                "s1",
                "m1",
                "USER",
                "admin",
                "admin",
                "brain",
                "AGENT",
                1,
                1,
                "USER",
                "COMPLETED",
                null,
                null,
                null,
                "hi",
                null,
                null,
                "{}",
                null,
                now,
                now));
        verify(ps).executeUpdate();
    }

    @Test
    void upsertAlarmSilence() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.prepareStatement(org.mockito.ArgumentMatchers.contains("config_alarm_silence"))).thenReturn(ps);

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        Instant now = Instant.now();
        repository.upsertAlarmSilence(new ApmConfigRepository.AlarmSilenceRow("demo", now, now));
        verify(ps).executeUpdate();
    }

    @Test
    void loadAiSessionsAndMessages() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement sessionStatement = mock(Statement.class);
        PreparedStatement messagePs = mock(PreparedStatement.class);
        ResultSet sessionsRs = mock(ResultSet.class);
        ResultSet messagesRs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(sessionStatement);
        when(connection.prepareStatement(contains("config_ai_message"))).thenReturn(messagePs);
        when(sessionStatement.executeQuery(contains("config_ai_message"))).thenReturn(sessionsRs);
        when(messagePs.executeQuery()).thenReturn(messagesRs);
        Instant now = Instant.now();
        when(sessionsRs.next()).thenReturn(true, false);
        when(sessionsRs.getString("session_id")).thenReturn("s1");
        when(sessionsRs.getString("user_id")).thenReturn("admin");
        when(sessionsRs.getString("user_name")).thenReturn("admin");
        when(sessionsRs.getString("agent")).thenReturn("brain");
        when(sessionsRs.getInt("message_count")).thenReturn(1);
        when(sessionsRs.getString("first_user_message")).thenReturn("hello");
        when(sessionsRs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
        when(sessionsRs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
        when(messagesRs.next()).thenReturn(true, false);
        when(messagesRs.getString("session_id")).thenReturn("s1");
        when(messagesRs.getString("message_id")).thenReturn("m1");
        when(messagesRs.getString("session_type")).thenReturn("USER");
        when(messagesRs.getString("user_id")).thenReturn("admin");
        when(messagesRs.getString("user_name")).thenReturn("admin");
        when(messagesRs.getString("agent")).thenReturn("brain");
        when(messagesRs.getString("agent_type")).thenReturn("AGENT");
        when(messagesRs.getInt("round_index")).thenReturn(1);
        when(messagesRs.getInt("message_index")).thenReturn(1);
        when(messagesRs.getString("message_type")).thenReturn("USER");
        when(messagesRs.getString("message_status")).thenReturn("COMPLETED");
        when(messagesRs.getString("model_name")).thenReturn(null);
        when(messagesRs.getString("call_id")).thenReturn(null);
        when(messagesRs.getString("tool_name")).thenReturn(null);
        when(messagesRs.getString("content")).thenReturn("hello");
        when(messagesRs.getString("attachments_json")).thenReturn(null);
        when(messagesRs.getString("error")).thenReturn(null);
        when(messagesRs.getString("metadata_json")).thenReturn("{}");
        when(messagesRs.getString("trigger_source")).thenReturn(null);
        when(messagesRs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
        when(messagesRs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        assertThat(repository.loadRecentAiSessions(10)).hasSize(1);
        assertThat(repository.loadAiMessages("s1")).hasSize(1);
    }

    @Test
    void aiPlatformSchemaReadyChecksAllTables() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(true);

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        assertThat(repository.aiPlatformSchemaReady()).isTrue();
        verify(statement).executeQuery(contains("config_ai_tool"));
        verify(statement).executeQuery(contains("config_ai_skill"));
        verify(statement).executeQuery(contains("config_ai_expert"));
    }

    @Test
    void loadUpsertAndDeleteAiTool() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(contains("config_ai_tool"))).thenReturn(ps);
        when(statement.executeQuery(contains("config_ai_tool"))).thenReturn(rs);
        Instant now = Instant.now();
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("tool_id")).thenReturn("apm.countRecentSpans");
        when(rs.getString("name")).thenReturn("Count spans");
        when(rs.getString("category")).thenReturn("APM 内置工具");
        when(rs.getString("description")).thenReturn("Count recent spans");
        when(rs.getString("type")).thenReturn("JAVA_BEAN");
        when(rs.getString("implementation")).thenReturn("apmAgentTools.countRecentSpans");
        when(rs.getString("schema_json")).thenReturn("{}");
        when(rs.getString("config_json")).thenReturn("{}");
        when(rs.getInt("enabled")).thenReturn(1);
        when(rs.getInt("built_in")).thenReturn(1);
        when(rs.getLong("version")).thenReturn(1L);
        when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
        when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        List<ApmConfigRepository.AiToolRow> rows = repository.loadAiTools();
        Assertions.assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.toolId()).isEqualTo("apm.countRecentSpans");
            assertThat(row.category()).isEqualTo("APM 内置工具");
            assertThat(row.enabled()).isTrue();
            assertThat(row.builtIn()).isTrue();
        });
        repository.upsertAiTool(new ApmConfigRepository.AiToolRow(
                "apm.countRecentSpans", "Count spans", "APM 内置工具", "Count recent spans", "JAVA_BEAN",
                "apmAgentTools.countRecentSpans", "{}", "{}", true, true, 2L, now, now));
        repository.deleteAiTool("apm.countRecentSpans");
        verify(ps, org.mockito.Mockito.atLeast(2)).executeUpdate();
    }

    @Test
    void loadUpsertAndDeleteAiSkillStoresLocationOnly() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(contains("config_ai_skill"))).thenReturn(ps);
        when(statement.executeQuery(contains("config_ai_skill"))).thenReturn(rs);
        Instant now = Instant.now();
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("skill_id")).thenReturn("skill.data.metrics");
        when(rs.getString("name")).thenReturn("Metrics skill");
        when(rs.getString("category")).thenReturn("数据分析");
        when(rs.getString("description")).thenReturn("metric query guide");
        when(rs.getString("content_uri")).thenReturn("file:///data/skills-source/skill.data.metrics/SKILL.md");
        when(rs.getString("file_path")).thenReturn("./data/skills/skill.data.metrics/SKILL.md");
        when(rs.getInt("enabled")).thenReturn(1);
        when(rs.getInt("built_in")).thenReturn(1);
        when(rs.getLong("version")).thenReturn(1L);
        when(rs.getString("checksum")).thenReturn("sha256:abc");
        when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
        when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        List<ApmConfigRepository.AiSkillRow> rows = repository.loadAiSkills();
        Assertions.assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.skillId()).isEqualTo("skill.data.metrics");
            assertThat(row.category()).isEqualTo("数据分析");
            assertThat(row.contentUri()).startsWith("file://");
            assertThat(row.filePath()).contains("SKILL.md");
        });
        repository.upsertAiSkill(new ApmConfigRepository.AiSkillRow(
                "skill.data.metrics", "Metrics skill", "数据分析", "metric query guide",
                "file:///data/skills-source/skill.data.metrics/SKILL.md",
                "./data/skills/skill.data.metrics/SKILL.md",
                true, true, 2L, "sha256:def", now, now));
        repository.deleteAiSkill("skill.data.metrics");
        verify(ps, org.mockito.Mockito.atLeast(2)).executeUpdate();
    }

    @Test
    void loadUpsertAndDeleteAiExpert() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(contains("config_ai_expert"))).thenReturn(ps);
        when(statement.executeQuery(contains("config_ai_expert"))).thenReturn(rs);
        Instant now = Instant.now();
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("expert_id")).thenReturn("brain");
        when(rs.getString("name")).thenReturn("大脑");
        when(rs.getString("category")).thenReturn("大脑专家");
        when(rs.getString("description")).thenReturn("brain expert");
        when(rs.getString("type")).thenReturn("BRAIN");
        when(rs.getString("model_provider_code")).thenReturn("openai");
        when(rs.getString("model_name")).thenReturn("gpt-4o-mini");
        when(rs.getString("system_prompt")).thenReturn("You are brain");
        when(rs.getString("tool_ids_json")).thenReturn("[\"apm.countRecentSpans\"]");
        when(rs.getString("skill_ids_json")).thenReturn("[\"skill.brain.routing\"]");
        when(rs.getString("options_json")).thenReturn("{\"maxIters\":8}");
        when(rs.getInt("enabled")).thenReturn(1);
        when(rs.getInt("built_in")).thenReturn(1);
        when(rs.getLong("version")).thenReturn(1L);
        when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
        when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        List<ApmConfigRepository.AiExpertRow> rows = repository.loadAiExperts();
        Assertions.assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.expertId()).isEqualTo("brain");
            assertThat(row.category()).isEqualTo("大脑专家");
            assertThat(row.toolIdsJson()).contains("apm.countRecentSpans");
            assertThat(row.skillIdsJson()).contains("skill.brain.routing");
        });
        repository.upsertAiExpert(new ApmConfigRepository.AiExpertRow(
                "brain", "大脑", "大脑专家", "brain expert", "BRAIN", "openai", "gpt-4o-mini",
                "You are brain", "[\"apm.countRecentSpans\"]", "[\"skill.brain.routing\"]",
                "{\"maxIters\":8}", true, true, 2L, now, now));
        repository.deleteAiExpert("brain");
        verify(ps, org.mockito.Mockito.atLeast(2)).executeUpdate();
    }

    @Test
    void aiExpertTaskSchemaReadyWhenTableExists() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(contains("config_ai_expert_task"))).thenReturn(rs);
        when(rs.next()).thenReturn(true);

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        assertThat(repository.aiExpertTaskSchemaReady()).isTrue();
    }

    @Test
    void upsertAiExpertTask() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.prepareStatement(contains("config_ai_expert_task"))).thenReturn(ps);
        Instant now = Instant.now();

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        repository.upsertAiExpertTask(new ApmConfigRepository.AiExpertTaskRow(
                "task-1",
                null,
                "session-1",
                "brain",
                "data",
                "SUCCEEDED",
                "analyze metrics",
                "done",
                null,
                "{}",
                now,
                now,
                now));

        verify(ps).setString(1, "task-1");
        verify(ps).setString(5, "data");
        verify(ps).executeUpdate();
    }

    @Test
    void loadActiveAlarmSilences() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(contains("config_alarm_silence"))).thenReturn(rs);
        Instant now = Instant.now();
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("service")).thenReturn("demo");
        when(rs.getTimestamp("silenced_until")).thenReturn(Timestamp.from(now));
        when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        assertThat(repository.loadActiveAlarmSilences()).hasSize(1);
    }

    @Test
    void alarmSilenceSchemaReadyWhenTableExists() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(contains("config_alarm_silence"))).thenReturn(rs);
        when(rs.next()).thenReturn(true);

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        assertThat(repository.alarmSilenceSchemaReady()).isTrue();
    }

    @Test
    void tableReadyReturnsFalseOnFailure() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        when(reader.connection()).thenThrow(new SQLException("down"));
        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        assertThat(repository.tableReady(DorisTableNames.CONFIG_AI_MESSAGE)).isFalse();
    }

    @Test
    void loadAndUpsertAlarm() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(contains("config_alarm"))).thenReturn(ps);
        when(statement.executeQuery(contains("config_alarm"))).thenReturn(rs);
        Instant now = Instant.now();
        when(rs.next()).thenReturn(true, false);
        when(rs.getLong("policy_id")).thenReturn(2L);
        when(rs.getString("id")).thenReturn("A1");
        when(rs.getString("service")).thenReturn("svc");
        when(rs.getString("detection_way")).thenReturn("threshold");
        when(rs.getString("level")).thenReturn("warning");
        when(rs.getString("message")).thenReturn("msg");
        when(rs.getString("status")).thenReturn("open");
        when(rs.getTimestamp("triggered_at")).thenReturn(Timestamp.from(now));
        when(rs.getTimestamp("resolved_at")).thenReturn(null);

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        assertThat(repository.loadRecentAlarms(10)).hasSize(1);
        repository.upsertAlarm(new ApmConfigRepository.AlarmRow(
                "A3", 2L, "svc", "threshold", "warning", "msg", "open", now, null));
        verify(ps).executeUpdate();
    }

    @Test
    void loadAndUpsertNotifyChannel() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(contains("config_notify_channel"))).thenReturn(ps);
        when(statement.executeQuery(contains("config_notify_channel"))).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getLong("id")).thenReturn(1L);
        when(rs.getString("channel_type")).thenReturn("webhook");
        when(rs.getString("webhook_url")).thenReturn("https://hook");
        when(rs.getInt("enabled")).thenReturn(1);

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        assertThat(repository.loadNotifyChannel()).isPresent();
        repository.upsertNotifyChannel(new ApmConfigRepository.NotifyChannelRow(1L, "webhook", "https://x", true));
        verify(ps).executeUpdate();
    }

    @Test
    void maxEventRuleIdPresent() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getObject("max_id")).thenReturn(5L);
        when(rs.getLong("max_id")).thenReturn(5L);

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        assertThat(repository.maxEventRuleId()).contains(5L);
    }

    @Test
    void listsEventsByRuleAndService() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        Instant now = Instant.now();
        when(reader.connection()).thenReturn(connection);
        when(connection.prepareStatement(contains("config_event"))).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("id")).thenReturn("E1");
        when(rs.getLong("rule_id")).thenReturn(1L);
        when(rs.getString("rule_name")).thenReturn("demo");
        when(rs.getString("service")).thenReturn("checkout");
        when(rs.getString("detection_way")).thenReturn("threshold");
        when(rs.getString("level")).thenReturn("critical");
        when(rs.getString("status")).thenReturn("trigger");
        when(rs.getString("message")).thenReturn("breached");
        when(rs.getString("group_key")).thenReturn("checkout");
        when(rs.getInt("silenced")).thenReturn(0);
        when(rs.getTimestamp("triggered_at")).thenReturn(Timestamp.from(now));
        // readEventRow uses positional column indices (also supports joined queries).
        when(rs.getString(1)).thenReturn("E1");
        when(rs.getLong(2)).thenReturn(1L);
        when(rs.getString(3)).thenReturn("demo");
        when(rs.getString(4)).thenReturn("checkout");
        when(rs.getString(5)).thenReturn("threshold");
        when(rs.getString(6)).thenReturn("critical");
        when(rs.getString(7)).thenReturn("trigger");
        when(rs.getString(8)).thenReturn("breached");
        when(rs.getString(9)).thenReturn("checkout");
        when(rs.getInt(10)).thenReturn(0);
        when(rs.getTimestamp(11)).thenReturn(Timestamp.from(now));

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        List<ApmConfigRepository.EventRow> rows = repository.listEventsByRuleAndService(
                1L, "checkout", now.minusSeconds(60), now, "trigger");
        Assertions.assertThat(rows).hasSize(1);
        assertThat(rows.get(0).id()).isEqualTo("E1");
    }

    @Test
    void loadsAndUpsertsCockpitConfig() throws Exception {
        ApmReadRepository reader = Mockito.mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet tableRs = mock(ResultSet.class);
        ResultSet configRs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(tableRs, configRs);
        when(tableRs.next()).thenReturn(true);
        when(configRs.next()).thenReturn(true, true, false);
        when(configRs.getString("config_key")).thenReturn("errorRateThreshold", "minRequestCount");
        when(configRs.getString("config_value")).thenReturn("0.08", "20");
        when(connection.prepareStatement(contains("INSERT INTO"))).thenReturn(ps);

        ApmConfigRepository repository = new ApmConfigRepository(reader, "databuff");
        assertThat(repository.cockpitConfigSchemaReady()).isTrue();
        assertThat(repository.loadCockpitConfig())
                .containsEntry("errorRateThreshold", "0.08")
                .containsEntry("minRequestCount", "20");
        repository.upsertCockpitConfig("errorRateThreshold", "0.1");
        verify(ps).executeUpdate();
    }
}
