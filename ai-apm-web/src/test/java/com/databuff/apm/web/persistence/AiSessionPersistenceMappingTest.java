package com.databuff.apm.web.persistence;

import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.web.TestStorageSupport;
import com.databuff.apm.web.ai.agent.AiMessageStatus;
import com.databuff.apm.web.ai.agent.AiMessageType;
import com.databuff.apm.web.ai.agent.AiSessionStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiSessionPersistenceMappingTest {

    @Test
    void lazyEnablesPersistenceWithoutReload() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement messagePs = mock(PreparedStatement.class);
        ResultSet schemaRs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(contains("config_ai_message"))).thenReturn(messagePs);
        when(statement.executeQuery(anyString())).thenReturn(schemaRs);
        when(schemaRs.next()).thenReturn(true);

        AiSessionStore store = new AiSessionStore();
        AiMessagePersistenceQueue queue = new AiMessagePersistenceQueue(reader, TestStorageSupport.storage());
        AiSessionPersistence persistence = new AiSessionPersistence(reader, store, queue, TestStorageSupport.storage());
        assertThat(persistence.persistenceEnabled()).isFalse();

        String sessionId = store.ensureSession(null, "brain", "rk", "web-1", "alice");
        store.appendUserMessage(sessionId, "你好", "brain", "alice", Map.of("service", "demo"));
        store.completeRound(sessionId, "brain");
        Thread.sleep(300L);

        verify(messagePs, org.mockito.Mockito.atLeastOnce()).executeUpdate();
        assertThat(persistence.persistenceEnabled()).isTrue();
    }

    @Test
    void mapsRoundAndMessageIndexToSql() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement messagePs = mock(PreparedStatement.class);
        ResultSet schemaRs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(contains("config_ai_message"))).thenReturn(messagePs);
        when(statement.executeQuery(anyString())).thenReturn(schemaRs);
        when(schemaRs.next()).thenReturn(true);

        AiSessionStore store = new AiSessionStore();
        AiMessagePersistenceQueue queue = new AiMessagePersistenceQueue(reader, TestStorageSupport.storage());
        AiSessionPersistence persistence = new AiSessionPersistence(reader, store, queue, TestStorageSupport.storage());
        String sessionId = store.ensureSession(null, "brain", "rk", "web-1", "alice");
        store.appendUserMessage(sessionId, "hello", "brain", "alice", Map.of());
        store.appendTraceMessage(
                sessionId,
                "brain",
                "alice",
                AiMessageType.TOOL_CALL,
                "call tool",
                AiMessageStatus.COMPLETED,
                Map.of("toolName", "serviceErrorRate"));
        String assistantId = store.reserveAssistantMessageId(sessionId, "brain");
        store.appendOrUpdateAssistantText(
                sessionId, assistantId, "brain", "done", AiMessageStatus.COMPLETED, Map.of());
        store.completeRound(sessionId, "brain");
        Thread.sleep(300L);

        AiSessionStore.ChatMessage toolCall = store.messages(sessionId).stream()
                .filter(message -> AiMessageType.TOOL_CALL.name().equals(message.messageType()))
                .findFirst()
                .orElseThrow();

        verify(messagePs, org.mockito.Mockito.atLeast(2)).executeUpdate();
        verify(messagePs, org.mockito.Mockito.atLeastOnce()).setInt(8, toolCall.roundIndex());
        verify(messagePs, org.mockito.Mockito.atLeastOnce()).setInt(9, toolCall.messageIndex());
        verify(messagePs, org.mockito.Mockito.atLeastOnce()).setString(10, "TOOL_CALL");
        verify(messagePs, org.mockito.Mockito.atLeastOnce()).setString(11, "COMPLETED");
        verify(messagePs, org.mockito.Mockito.atLeastOnce()).setString(14, "serviceErrorRate");
    }

    @Test
    void persistsToolResultInMetadataJson() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement messagePs = mock(PreparedStatement.class);
        ResultSet schemaRs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(contains("config_ai_message"))).thenReturn(messagePs);
        when(statement.executeQuery(anyString())).thenReturn(schemaRs);
        when(schemaRs.next()).thenReturn(true);

        AiSessionStore store = new AiSessionStore();
        new AiSessionPersistence(
                reader,
                store,
                new AiMessagePersistenceQueue(reader, TestStorageSupport.storage()),
                TestStorageSupport.storage());
        String sessionId = store.ensureSession(null, "data", "rk", "web-1", "alice");
        store.appendUserMessage(sessionId, "hello", "data", "alice", Map.of());
        store.appendTraceMessage(
                sessionId,
                "data",
                "alice",
                AiMessageType.TOOL_RESULT,
                "工具调用结果：queryServicesAll",
                AiMessageStatus.COMPLETED,
                Map.of(
                        "toolName", "queryServicesAll",
                        "toolCallId", "call-1",
                        "toolResult", "{\"ok\":true}"));
        store.completeRound(sessionId, "data");
        Thread.sleep(300L);

        ArgumentCaptor<String> metadataJson = ArgumentCaptor.forClass(String.class);
        verify(messagePs, org.mockito.Mockito.atLeastOnce()).setString(eq(18), metadataJson.capture());
        assertThat(metadataJson.getAllValues())
                .anySatisfy(json -> assertThat(json).contains("\"toolResult\":\"{\\\"ok\\\":true}\""));
    }

    @Test
    void hydrateFailureKeepsPersistenceEnabled() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet schemaRs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("LIMIT 1")) {
                return schemaRs;
            }
            throw new SQLException("load failed");
        });
        when(schemaRs.next()).thenReturn(true);

        AiMessagePersistenceQueue queue = new AiMessagePersistenceQueue(reader, TestStorageSupport.storage());
        AiSessionPersistence persistence = new AiSessionPersistence(reader, new AiSessionStore(), queue, TestStorageSupport.storage());
        persistence.reloadFromStore();
        assertThat(persistence.persistenceEnabled()).isTrue();
    }
}
