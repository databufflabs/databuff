package com.databuff.apm.web.persistence;

import com.databuff.apm.web.TestStorageSupport;
import com.databuff.apm.web.ai.TestBeanSupport;
import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.web.ai.InMemoryLlmProviderStore;
import com.databuff.apm.web.ai.LlmProviderView;
import com.databuff.apm.web.ai.UpdateLlmProviderRequest;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmProviderPersistenceTest {

    @Test
    void skipsPersistWhenStoreUnavailable() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.connection()).thenThrow(new SQLException("down"));
        InMemoryLlmProviderStore store = TestBeanSupport.llmProviderStore();
        LlmProviderPersistence sync = new LlmProviderPersistence(reader, store, TestStorageSupport.storage());
        sync.reloadFromStore();
        LlmProviderView view = store.updateProvider("openai", new UpdateLlmProviderRequest(null, "sk", null, true));
        assertThat(sync.persistenceEnabled()).isFalse();
        assertThatThrownBy(() -> sync.persistUpdate("openai", new UpdateLlmProviderRequest(null, "sk", null, true), view))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("配置库暂不可用");
        assertThat(sync.persistenceEnabled()).isFalse();
    }

    @Test
    void loadsRowsAndPersistsUpdates() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet schemaRs = mock(ResultSet.class);
        ResultSet providersRs = mock(ResultSet.class);
        ResultSet modelsRs = mock(ResultSet.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(contains("config_llm_provider"))).thenReturn(ps);
        when(connection.prepareStatement(contains("config_llm_model"))).thenReturn(ps);
        when(statement.executeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("LIMIT 1")) {
                return schemaRs;
            }
            if (sql.contains("config_llm_model")) {
                return modelsRs;
            }
            return providersRs;
        });
        when(schemaRs.next()).thenReturn(true);
        when(providersRs.next()).thenReturn(true, false);
        when(modelsRs.next()).thenReturn(false);
        when(providersRs.getString("provider_code")).thenReturn("openai");
        when(providersRs.getString("display_name")).thenReturn("OpenAI");
        when(providersRs.getString("base_url")).thenReturn("https://api.openai.com/v1");
        when(providersRs.getInt("enabled")).thenReturn(1);
        when(providersRs.getString("api_key_cipher")).thenReturn("c2s=");
        when(providersRs.getString("default_model")).thenReturn("gpt-4o-mini");
        when(providersRs.getString("api_type")).thenReturn("openai-completions");

        InMemoryLlmProviderStore store = TestBeanSupport.llmProviderStore();
        LlmProviderPersistence sync = new LlmProviderPersistence(reader, store, TestStorageSupport.storage());
        sync.reloadFromStore();
        assertThat(sync.persistenceEnabled()).isTrue();

        LlmProviderView view = store.updateProvider("openai", new UpdateLlmProviderRequest(
                null, null, "gpt-4o", true));
        sync.persistUpdate("openai", new UpdateLlmProviderRequest(null, null, "gpt-4o", true), view);

        doThrow(new SQLException("persist failed")).when(ps).executeUpdate();
        assertThatThrownBy(() -> sync.persistUpdate(
                "openai", new UpdateLlmProviderRequest(null, "sk-live", null, true), view))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("保存模型配置到数据库失败");
    }

    @Test
    void keepsPersistenceDisabledWhenLoadFailsSoHydrateCanRetry() throws Exception {
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

        InMemoryLlmProviderStore store = TestBeanSupport.llmProviderStore();
        LlmProviderPersistence sync = new LlmProviderPersistence(reader, store, TestStorageSupport.storage());
        assertThat(sync.reloadFromStore()).isFalse();
        assertThat(sync.persistenceEnabled()).isFalse();
    }
}
