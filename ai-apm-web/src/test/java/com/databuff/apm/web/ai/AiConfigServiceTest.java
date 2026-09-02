package com.databuff.apm.web.ai;

import com.databuff.apm.web.persistence.LlmProviderPersistence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class AiConfigServiceTest {

    @Test
    void exposesProviderListAndStatus() {
        AiConfigService service = TestAiSupport.configService();
        assertThat(service.listProviders()).isNotEmpty();
        assertThat(service.aiReady()).isFalse();
        service.updateProvider("deepseek", new UpdateLlmProviderRequest(null, "sk-test", null, true));
        assertThat(service.aiReady()).isTrue();
        assertThat(service.testProvider(new TestLlmProviderRequest("", null)).ok()).isFalse();
    }

    @Test
    void savesEnabledProviderWithoutApiKey() {
        AiConfigService service = TestAiSupport.configService();

        LlmProviderView saved = service.saveProviderDetail(new SaveLlmProviderRequest(
                "ollama",
                "Ollama",
                LlmApiTypes.OPENAI_COMPLETIONS,
                "http://127.0.0.1:11434/v1",
                null,
                true,
                null,
                "llama3",
                List.of(new LlmModelView("llama3", "Llama 3", null, null, List.of(), true))));

        assertThat(saved.configured()).isTrue();
        assertThat(service.aiReady()).isTrue();
        assertThat(service.getProviderDetail("ollama").apiKey()).isNull();
    }

    @Test
    void savesRemovalOfAConfiguredModel() {
        AiConfigService service = TestAiSupport.configService();
        service.createProvider(new CreateLlmProviderRequest(
                "local-models", "Local Models", "http://127.0.0.1:11434/v1", "model-a", null, true));
        service.saveProviderDetail(new SaveLlmProviderRequest(
                "local-models", "Local Models", LlmApiTypes.OPENAI_COMPLETIONS,
                "http://127.0.0.1:11434/v1", null, true, null, "model-a", List.of(
                new LlmModelView("model-a", "Model A", null, null, List.of(), true),
                new LlmModelView("model-b", "Model B", null, null, List.of(), false))));

        service.saveProviderDetail(new SaveLlmProviderRequest(
                "local-models", "Local Models", LlmApiTypes.OPENAI_COMPLETIONS,
                "http://127.0.0.1:11434/v1", null, true, null, "model-b", List.of(
                new LlmModelView("model-b", "Model B", null, null, List.of(), true))));

        assertThat(service.getProviderDetail("local-models").models())
                .extracting(LlmModelView::modelId)
                .containsExactly("model-b");
    }

    @Test
    void restoresProviderWhenSavingToDatabaseFails() {
        InMemoryLlmProviderStore store = TestBeanSupport.llmProviderStore();
        LlmProviderPersistence persistence = mock(LlmProviderPersistence.class);
        AiConfigService service = new AiConfigService(
                store,
                new LlmCatalogService(),
                persistence,
                TestBeanSupport.defaultProviderProperties());
        LlmProviderDetailView before = store.getProviderDetail("openai");
        long versionBefore = store.providerVersion("openai");
        doThrow(new IllegalStateException("database unavailable"))
                .when(persistence).persistDetail(any(), any());

        assertThatThrownBy(() -> service.saveProviderDetail(new SaveLlmProviderRequest(
                "openai",
                "Changed name",
                LlmApiTypes.OPENAI_COMPLETIONS,
                "https://changed.example.com/v1",
                null,
                true,
                null,
                "changed-model",
                List.of(new LlmModelView(
                        "changed-model", "Changed", null, null, List.of(), true)))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(store.getProviderDetail("openai")).isEqualTo(before);
        assertThat(store.providerVersion("openai")).isEqualTo(versionBefore);
    }

    @Test
    void removesNewProviderFromMemoryWhenDatabaseCreateFails() {
        InMemoryLlmProviderStore store = TestBeanSupport.llmProviderStore();
        LlmProviderPersistence persistence = mock(LlmProviderPersistence.class);
        AiConfigService service = new AiConfigService(
                store,
                new LlmCatalogService(),
                persistence,
                TestBeanSupport.defaultProviderProperties());
        doThrow(new IllegalStateException("database unavailable"))
                .when(persistence).persistCreate(any(), any());

        assertThatThrownBy(() -> service.createProvider(new CreateLlmProviderRequest(
                "failed-create", "Failed", "https://example.com/v1", "model", null, true)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(store.listProviders())
                .noneMatch(provider -> "failed-create".equals(provider.providerCode()));
    }
}
