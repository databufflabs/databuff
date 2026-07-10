package com.databuff.apm.web.persistence;

import com.databuff.apm.web.TestStorageSupport;
import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.web.ai.agent.AiSessionStore;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiSessionPersistenceTest {

    @Test
    void listsAndCountsInMemoryWhenPersistenceDisabled() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.connection()).thenThrow(new SQLException("down"));
        AiSessionStore store = new AiSessionStore();
        store.ensureSession("s1", "brain", null, null, "admin");
        store.ensureSession("s2", "brain", null, null, "admin");
        AiSessionPersistence persistence = new AiSessionPersistence(
                reader, store, new AiMessagePersistenceQueue(reader, TestStorageSupport.storage()), TestStorageSupport.storage());
        assertThat(persistence.countSessions()).isEqualTo(2);
        assertThat(persistence.listSessions(0, 1)).hasSize(1);
        assertThat(persistence.listSessions(1, 1)).hasSize(1);
    }

    @Test
    void skipsWhenSchemaNotReady() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.connection()).thenThrow(new SQLException("down"));
        AiSessionPersistence sync = new AiSessionPersistence(
                reader, new AiSessionStore(), new AiMessagePersistenceQueue(reader, TestStorageSupport.storage()), TestStorageSupport.storage());
        sync.reloadFromStore();
        assertThat(sync.persistenceEnabled()).isFalse();
    }

    @Test
    void hydratesAndPersists() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement messagePs = mock(PreparedStatement.class);
        ResultSet schemaRs = mock(ResultSet.class);
        ResultSet sessionsRs = mock(ResultSet.class);
        ResultSet messagesRs = mock(ResultSet.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(contains("config_ai_message"))).thenReturn(messagePs);
        when(statement.executeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("LIMIT 1")) {
                return schemaRs;
            }
            return sessionsRs;
        });
        when(schemaRs.next()).thenReturn(true);
        when(sessionsRs.next()).thenReturn(true, false);
        when(sessionsRs.getString("session_id")).thenReturn("s1");
        when(sessionsRs.getString("user_id")).thenReturn("admin");
        when(sessionsRs.getString("user_name")).thenReturn("admin");
        when(sessionsRs.getString("agent")).thenReturn("brain");
        when(sessionsRs.getInt("message_count")).thenReturn(1);
        Instant now = Instant.now();
        when(sessionsRs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
        when(sessionsRs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
        when(messagePs.executeQuery()).thenReturn(messagesRs);
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
        when(messagesRs.getString("content")).thenReturn("hi");
        when(messagesRs.getString("attachments_json")).thenReturn(null);
        when(messagesRs.getString("error")).thenReturn(null);
        when(messagesRs.getString("metadata_json")).thenReturn("{}");
        when(messagesRs.getString("trigger_source")).thenReturn(null);
        when(messagesRs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
        when(messagesRs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));

        AiSessionStore store = new AiSessionStore();
        AiSessionPersistence sync = new AiSessionPersistence(
                reader, store, new AiMessagePersistenceQueue(reader, TestStorageSupport.storage()), TestStorageSupport.storage());
        sync.reloadFromStore();
        assertThat(sync.persistenceEnabled()).isTrue();
        assertThat(store.messages("s1")).hasSize(1);

        sync.onMessageAppended("s2", "assistant", "ok", now);
        Thread.sleep(300L);
        verify(messagePs).executeUpdate();

        doThrow(new SQLException("fail")).when(messagePs).executeUpdate();
        sync.onMessageAppended("s3", "assistant", "fail", now);
    }

    @Test
    void toleratesLoadFailure() throws Exception {
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

        AiSessionPersistence sync = new AiSessionPersistence(
                reader, new AiSessionStore(), new AiMessagePersistenceQueue(reader, TestStorageSupport.storage()), TestStorageSupport.storage());
        sync.reloadFromStore();
        assertThat(sync.persistenceEnabled()).isTrue();
    }
}
