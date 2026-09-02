package com.databuff.apm.web.ai;

import com.databuff.apm.common.storage.ApmConfigRepository;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryLlmProviderStoreTest {

    @Test
    void seedsProvidersAndTracksApiKey() {
        InMemoryLlmProviderStore store = TestBeanSupport.llmProviderStore();
        assertThat(store.listProviders()).hasSizeGreaterThanOrEqualTo(8);
        LlmProviderView updated = store.updateProvider("openai", new UpdateLlmProviderRequest(
                null, "sk-test", null, true));
        assertThat(updated.configured()).isTrue();
        assertThat(updated.enabled()).isTrue();
        assertThat(store.hasEnabledProvider()).isTrue();
    }

    @Test
    void rejectsUnknownProvider() {
        InMemoryLlmProviderStore store = TestBeanSupport.llmProviderStore();
        assertThatThrownBy(() -> store.updateProvider("unknown", new UpdateLlmProviderRequest(
                null, null, null, true)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesTestRequest() {
        InMemoryLlmProviderStore store = TestBeanSupport.llmProviderStore();
        assertThat(store.testConnection(new TestLlmProviderRequest("", null)).ok()).isFalse();
    }

    @Test
    void testsConnectionAgainstHttpServer() throws Exception {
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 0);
        byte[] body = """
                {"id":"t","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"pong"},"finish_reason":"stop"}]}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            InMemoryLlmProviderStore store = TestBeanSupport.llmProviderStore();
            int port = server.getAddress().getPort();
            assertThat(store.testConnection(new TestLlmProviderRequest(
                    "http://127.0.0.1:" + port + "/v1",
                    "key",
                    "openai-completions",
                    "test-model",
                    null)).ok()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testsConnectionFailsWhenFullChatCompletionsPathUsedAsBaseUrl() throws Exception {
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 0);
        byte[] body = """
                {"id":"t","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"pong"},"finish_reason":"stop"}]}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("/api/paas/v4/chat/completions".equals(path)) {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        });
        server.start();
        try {
            InMemoryLlmProviderStore store = TestBeanSupport.llmProviderStore();
            int port = server.getAddress().getPort();
            // Correct Base URL (matches AgentScope SDK join) succeeds.
            assertThat(store.testConnection(new TestLlmProviderRequest(
                    "http://127.0.0.1:" + port + "/api/paas/v4",
                    "key",
                    "openai-completions",
                    "GLM-5",
                    null)).ok()).isTrue();
            // Pasting the full endpoint as Base URL must fail connectivity the same way runtime would.
            assertThat(store.testConnection(new TestLlmProviderRequest(
                    "http://127.0.0.1:" + port + "/api/paas/v4/chat/completions",
                    "key",
                    "openai-completions",
                    "GLM-5",
                    null)).ok()).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testsAnthropicConnectionAgainstHttpServer() throws Exception {
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 0);
        byte[] body = """
                {"id":"t","type":"message","role":"assistant","content":[{"type":"text","text":"pong"}],"model":"MiniMax-M3","stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        server.createContext("/anthropic/v1/messages", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            InMemoryLlmProviderStore store = TestBeanSupport.llmProviderStore();
            int port = server.getAddress().getPort();
            assertThat(store.testConnection(new TestLlmProviderRequest(
                    "http://127.0.0.1:" + port + "/anthropic",
                    "key",
                    LlmApiTypes.ANTHROPIC_MESSAGES,
                    "MiniMax-M3",
                    null)).ok()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void requiresModelIdForConnectivityTest() {
        InMemoryLlmProviderStore store = TestBeanSupport.llmProviderStore();
        TestLlmProviderResult result = store.testConnection(new TestLlmProviderRequest(
                "https://api.example.com/v1",
                "key"));
        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("模型 ID");
    }

    @Test
    void hydratesFromDorisRows() {
        InMemoryLlmProviderStore store = TestBeanSupport.llmProviderStore();
        store.applyPersistedRows(List.of(new ApmConfigRepository.LlmProviderRow(
                "openai", "OpenAI", "https://api.openai.com/v1", true, "c2stZXk=", "gpt-4o")));
        LlmProviderView view = store.listProviders().stream()
                .filter(item -> "openai".equals(item.providerCode()))
                .findFirst()
                .orElseThrow();
        assertThat(view.enabled()).isTrue();
        assertThat(view.configured()).isTrue();
        assertThat(store.apiKeyCipher("openai")).isNotBlank();
        assertThat(store.apiKeyCipher("missing")).isNull();
    }

    @Test
    void unchangedDorisReapplyDoesNotBumpProviderVersion() {
        InMemoryLlmProviderStore store = TestBeanSupport.llmProviderStore();
        ApmConfigRepository.LlmProviderRow row = new ApmConfigRepository.LlmProviderRow(
                "openai", "OpenAI", "https://api.openai.com/v1", true, "c2stZXk=", "gpt-4o");
        store.applyPersistedRows(List.of(row));
        long versionAfterFirst = store.providerVersion("openai");

        store.applyPersistedRows(List.of(row));

        assertThat(store.providerVersion("openai")).isEqualTo(versionAfterFirst);
    }

    @Test
    void updatesBaseUrlAndDefaultModel() {
        InMemoryLlmProviderStore store = TestBeanSupport.llmProviderStore();
        LlmProviderView updated = store.updateProvider("ollama", new UpdateLlmProviderRequest(
                "http://localhost:11434/v1", null, "mistral", null));
        assertThat(updated.baseUrl()).contains("11434");
        assertThat(updated.defaultModel()).isEqualTo("mistral");
        assertThat(updated.defaultProvider()).isFalse();
    }

    @Test
    void autoSetsDefaultWhenFirstProviderConfigured() {
        InMemoryLlmProviderStore store = TestBeanSupport.llmProviderStore();
        LlmProviderView updated = store.updateProvider("deepseek", new UpdateLlmProviderRequest(
                null, "sk-test", null, null));
        assertThat(updated.enabled()).isTrue();
        assertThat(updated.configured()).isTrue();
        assertThat(updated.defaultProvider()).isTrue();
        assertThat(store.firstEnabledProvider()).isPresent();
    }

    @Test
    void createsCustomProviderAndSetsDefault() {
        InMemoryLlmProviderStore store = TestBeanSupport.llmProviderStore();
        LlmProviderView created = store.createProvider(new CreateLlmProviderRequest(
                "my-llm", "My LLM", "https://api.example.com/v1", "default", "sk-test", true));
        assertThat(created.configured()).isTrue();
        assertThat(created.enabled()).isTrue();

        LlmProviderView defaulted = store.setDefaultProvider("my-llm");
        assertThat(defaulted.defaultProvider()).isTrue();
        assertThat(store.firstEnabledProvider()).isPresent();
        assertThat(store.firstEnabledProvider().orElseThrow().providerCode()).isEqualTo("my-llm");
    }

    @Test
    void supportsKeylessCustomProvider() {
        InMemoryLlmProviderStore store = TestBeanSupport.llmProviderStore();
        LlmProviderView created = store.createProvider(new CreateLlmProviderRequest(
                "local-llm", "Local LLM", "http://127.0.0.1:11434/v1", "llama3", null, true));

        assertThat(created.configured()).isTrue();
        assertThat(store.hasEnabledProvider()).isTrue();
        assertThat(store.resolveProvider("local-llm")).get()
                .extracting(OpenAiCompatibleChatClient.ResolvedLlmProvider::apiKey)
                .isNull();
    }

    @Test
    void rejectsDuplicateModelIdsAndInvalidTokenLimits() {
        InMemoryLlmProviderStore store = TestBeanSupport.llmProviderStore();

        assertThatThrownBy(() -> store.saveProviderDetail(new SaveLlmProviderRequest(
                "openai", null, null, null, null, null, null, "same", List.of(
                new LlmModelView("same", "One", null, null, List.of(), true),
                new LlmModelView("same", "Two", null, null, List.of(), false)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能重复");

        assertThatThrownBy(() -> store.saveProviderDetail(new SaveLlmProviderRequest(
                "openai", null, null, null, null, null, null, "bad", List.of(
                new LlmModelView("bad", "Bad", -1, 0, List.of(), true)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("正整数");
    }

    @Test
    void deletesCustomProviderAndFallsBackFromDefault() {
        InMemoryLlmProviderStore store = TestBeanSupport.llmProviderStore();
        store.updateProvider("openai", new UpdateLlmProviderRequest(
                null, "sk-openai", null, true));
        LlmProviderView created = store.createProvider(new CreateLlmProviderRequest(
                "my-llm", "My LLM", "https://api.example.com/v1", "default", "sk-test", true));
        assertThat(created.builtIn()).isFalse();
        store.setDefaultProvider("my-llm");

        store.deleteProvider("my-llm");

        assertThat(store.listProviders()).noneMatch(provider -> "my-llm".equals(provider.providerCode()));
        assertThat(store.resolveApiKey("my-llm", null)).isNull();
        assertThat(store.firstEnabledProvider()).get().extracting(
                OpenAiCompatibleChatClient.ResolvedLlmProvider::providerCode).isEqualTo("openai");
    }

    @Test
    void rejectsDeletingBuiltInProvider() {
        InMemoryLlmProviderStore store = TestBeanSupport.llmProviderStore();
        assertThat(store.getProviderDetail("openai").builtIn()).isTrue();

        assertThatThrownBy(() -> store.deleteProvider("openai"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内置");
    }

    @Test
    void rejectsDuplicateProvider() {
        InMemoryLlmProviderStore store = TestBeanSupport.llmProviderStore();
        assertThatThrownBy(() -> store.createProvider(new CreateLlmProviderRequest(
                "openai", "Dup", "https://api.example.com/v1", "m", null, false)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void omitsApiKeyFromDetailViewWhenMaskEnabled() {
        InMemoryLlmProviderStore store = TestBeanSupport.llmProviderStore();
        store.updateProvider("openai", new UpdateLlmProviderRequest(null, "sk-test-secret-key", null, null));
        var detail = store.getProviderDetail("openai");
        assertThat(detail.configured()).isTrue();
        assertThat(detail.apiKey()).isNull();
        assertThat(detail.apiKeyMasked()).isTrue();
    }

    @Test
    void returnsApiKeyInDetailViewWhenMaskDisabled() {
        InMemoryLlmProviderStore store = TestBeanSupport.llmProviderStore(TestBeanSupport.unmaskedProviderProperties());
        store.updateProvider("openai", new UpdateLlmProviderRequest(null, "sk-test-secret-key", null, null));
        var detail = store.getProviderDetail("openai");
        assertThat(detail.configured()).isTrue();
        assertThat(detail.apiKey()).isEqualTo("sk-test-secret-key");
        assertThat(detail.apiKeyMasked()).isFalse();
    }

    @Test
    void resolvesStoredApiKeyForTestWhenRequestOmitsKey() {
        InMemoryLlmProviderStore store = TestBeanSupport.llmProviderStore();
        store.updateProvider("openai", new UpdateLlmProviderRequest(null, "stored-key", null, null));
        assertThat(store.resolveApiKey("openai", null)).isEqualTo("stored-key");
        assertThat(store.resolveApiKey("openai", "override")).isEqualTo("override");
    }
}
