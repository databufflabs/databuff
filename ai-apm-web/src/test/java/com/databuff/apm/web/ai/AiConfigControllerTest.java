package com.databuff.apm.web.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiConfigControllerTest {

    @Test
    void delegatesToService() {
        AiConfigController controller = new AiConfigController(TestAiSupport.configService());
        assertThat(controller.listProviders()).isNotEmpty();
        assertThat(controller.status()).containsEntry("ready", false);
        assertThat(controller.status()).containsEntry("maskProviderApiKey", true);
        assertThat(controller.updateProvider("openai", new UpdateLlmProviderRequest(
                null, "sk-test", null, true)).enabled()).isTrue();
        assertThat(controller.testProvider(new TestLlmProviderRequest(
                "http://127.0.0.1:1/v1", null, "openai-completions", "test-model", null)).ok())
                .isFalse();
    }
}
