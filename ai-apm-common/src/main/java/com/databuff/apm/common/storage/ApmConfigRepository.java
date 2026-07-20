package com.databuff.apm.common.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC access to {@code databuff} config tables (LLM providers, event rules, alarms).
 */
public class ApmConfigRepository {

    private final ApmReadRepository reader;
    private final String database;

    public ApmConfigRepository(ApmReadRepository reader, String database) {
        this.reader = reader;
        this.database = database;
    }

    public boolean eventRuleSchemaReady() {
        return tableReady(DorisTableNames.CONFIG_EVENT_RULE);
    }

    public boolean eventSchemaReady() {
        return tableReady(DorisTableNames.CONFIG_EVENT);
    }

    public boolean alarmPolicySchemaReady() {
        return tableReady(DorisTableNames.CONFIG_ALARM_POLICY);
    }

    public boolean schemaReady() {
        return tableReady(DorisTableNames.CONFIG_LLM_PROVIDER);
    }

    public boolean aiMessageSchemaReady() {
        return tableReady(DorisTableNames.CONFIG_AI_MESSAGE);
    }

    /** @deprecated use {@link #aiMessageSchemaReady()} */
    public boolean aiSessionSchemaReady() {
        return aiMessageSchemaReady();
    }

    public boolean aiPlatformSchemaReady() {
        return tableReady(DorisTableNames.CONFIG_AI_TOOL)
                && tableReady(DorisTableNames.CONFIG_AI_SKILL)
                && tableReady(DorisTableNames.CONFIG_AI_EXPERT)
                && tableReady(DorisTableNames.CONFIG_AI_CAPABILITY);
    }

    public boolean aiExpertTaskSchemaReady() {
        return tableReady(DorisTableNames.CONFIG_AI_EXPERT_TASK);
    }

    public boolean metricCoreSchemaReady() {
        return tableReady(DorisTableNames.CONFIG_METRIC_CORE);
    }

    public boolean alarmSilenceSchemaReady() {
        return tableReady(DorisTableNames.CONFIG_ALARM_SILENCE);
    }

    public boolean tableReady(String table) {
        try (Connection connection = reader.connection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            try (ResultSet ignored = statement.executeQuery(
                    "SELECT 1 FROM " + qualified(table) + " LIMIT 1")) {
                return true;
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public List<LlmProviderRow> loadLlmProviders() throws SQLException {
        String sql = "SELECT provider_code, display_name, base_url, enabled, api_key_cipher, default_model, api_type "
                + "FROM " + qualified(DorisTableNames.CONFIG_LLM_PROVIDER);
        List<LlmProviderRow> rows = new ArrayList<>();
        try (Connection connection = reader.connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(new LlmProviderRow(
                        rs.getString("provider_code"),
                        rs.getString("display_name"),
                        rs.getString("base_url"),
                        rs.getInt("enabled") == 1,
                        rs.getString("api_key_cipher"),
                        rs.getString("default_model"),
                        rs.getString("api_type")));
            }
        }
        return rows;
    }

    public void upsertLlmProvider(LlmProviderRow row) throws SQLException {
        String sql = "INSERT INTO " + qualified(DorisTableNames.CONFIG_LLM_PROVIDER)
                + " (provider_code, display_name, base_url, enabled, api_key_cipher, default_model, api_type, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = reader.connection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, row.providerCode());
            ps.setString(2, row.displayName());
            ps.setString(3, row.baseUrl());
            ps.setInt(4, row.enabled() ? 1 : 0);
            ps.setString(5, row.apiKeyCipher());
            ps.setString(6, row.defaultModel());
            ps.setString(7, row.apiType());
            ps.setTimestamp(8, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }

    public List<LlmModelRow> loadLlmModels() throws SQLException {
        String sql = "SELECT provider_code, model_id, display_name, context_window, max_output_tokens, "
                + "env_vars_json, is_default, enabled "
                + "FROM " + qualified(DorisTableNames.CONFIG_LLM_MODEL)
                + " ORDER BY provider_code, model_id";
        List<LlmModelRow> rows = new ArrayList<>();
        try (Connection connection = reader.connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(new LlmModelRow(
                        rs.getString("provider_code"),
                        rs.getString("model_id"),
                        rs.getString("display_name"),
                        readNullableInt(rs, "context_window"),
                        readNullableInt(rs, "max_output_tokens"),
                        rs.getString("env_vars_json"),
                        rs.getInt("is_default") == 1,
                        rs.getInt("enabled") == 1));
            }
        }
        return rows;
    }

    public void replaceLlmModels(String providerCode, List<LlmModelRow> models) throws SQLException {
        String deleteSql = "DELETE FROM " + qualified(DorisTableNames.CONFIG_LLM_MODEL)
                + " WHERE provider_code = ?";
        String insertSql = "INSERT INTO " + qualified(DorisTableNames.CONFIG_LLM_MODEL)
                + " (provider_code, model_id, display_name, context_window, max_output_tokens, env_vars_json, "
                + "is_default, enabled, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = reader.connection()) {
            try (PreparedStatement deletePs = connection.prepareStatement(deleteSql)) {
                deletePs.setString(1, providerCode);
                deletePs.executeUpdate();
            }
            if (models == null || models.isEmpty()) {
                return;
            }
            try (PreparedStatement insertPs = connection.prepareStatement(insertSql)) {
                for (LlmModelRow model : models) {
                    insertPs.setString(1, providerCode);
                    insertPs.setString(2, model.modelId());
                    insertPs.setString(3, model.displayName());
                    setNullableInt(insertPs, 4, model.contextWindow());
                    setNullableInt(insertPs, 5, model.maxOutputTokens());
                    insertPs.setString(6, model.envVarsJson());
                    insertPs.setInt(7, model.isDefault() ? 1 : 0);
                    insertPs.setInt(8, model.enabled() ? 1 : 0);
                    insertPs.setTimestamp(9, Timestamp.from(Instant.now()));
                    insertPs.addBatch();
                }
                insertPs.executeBatch();
            }
        }
    }

    private static Integer readNullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    public List<EventRuleRow> loadEventRules() throws SQLException {
        String sql = "SELECT id, rule_name, classify, detection_way, service, metric, threshold, comparator, enabled, query_json "
                + "FROM " + qualified(DorisTableNames.CONFIG_EVENT_RULE) + " ORDER BY id";
        List<EventRuleRow> rows = new ArrayList<>();
        try (Connection connection = reader.connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(new EventRuleRow(
                        rs.getLong("id"),
                        rs.getString("rule_name"),
                        rs.getString("classify"),
                        rs.getString("detection_way"),
                        rs.getString("service"),
                        rs.getString("metric"),
                        rs.getDouble("threshold"),
                        rs.getString("comparator"),
                        rs.getInt("enabled") == 1,
                        rs.getString("query_json")));
            }
        }
        return rows;
    }

    public void upsertEventRule(EventRuleRow row) throws SQLException {
        String sql = "INSERT INTO " + qualified(DorisTableNames.CONFIG_EVENT_RULE)
                + " (id, rule_name, classify, detection_way, service, metric, threshold, comparator, enabled, query_json, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = reader.connection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, row.id());
            ps.setString(2, row.ruleName());
            ps.setString(3, row.classify());
            ps.setString(4, row.detectionWay());
            ps.setString(5, row.service());
            ps.setString(6, row.metric());
            ps.setDouble(7, row.threshold());
            ps.setString(8, row.comparator());
            ps.setInt(9, row.enabled() ? 1 : 0);
            ps.setString(10, row.queryJson());
            ps.setTimestamp(11, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }

    public void deleteEventRule(long id) throws SQLException {
        String sql = "DELETE FROM " + qualified(DorisTableNames.CONFIG_EVENT_RULE) + " WHERE id = ?";
        try (Connection connection = reader.connection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public Optional<Long> maxEventRuleId() throws SQLException {
        String sql = "SELECT MAX(id) AS max_id FROM " + qualified(DorisTableNames.CONFIG_EVENT_RULE);
        try (Connection connection = reader.connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (rs.next() && rs.getObject("max_id") != null) {
                return Optional.of(rs.getLong("max_id"));
            }
        }
        return Optional.empty();
    }

    public List<AiSessionSummaryRow> loadRecentAiSessions(int limit) throws SQLException {
        return loadRecentAiSessions(limit, 0);
    }

    public List<AiSessionSummaryRow> loadRecentAiSessions(int limit, int offset) throws SQLException {
        int safeLimit = Math.max(1, limit);
        int safeOffset = Math.max(0, offset);
        String messageTable = qualified(DorisTableNames.CONFIG_AI_MESSAGE);
        String sql = "SELECT grouped.session_id, grouped.user_id, grouped.user_name, grouped.agent,"
                + " grouped.created_at, grouped.updated_at, grouped.message_count,"
                + " (SELECT m2.content FROM " + messageTable + " m2"
                + " WHERE m2.session_id = grouped.session_id"
                + " AND m2.message_type = 'USER'"
                + " AND m2.content IS NOT NULL AND TRIM(m2.content) != ''"
                + " ORDER BY m2.round_index, m2.message_index, m2.created_at, m2.message_id"
                + " LIMIT 1) AS first_user_message"
                + " FROM ("
                + " SELECT session_id, user_id, user_name, MAX(agent) AS agent,"
                + " MIN(created_at) AS created_at, MAX(updated_at) AS updated_at,"
                + " COUNT(*) AS message_count"
                + " FROM " + messageTable
                + " WHERE session_type = 'USER'"
                + " GROUP BY session_id, user_id, user_name"
                + " ) grouped"
                + " ORDER BY grouped.updated_at DESC LIMIT " + safeLimit + " OFFSET " + safeOffset;
        List<AiSessionSummaryRow> rows = new ArrayList<>();
        try (Connection connection = reader.connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(new AiSessionSummaryRow(
                        rs.getString("session_id"),
                        rs.getString("user_id"),
                        rs.getString("user_name"),
                        rs.getString("agent"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getInt("message_count"),
                        rs.getString("first_user_message")));
            }
        }
        return rows;
    }

    public long countAiSessions() throws SQLException {
        String sql = "SELECT COUNT(*) AS total FROM ("
                + "SELECT session_id FROM " + qualified(DorisTableNames.CONFIG_AI_MESSAGE)
                + " WHERE session_type = 'USER'"
                + " GROUP BY session_id, user_id, user_name"
                + ") sessions";
        try (Connection connection = reader.connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getLong("total");
            }
        }
        return 0L;
    }

    /** @deprecated use {@link #loadRecentAiSessions(int)} */
    public List<AiSessionSummaryRow> loadRecentAiSessionsLegacy(int limit) throws SQLException {
        return loadRecentAiSessions(limit);
    }

    public List<AiMessageRow> loadAiMessages(String sessionId) throws SQLException {
        return loadAiMessagesAfter(sessionId, null);
    }

    public List<AiMessageRow> loadAiMessagesAfter(String sessionId, String afterMessageId) throws SQLException {
        String sql = "SELECT session_id, message_id, session_type, user_id, user_name, agent, agent_type,"
                + " round_index, message_index, message_type, message_status, model_name, call_id, tool_name,"
                + " content, attachments_json, error, metadata_json, trigger_source, created_at, updated_at"
                + " FROM " + qualified(DorisTableNames.CONFIG_AI_MESSAGE)
                + " WHERE session_id = ?"
                + (afterMessageId == null || afterMessageId.isBlank() ? "" : " AND message_id > ?")
                + " ORDER BY round_index, message_index, created_at, message_id";
        List<AiMessageRow> rows = new ArrayList<>();
        try (Connection connection = reader.connection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            if (afterMessageId != null && !afterMessageId.isBlank()) {
                ps.setString(2, afterMessageId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(readAiMessageRow(rs));
                }
            }
        }
        return rows;
    }

    private AiMessageRow readAiMessageRow(ResultSet rs) throws SQLException {
        return new AiMessageRow(
                rs.getString("session_id"),
                rs.getString("message_id"),
                rs.getString("session_type"),
                rs.getString("user_id"),
                rs.getString("user_name"),
                rs.getString("agent"),
                rs.getString("agent_type"),
                rs.getInt("round_index"),
                rs.getInt("message_index"),
                rs.getString("message_type"),
                rs.getString("message_status"),
                rs.getString("model_name"),
                rs.getString("call_id"),
                rs.getString("tool_name"),
                rs.getString("content"),
                rs.getString("attachments_json"),
                rs.getString("error"),
                rs.getString("metadata_json"),
                rs.getString("trigger_source"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    public void upsertAiMessage(AiMessageRow row) throws SQLException {
        String sql = "INSERT INTO " + qualified(DorisTableNames.CONFIG_AI_MESSAGE)
                + " (session_id, message_id, session_type, user_id, user_name, agent, agent_type,"
                + " round_index, message_index, message_type, message_status, model_name, call_id, tool_name,"
                + " content, attachments_json, error, metadata_json, trigger_source, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = reader.connection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, row.sessionId());
            ps.setString(2, row.messageId());
            ps.setString(3, row.sessionType());
            ps.setString(4, row.userId());
            ps.setString(5, row.userName());
            ps.setString(6, row.agent());
            ps.setString(7, row.agentType());
            ps.setInt(8, row.roundIndex());
            ps.setInt(9, row.messageIndex());
            ps.setString(10, row.messageType());
            ps.setString(11, row.messageStatus());
            ps.setString(12, row.modelName());
            ps.setString(13, row.callId());
            ps.setString(14, row.toolName());
            ps.setString(15, row.content());
            ps.setString(16, row.attachmentsJson());
            ps.setString(17, row.error());
            ps.setString(18, row.metadataJson());
            ps.setString(19, row.triggerSource());
            ps.setTimestamp(20, Timestamp.from(row.createdAt()));
            ps.setTimestamp(21, Timestamp.from(row.updatedAt()));
            ps.executeUpdate();
        }
    }

    /** @deprecated use {@link #upsertAiMessage(AiMessageRow)} */
    public void insertAiMessage(AiMessageRow row) throws SQLException {
        upsertAiMessage(row);
    }

    public List<AiToolRow> loadAiTools() throws SQLException {
        String sql = "SELECT tool_id, name, category, description, type, implementation, schema_json, config_json,"
                + " enabled, built_in, version, created_at, updated_at FROM "
                + qualified(DorisTableNames.CONFIG_AI_TOOL) + " ORDER BY tool_id";
        List<AiToolRow> rows = new ArrayList<>();
        try (Connection connection = reader.connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(new AiToolRow(
                        rs.getString("tool_id"),
                        rs.getString("name"),
                        rs.getString("category"),
                        rs.getString("description"),
                        rs.getString("type"),
                        rs.getString("implementation"),
                        rs.getString("schema_json"),
                        rs.getString("config_json"),
                        rs.getInt("enabled") == 1,
                        rs.getInt("built_in") == 1,
                        rs.getLong("version"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()));
            }
        }
        return rows;
    }

    public void upsertAiTool(AiToolRow row) throws SQLException {
        String sql = "INSERT INTO " + qualified(DorisTableNames.CONFIG_AI_TOOL)
                + " (tool_id, name, category, description, type, implementation, schema_json, config_json,"
                + " enabled, built_in, version, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = reader.connection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, row.toolId());
            ps.setString(2, row.name());
            ps.setString(3, row.category());
            ps.setString(4, row.description());
            ps.setString(5, row.type());
            ps.setString(6, row.implementation());
            ps.setString(7, row.schemaJson());
            ps.setString(8, row.configJson());
            ps.setInt(9, row.enabled() ? 1 : 0);
            ps.setInt(10, row.builtIn() ? 1 : 0);
            ps.setLong(11, row.version());
            ps.setTimestamp(12, Timestamp.from(row.createdAt()));
            ps.setTimestamp(13, Timestamp.from(row.updatedAt()));
            ps.executeUpdate();
        }
    }

    public void deleteAiTool(String toolId) throws SQLException {
        String sql = "DELETE FROM " + qualified(DorisTableNames.CONFIG_AI_TOOL) + " WHERE tool_id = ?";
        try (Connection connection = reader.connection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, toolId);
            ps.executeUpdate();
        }
    }

    public List<AiSkillRow> loadAiSkills() throws SQLException {
        String sql = "SELECT skill_id, name, category, description, content_uri, file_path,"
                + " enabled, built_in, version, checksum, created_at, updated_at FROM "
                + qualified(DorisTableNames.CONFIG_AI_SKILL) + " ORDER BY skill_id";
        List<AiSkillRow> rows = new ArrayList<>();
        try (Connection connection = reader.connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(new AiSkillRow(
                        rs.getString("skill_id"),
                        rs.getString("name"),
                        rs.getString("category"),
                        rs.getString("description"),
                        rs.getString("content_uri"),
                        rs.getString("file_path"),
                        rs.getInt("enabled") == 1,
                        rs.getInt("built_in") == 1,
                        rs.getLong("version"),
                        rs.getString("checksum"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()));
            }
        }
        return rows;
    }

    public void upsertAiSkill(AiSkillRow row) throws SQLException {
        String sql = "INSERT INTO " + qualified(DorisTableNames.CONFIG_AI_SKILL)
                + " (skill_id, name, category, description, content_uri, file_path,"
                + " enabled, built_in, version, checksum, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = reader.connection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, row.skillId());
            ps.setString(2, row.name());
            ps.setString(3, row.category());
            ps.setString(4, row.description());
            ps.setString(5, row.contentUri());
            ps.setString(6, row.filePath());
            ps.setInt(7, row.enabled() ? 1 : 0);
            ps.setInt(8, row.builtIn() ? 1 : 0);
            ps.setLong(9, row.version());
            ps.setString(10, row.checksum());
            ps.setTimestamp(11, Timestamp.from(row.createdAt()));
            ps.setTimestamp(12, Timestamp.from(row.updatedAt()));
            ps.executeUpdate();
        }
    }

    public void deleteAiSkill(String skillId) throws SQLException {
        String sql = "DELETE FROM " + qualified(DorisTableNames.CONFIG_AI_SKILL) + " WHERE skill_id = ?";
        try (Connection connection = reader.connection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, skillId);
            ps.executeUpdate();
        }
    }

    public List<AiExpertRow> loadAiExperts() throws SQLException {
        String sql = "SELECT expert_id, name, category, description, type, model_provider_code, model_name,"
                + " system_prompt, tool_ids_json, skill_ids_json, options_json,"
                + " enabled, built_in, version, created_at, updated_at FROM "
                + qualified(DorisTableNames.CONFIG_AI_EXPERT) + " ORDER BY expert_id";
        List<AiExpertRow> rows = new ArrayList<>();
        try (Connection connection = reader.connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(new AiExpertRow(
                        rs.getString("expert_id"),
                        rs.getString("name"),
                        rs.getString("category"),
                        rs.getString("description"),
                        rs.getString("type"),
                        rs.getString("model_provider_code"),
                        rs.getString("model_name"),
                        rs.getString("system_prompt"),
                        rs.getString("tool_ids_json"),
                        rs.getString("skill_ids_json"),
                        rs.getString("options_json"),
                        rs.getInt("enabled") == 1,
                        rs.getInt("built_in") == 1,
                        rs.getLong("version"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()));
            }
        }
        return rows;
    }

    public void upsertAiExpert(AiExpertRow row) throws SQLException {
        String sql = "INSERT INTO " + qualified(DorisTableNames.CONFIG_AI_EXPERT)
                + " (expert_id, name, category, description, type, model_provider_code, model_name,"
                + " system_prompt, tool_ids_json, skill_ids_json, options_json,"
                + " enabled, built_in, version, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = reader.connection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, row.expertId());
            ps.setString(2, row.name());
            ps.setString(3, row.category());
            ps.setString(4, row.description());
            ps.setString(5, row.type());
            ps.setString(6, row.modelProviderCode());
            ps.setString(7, row.modelName());
            ps.setString(8, row.systemPrompt());
            ps.setString(9, row.toolIdsJson());
            ps.setString(10, row.skillIdsJson());
            ps.setString(11, row.optionsJson());
            ps.setInt(12, row.enabled() ? 1 : 0);
            ps.setInt(13, row.builtIn() ? 1 : 0);
            ps.setLong(14, row.version());
            ps.setTimestamp(15, Timestamp.from(row.createdAt()));
            ps.setTimestamp(16, Timestamp.from(row.updatedAt()));
            ps.executeUpdate();
        }
    }

    public void deleteAiExpert(String expertId) throws SQLException {
        String sql = "DELETE FROM " + qualified(DorisTableNames.CONFIG_AI_EXPERT) + " WHERE expert_id = ?";
        try (Connection connection = reader.connection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, expertId);
            ps.executeUpdate();
        }
    }

    public void upsertAiExpertTask(AiExpertTaskRow row) throws SQLException {
        String sql = "INSERT INTO " + qualified(DorisTableNames.CONFIG_AI_EXPERT_TASK)
                + " (task_id, parent_task_id, session_id, source_expert_id, target_expert_id, status,"
                + " input_text, output_text, error_text, metadata_json, created_at, updated_at, completed_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = reader.connection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, row.taskId());
            ps.setString(2, row.parentTaskId());
            ps.setString(3, row.sessionId());
            ps.setString(4, row.sourceExpertId());
            ps.setString(5, row.targetExpertId());
            ps.setString(6, row.status());
            ps.setString(7, row.input());
            ps.setString(8, row.output());
            ps.setString(9, row.error());
            ps.setString(10, row.metadataJson());
            ps.setTimestamp(11, Timestamp.from(row.createdAt()));
            ps.setTimestamp(12, Timestamp.from(row.updatedAt()));
            ps.setTimestamp(13, row.completedAt() == null ? null : Timestamp.from(row.completedAt()));
            ps.executeUpdate();
        }
    }

    public List<AiCapabilityRow> loadAiCapabilities() throws SQLException {
        String sql = "SELECT capability_id, name, tagline, expert_id, prompts_json,"
                + " enabled, built_in, version, created_at, updated_at,"
                + " default_name, default_tagline, default_expert_id, default_prompts_json FROM "
                + qualified(DorisTableNames.CONFIG_AI_CAPABILITY) + " ORDER BY capability_id";
        List<AiCapabilityRow> rows = new ArrayList<>();
        try (Connection connection = reader.connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(new AiCapabilityRow(
                        rs.getString("capability_id"),
                        rs.getString("name"),
                        rs.getString("tagline"),
                        rs.getString("expert_id"),
                        rs.getString("prompts_json"),
                        rs.getInt("enabled") == 1,
                        rs.getInt("built_in") == 1,
                        rs.getLong("version"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getString("default_name"),
                        rs.getString("default_tagline"),
                        rs.getString("default_expert_id"),
                        rs.getString("default_prompts_json")));
            }
        }
        return rows;
    }

    public void upsertAiCapability(AiCapabilityRow row) throws SQLException {
        String sql = "INSERT INTO " + qualified(DorisTableNames.CONFIG_AI_CAPABILITY)
                + " (capability_id, name, tagline, expert_id, prompts_json,"
                + " enabled, built_in, version, created_at, updated_at,"
                + " default_name, default_tagline, default_expert_id, default_prompts_json)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = reader.connection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, row.capabilityId());
            ps.setString(2, row.name());
            ps.setString(3, row.tagline());
            ps.setString(4, row.expertId());
            ps.setString(5, row.promptsJson());
            ps.setInt(6, row.enabled() ? 1 : 0);
            ps.setInt(7, row.builtIn() ? 1 : 0);
            ps.setLong(8, row.version());
            ps.setTimestamp(9, Timestamp.from(row.createdAt()));
            ps.setTimestamp(10, Timestamp.from(row.updatedAt()));
            ps.setString(11, row.defaultName());
            ps.setString(12, row.defaultTagline());
            ps.setString(13, row.defaultExpertId());
            ps.setString(14, row.defaultPromptsJson());
            ps.executeUpdate();
        }
    }

    public void deleteAiCapability(String capabilityId) throws SQLException {
        String sql = "DELETE FROM " + qualified(DorisTableNames.CONFIG_AI_CAPABILITY) + " WHERE capability_id = ?";
        try (Connection connection = reader.connection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, capabilityId);
            ps.executeUpdate();
        }
    }

    public List<AlarmSilenceRow> loadActiveAlarmSilences() throws SQLException {
        String sql = "SELECT service, silenced_until, updated_at FROM "
                + qualified(DorisTableNames.CONFIG_ALARM_SILENCE) + " WHERE silenced_until > NOW()";
        List<AlarmSilenceRow> rows = new ArrayList<>();
        try (Connection connection = reader.connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(new AlarmSilenceRow(
                        rs.getString("service"),
                        rs.getTimestamp("silenced_until").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()));
            }
        }
        return rows;
    }

    public void upsertAlarmSilence(AlarmSilenceRow row) throws SQLException {
        String sql = "INSERT INTO " + qualified(DorisTableNames.CONFIG_ALARM_SILENCE)
                + " (service, silenced_until, updated_at) VALUES (?, ?, ?)";
        try (Connection connection = reader.connection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, row.service());
            ps.setTimestamp(2, Timestamp.from(row.silencedUntil()));
            ps.setTimestamp(3, Timestamp.from(row.updatedAt()));
            ps.executeUpdate();
        }
    }

    public void upsertEvent(EventRow row) throws SQLException {
        String sql = "INSERT INTO " + qualified(DorisTableNames.CONFIG_EVENT)
                + " (id, rule_id, rule_name, service, detection_way, level, status, message, group_key, silenced, triggered_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = reader.connection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, row.id());
            ps.setLong(2, row.ruleId());
            ps.setString(3, row.ruleName());
            ps.setString(4, row.service());
            ps.setString(5, row.detectionWay());
            ps.setString(6, row.level());
            ps.setString(7, row.status());
            ps.setString(8, row.message());
            ps.setString(9, row.groupKey());
            ps.setInt(10, row.silenced() ? 1 : 0);
            ps.setTimestamp(11, Timestamp.from(row.triggeredAt()));
            ps.executeUpdate();
        }
    }

    public List<EventRow> loadRecentEvents(int limit) throws SQLException {
        String sql = "SELECT id, rule_id, rule_name, service, detection_way, level, status, message, group_key, silenced, triggered_at "
                + "FROM " + qualified(DorisTableNames.CONFIG_EVENT)
                + " ORDER BY triggered_at DESC LIMIT " + limit;
        List<EventRow> rows = new ArrayList<>();
        try (Connection connection = reader.connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(readEventRow(rs));
            }
        }
        return rows;
    }

    public Optional<EventRow> loadEventById(String id) throws SQLException {
        String sql = "SELECT id, rule_id, rule_name, service, detection_way, level, status, message, group_key, silenced, triggered_at "
                + "FROM " + qualified(DorisTableNames.CONFIG_EVENT)
                + " WHERE id = ? LIMIT 1";
        try (Connection connection = reader.connection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(readEventRow(rs));
            }
        }
    }

    public List<EventRow> listEventsByRuleAndService(
            long ruleId,
            String service,
            Instant from,
            Instant to,
            String status) throws SQLException {
        String sql = "SELECT e.id, e.rule_id, e.rule_name, e.service, e.detection_way, e.level, e.status, e.message, e.group_key, e.silenced, e.triggered_at "
                + "FROM " + qualified(DorisTableNames.CONFIG_EVENT) + " e "
                + "WHERE e.rule_id = ? AND e.service = ? AND e.status = ?"
                + " AND triggered_at >= ? AND triggered_at <= ?"
                + " ORDER BY triggered_at DESC";
        List<EventRow> rows = new ArrayList<>();
        try (Connection connection = reader.connection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, ruleId);
            ps.setString(2, service);
            ps.setString(3, status);
            ps.setTimestamp(4, Timestamp.from(from));
            ps.setTimestamp(5, Timestamp.from(to));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(readEventRow(rs));
                }
            }
        }
        return rows;
    }

    public List<EventRow> listEventsByAlarmId(String alarmId, String status) throws SQLException {
        if (alarmId == null || alarmId.isBlank()) {
            return List.of();
        }
        Map<String, List<EventRow>> grouped = listEventsByAlarmIds(List.of(alarmId), status);
        return grouped.getOrDefault(alarmId, List.of());
    }

    private static final int ALARM_EVENT_BATCH_SIZE = 500;

    public Map<String, List<EventRow>> listEventsByAlarmIds(Collection<String> alarmIds, String status) throws SQLException {
        if (alarmIds == null || alarmIds.isEmpty()) {
            return Map.of();
        }
        List<String> ids = alarmIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, List<EventRow>> grouped = new LinkedHashMap<>();
        for (int offset = 0; offset < ids.size(); offset += ALARM_EVENT_BATCH_SIZE) {
            List<String> batch = ids.subList(offset, Math.min(offset + ALARM_EVENT_BATCH_SIZE, ids.size()));
            String placeholders = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
            String sql = "SELECT rel.alarm_id, e.id, e.rule_id, e.rule_name, e.service, e.detection_way, e.level, e.status, e.message, e.group_key, e.silenced, e.triggered_at "
                    + "FROM " + qualified(DorisTableNames.CONFIG_ALARM_EVENT) + " rel "
                    + "INNER JOIN " + qualified(DorisTableNames.CONFIG_EVENT) + " e ON rel.event_id = e.id "
                    + "WHERE rel.alarm_id IN (" + placeholders + ") AND e.status = ? "
                    + "ORDER BY rel.alarm_id, e.triggered_at DESC";
            try (Connection connection = reader.connection();
                 PreparedStatement ps = connection.prepareStatement(sql)) {
                int index = 1;
                for (String alarmId : batch) {
                    ps.setString(index++, alarmId);
                }
                ps.setString(index, status);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String alarmId = rs.getString(1);
                        grouped.computeIfAbsent(alarmId, key -> new ArrayList<>())
                                .add(readEventRow(rs, 2));
                    }
                }
            }
        }
        grouped.replaceAll((alarmId, rows) -> List.copyOf(rows));
        return grouped;
    }

    public void upsertAlarmEvent(AlarmEventRow row) throws SQLException {
        String sql = "INSERT INTO " + qualified(DorisTableNames.CONFIG_ALARM_EVENT)
                + " (alarm_id, event_id, linked_at) VALUES (?, ?, ?)";
        try (Connection connection = reader.connection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, row.alarmId());
            ps.setString(2, row.eventId());
            ps.setTimestamp(3, Timestamp.from(row.linkedAt()));
            ps.executeUpdate();
        }
    }

    public long countEventsByRuleAndService(
            long ruleId,
            String service,
            Instant from,
            Instant to,
            String status) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + qualified(DorisTableNames.CONFIG_EVENT)
                + " WHERE rule_id = ? AND service = ? AND status = ?"
                + " AND triggered_at >= ? AND triggered_at <= ?";
        try (Connection connection = reader.connection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, ruleId);
            ps.setString(2, service);
            ps.setString(3, status);
            ps.setTimestamp(4, Timestamp.from(from));
            ps.setTimestamp(5, Timestamp.from(to));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private static EventRow readEventRow(ResultSet rs) throws SQLException {
        return readEventRow(rs, 1);
    }

    private static EventRow readEventRow(ResultSet rs, int fromColumn) throws SQLException {
        return new EventRow(
                rs.getString(fromColumn),
                rs.getLong(fromColumn + 1),
                rs.getString(fromColumn + 2),
                rs.getString(fromColumn + 3),
                rs.getString(fromColumn + 4),
                rs.getString(fromColumn + 5),
                rs.getString(fromColumn + 6),
                rs.getString(fromColumn + 7),
                rs.getString(fromColumn + 8),
                rs.getInt(fromColumn + 9) == 1,
                rs.getTimestamp(fromColumn + 10).toInstant());
    }

    public void upsertAlarmPolicy(AlarmPolicyRow row) throws SQLException {
        String sql = "INSERT INTO " + qualified(DorisTableNames.CONFIG_ALARM_POLICY)
                + " (policy_type, policy_id, policy_name, enabled, body_json, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection connection = reader.connection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, row.policyType());
            ps.setLong(2, row.policyId());
            ps.setString(3, row.policyName());
            ps.setInt(4, row.enabled() ? 1 : 0);
            ps.setString(5, row.bodyJson());
            ps.setTimestamp(6, Timestamp.from(row.updatedAt()));
            ps.executeUpdate();
        }
    }

    public void deleteAlarmPolicy(String policyType, long policyId) throws SQLException {
        String sql = "DELETE FROM " + qualified(DorisTableNames.CONFIG_ALARM_POLICY)
                + " WHERE policy_type = ? AND policy_id = ?";
        try (Connection connection = reader.connection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, policyType);
            ps.setLong(2, policyId);
            ps.executeUpdate();
        }
    }

    public List<AlarmPolicyRow> loadAlarmPolicies(String policyType) throws SQLException {
        String sql = "SELECT policy_type, policy_id, policy_name, enabled, body_json, updated_at FROM "
                + qualified(DorisTableNames.CONFIG_ALARM_POLICY)
                + " WHERE policy_type = ? ORDER BY policy_id";
        List<AlarmPolicyRow> rows = new ArrayList<>();
        try (Connection connection = reader.connection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, policyType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new AlarmPolicyRow(
                            rs.getString("policy_type"),
                            rs.getLong("policy_id"),
                            rs.getString("policy_name"),
                            rs.getInt("enabled") == 1,
                            rs.getString("body_json"),
                            rs.getTimestamp("updated_at").toInstant()));
                }
            }
        }
        return rows;
    }

    public boolean alarmSchemaReady() {
        return tableReady(DorisTableNames.CONFIG_ALARM);
    }

    public List<AlarmRow> loadRecentAlarms(int limit) throws SQLException {
        String sql = "SELECT id, policy_id, service, detection_way, level, message, status, triggered_at, resolved_at "
                + "FROM " + qualified(DorisTableNames.CONFIG_ALARM) + " ORDER BY triggered_at DESC LIMIT " + limit;
        List<AlarmRow> rows = new ArrayList<>();
        try (Connection connection = reader.connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(mapAlarmRow(rs));
            }
        }
        return rows;
    }

    public void upsertAlarm(AlarmRow row) throws SQLException {
        String sql = "INSERT INTO " + qualified(DorisTableNames.CONFIG_ALARM)
                + " (id, policy_id, service, detection_way, level, message, status, triggered_at, resolved_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = reader.connection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, row.id());
            ps.setLong(2, row.policyId());
            ps.setString(3, row.service());
            ps.setString(4, row.detectionWay());
            ps.setString(5, row.level());
            ps.setString(6, row.message());
            ps.setString(7, row.status());
            ps.setTimestamp(8, Timestamp.from(row.triggeredAt()));
            if (row.resolvedAt() == null) {
                ps.setTimestamp(9, null);
            } else {
                ps.setTimestamp(9, Timestamp.from(row.resolvedAt()));
            }
            ps.executeUpdate();
        }
    }

    public boolean notifyChannelSchemaReady() {
        return tableReady("dim_notify_channel");
    }

    public Optional<NotifyChannelRow> loadNotifyChannel() throws SQLException {
        String sql = "SELECT id, channel_type, webhook_url, enabled FROM "
                + qualified(DorisTableNames.CONFIG_NOTIFY_CHANNEL) + " ORDER BY id LIMIT 1";
        try (Connection connection = reader.connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (rs.next()) {
                return Optional.of(new NotifyChannelRow(
                        rs.getLong("id"),
                        rs.getString("channel_type"),
                        rs.getString("webhook_url"),
                        rs.getInt("enabled") == 1));
            }
        }
        return Optional.empty();
    }

    public void upsertNotifyChannel(NotifyChannelRow row) throws SQLException {
        String sql = "INSERT INTO " + qualified(DorisTableNames.CONFIG_NOTIFY_CHANNEL)
                + " (id, channel_type, webhook_url, enabled, updated_at) VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = reader.connection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, row.id());
            ps.setString(2, row.channelType());
            ps.setString(3, row.webhookUrl());
            ps.setInt(4, row.enabled() ? 1 : 0);
            ps.setTimestamp(5, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }

    private AlarmRow mapAlarmRow(ResultSet rs) throws SQLException {
        Timestamp resolved = rs.getTimestamp("resolved_at");
        return new AlarmRow(
                rs.getString("id"),
                rs.getLong("policy_id"),
                rs.getString("service"),
                rs.getString("detection_way"),
                rs.getString("level"),
                rs.getString("message"),
                rs.getString("status"),
                rs.getTimestamp("triggered_at").toInstant(),
                resolved == null ? null : resolved.toInstant());
    }

    private String qualified(String table) {
        return database + "." + table;
    }

    public record LlmProviderRow(
            String providerCode,
            String displayName,
            String baseUrl,
            boolean enabled,
            String apiKeyCipher,
            String defaultModel,
            String apiType) {

        public LlmProviderRow(
                String providerCode,
                String displayName,
                String baseUrl,
                boolean enabled,
                String apiKeyCipher,
                String defaultModel) {
            this(providerCode, displayName, baseUrl, enabled, apiKeyCipher, defaultModel, null);
        }
    }

    public record LlmModelRow(
            String providerCode,
            String modelId,
            String displayName,
            Integer contextWindow,
            Integer maxOutputTokens,
            String envVarsJson,
            boolean isDefault,
            boolean enabled) {
    }

    public record EventRuleRow(
            long id,
            String ruleName,
            String classify,
            String detectionWay,
            String service,
            String metric,
            double threshold,
            String comparator,
            boolean enabled,
            String queryJson) {
    }

    public record EventRow(
            String id,
            long ruleId,
            String ruleName,
            String service,
            String detectionWay,
            String level,
            String status,
            String message,
            String groupKey,
            boolean silenced,
            Instant triggeredAt) {
    }

    public record AlarmEventRow(
            String alarmId,
            String eventId,
            Instant linkedAt) {
    }

    public record AlarmPolicyRow(
            String policyType,
            long policyId,
            String policyName,
            boolean enabled,
            String bodyJson,
            Instant updatedAt) {
    }

    public record AiSessionSummaryRow(
            String sessionId,
            String userId,
            String userName,
            String agent,
            Instant createdAt,
            Instant updatedAt,
            int messageCount,
            String firstUserMessage) {
    }

    public record AiMessageRow(
            String sessionId,
            String messageId,
            String sessionType,
            String userId,
            String userName,
            String agent,
            String agentType,
            int roundIndex,
            int messageIndex,
            String messageType,
            String messageStatus,
            String modelName,
            String callId,
            String toolName,
            String content,
            String attachmentsJson,
            String error,
            String metadataJson,
            String triggerSource,
            Instant createdAt,
            Instant updatedAt) {

        public AiMessageRow(
                String sessionId,
                String messageId,
                String messageType,
                String content,
                Instant createdAt) {
            this(
                    sessionId,
                    messageId,
                    "USER",
                    "admin",
                    "admin",
                    "brain",
                    "AGENT",
                    1,
                    1,
                    messageType,
                    "COMPLETED",
                    null,
                    null,
                    null,
                    content,
                    null,
                    null,
                    "{}",
                    null,
                    createdAt,
                    createdAt);
        }
    }

    public record AiToolRow(
            String toolId,
            String name,
            String category,
            String description,
            String type,
            String implementation,
            String schemaJson,
            String configJson,
            boolean enabled,
            boolean builtIn,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record AiSkillRow(
            String skillId,
            String name,
            String category,
            String description,
            String contentUri,
            String filePath,
            boolean enabled,
            boolean builtIn,
            long version,
            String checksum,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record AiExpertRow(
            String expertId,
            String name,
            String category,
            String description,
            String type,
            String modelProviderCode,
            String modelName,
            String systemPrompt,
            String toolIdsJson,
            String skillIdsJson,
            String optionsJson,
            boolean enabled,
            boolean builtIn,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record AiCapabilityRow(
            String capabilityId,
            String name,
            String tagline,
            String expertId,
            String promptsJson,
            boolean enabled,
            boolean builtIn,
            long version,
            Instant createdAt,
            Instant updatedAt,
            String defaultName,
            String defaultTagline,
            String defaultExpertId,
            String defaultPromptsJson) {
    }

    public record AiExpertTaskRow(
            String taskId,
            String parentTaskId,
            String sessionId,
            String sourceExpertId,
            String targetExpertId,
            String status,
            String input,
            String output,
            String error,
            String metadataJson,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt) {
    }

    public record AlarmSilenceRow(
            String service,
            Instant silencedUntil,
            Instant updatedAt) {
    }

    public record AlarmRow(
            String id,
            long policyId,
            String service,
            String detectionWay,
            String level,
            String message,
            String status,
            Instant triggeredAt,
            Instant resolvedAt) {
    }

    public record NotifyChannelRow(
            long id,
            String channelType,
            String webhookUrl,
            boolean enabled) {
    }

    public boolean cockpitConfigSchemaReady() {
        return tableReady(DorisTableNames.CONFIG_COCKPIT);
    }

    public java.util.Map<String, String> loadCockpitConfig() throws SQLException {
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        String sql = "SELECT config_key, config_value FROM " + qualified(DorisTableNames.CONFIG_COCKPIT);
        try (Connection connection = reader.connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                values.put(rs.getString("config_key"), rs.getString("config_value"));
            }
        }
        return values;
    }

    public void upsertCockpitConfig(String key, String value) throws SQLException {
        String sql = "INSERT INTO " + qualified(DorisTableNames.CONFIG_COCKPIT)
                + " (config_key, config_value, updated_at) VALUES (?, ?, ?)";
        try (Connection connection = reader.connection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.setTimestamp(3, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }

    public long countMetricCoreRows() throws SQLException {
        String sql = "SELECT COUNT(*) AS cnt FROM " + qualified(DorisTableNames.CONFIG_METRIC_CORE);
        try (Connection connection = reader.connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getLong("cnt") : 0L;
        }
    }

    public List<MetricCoreConfigRow> loadMetricCoreRows() throws SQLException {
        String sql = "SELECT id, type1, type2, type3, app, database_name, measurement, doris_table,"
                + " description, tag_key_json, tag_value_json, fields_json, enabled, builtin, updated_at"
                + " FROM " + qualified(DorisTableNames.CONFIG_METRIC_CORE) + " ORDER BY id";
        List<MetricCoreConfigRow> rows = new ArrayList<>();
        try (Connection connection = reader.connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                Timestamp updatedAt = rs.getTimestamp("updated_at");
                rows.add(new MetricCoreConfigRow(
                        rs.getLong("id"),
                        rs.getString("type1"),
                        rs.getString("type2"),
                        rs.getString("type3"),
                        rs.getString("app"),
                        rs.getString("database_name"),
                        rs.getString("measurement"),
                        rs.getString("doris_table"),
                        rs.getString("description"),
                        rs.getString("tag_key_json"),
                        rs.getString("tag_value_json"),
                        rs.getString("fields_json"),
                        rs.getInt("enabled") == 1,
                        rs.getInt("builtin") == 1,
                        updatedAt == null ? null : updatedAt.toInstant()));
            }
        }
        return rows;
    }

    public void upsertMetricCoreRow(MetricCoreConfigRow row) throws SQLException {
        String sql = "INSERT INTO " + qualified(DorisTableNames.CONFIG_METRIC_CORE)
                + " (id, type1, type2, type3, app, database_name, measurement, doris_table,"
                + " description, tag_key_json, tag_value_json, fields_json, enabled, builtin, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = reader.connection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, row.id());
            ps.setString(2, row.type1());
            ps.setString(3, row.type2());
            ps.setString(4, row.type3());
            ps.setString(5, row.app());
            ps.setString(6, row.databaseName());
            ps.setString(7, row.measurement());
            ps.setString(8, row.dorisTable());
            ps.setString(9, row.description());
            ps.setString(10, row.tagKeyJson());
            ps.setString(11, row.tagValueJson());
            ps.setString(12, row.fieldsJson());
            ps.setInt(13, row.enabled() ? 1 : 0);
            ps.setInt(14, row.builtin() ? 1 : 0);
            ps.setTimestamp(15, Timestamp.from(row.updatedAt() == null ? Instant.now() : row.updatedAt()));
            ps.executeUpdate();
        }
    }

    public record MetricCoreConfigRow(
            long id,
            String type1,
            String type2,
            String type3,
            String app,
            String databaseName,
            String measurement,
            String dorisTable,
            String description,
            String tagKeyJson,
            String tagValueJson,
            String fieldsJson,
            boolean enabled,
            boolean builtin,
            Instant updatedAt) {
    }
}
