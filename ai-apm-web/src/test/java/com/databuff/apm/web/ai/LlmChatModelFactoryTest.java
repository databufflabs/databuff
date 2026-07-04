package com.databuff.apm.web.ai;

import io.agentscope.core.model.AnthropicChatModel;
import io.agentscope.core.model.OpenAIChatModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmChatModelFactoryTest {

    @Test
    void buildsOpenAiModelByDefault() {
        var provider = new OpenAiCompatibleChatClient.ResolvedLlmProvider(
                "openai", "https://api.openai.com/v1", "gpt-4o-mini", "sk-test", LlmApiTypes.OPENAI_COMPLETIONS);
        assertThat(LlmChatModelFactory.build(provider, "gpt-4o-mini", false))
                .isInstanceOf(OpenAIChatModel.class);
    }

    @Test
    void buildsAnthropicModelForAnthropicApiType() {
        var provider = new OpenAiCompatibleChatClient.ResolvedLlmProvider(
                "minimax",
                "https://api.minimaxi.com/anthropic",
                "MiniMax-M3",
                "sk-test",
                LlmApiTypes.ANTHROPIC_MESSAGES);
        assertThat(LlmChatModelFactory.build(provider, "MiniMax-M3", false))
                .isInstanceOf(AnthropicChatModel.class);
    }

    @Test
    void buildsAnthropicMessagesUrlForMiniMaxBaseUrl() {
        assertThat(LlmChatModelFactory.buildAnthropicMessagesUrl("https://api.minimaxi.com/anthropic"))
                .isEqualTo("https://api.minimaxi.com/anthropic/v1/messages");
    }

    @Test
    void buildsAnthropicMessagesUrlWhenBaseUrlAlreadyEndsWithV1() {
        assertThat(LlmChatModelFactory.buildAnthropicMessagesUrl("https://api.anthropic.com/v1"))
                .isEqualTo("https://api.anthropic.com/v1/messages");
    }

    @Test
    void buildsAnthropicMessagesUrlForCommonProviders() {
        assertThat(LlmChatModelFactory.buildAnthropicMessagesUrl("https://api.deepseek.com/anthropic"))
                .isEqualTo("https://api.deepseek.com/anthropic/v1/messages");
        assertThat(LlmChatModelFactory.buildAnthropicMessagesUrl("https://api.moonshot.cn/anthropic"))
                .isEqualTo("https://api.moonshot.cn/anthropic/v1/messages");
        assertThat(LlmChatModelFactory.buildAnthropicMessagesUrl("https://open.bigmodel.cn/api/anthropic"))
                .isEqualTo("https://open.bigmodel.cn/api/anthropic/v1/messages");
        assertThat(LlmChatModelFactory.buildAnthropicMessagesUrl("https://dashscope.aliyuncs.com/apps/anthropic"))
                .isEqualTo("https://dashscope.aliyuncs.com/apps/anthropic/v1/messages");
    }

    @Test
    void buildsOpenAiChatCompletionsUrlForVersionedBaseUrl() {
        assertThat(LlmChatModelFactory.buildOpenAiChatCompletionsUrl("https://api.openai.com/v1"))
                .isEqualTo("https://api.openai.com/v1/chat/completions");
        assertThat(LlmChatModelFactory.buildOpenAiChatCompletionsUrl("https://open.bigmodel.cn/api/paas/v4"))
                .isEqualTo("https://open.bigmodel.cn/api/paas/v4/chat/completions");
        assertThat(LlmChatModelFactory.buildOpenAiChatCompletionsUrl("https://api.deepseek.com"))
                .isEqualTo("https://api.deepseek.com/v1/chat/completions");
    }

    @Test
    void buildsOpenAiModelsUrlForVersionedBaseUrl() {
        assertThat(LlmChatModelFactory.buildOpenAiModelsUrl("https://api.openai.com/v1"))
                .isEqualTo("https://api.openai.com/v1/models");
        assertThat(LlmChatModelFactory.buildOpenAiModelsUrl("https://dashscope.aliyuncs.com/compatible-mode/v1"))
                .isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1/models");
    }
}
