package com.databuff.apm.web.ai;

import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
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
    void probeUsesSameAgentScopeModelStackAsBuild() throws Exception {
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
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
            int port = server.getAddress().getPort();
            var provider = new OpenAiCompatibleChatClient.ResolvedLlmProvider(
                    "openai",
                    "http://127.0.0.1:" + port + "/v1",
                    "gpt-test",
                    "sk-test",
                    LlmApiTypes.OPENAI_COMPLETIONS);
            LlmChatModelFactory.probe(provider, "gpt-test");
        } finally {
            server.stop(0);
        }
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
    void normalizeAnthropicSdkBaseUrlStripsTrailingV1ForKimiCoding() {
        assertThat(LlmChatModelFactory.normalizeAnthropicSdkBaseUrl("https://api.kimi.com/coding/v1"))
                .isEqualTo("https://api.kimi.com/coding");
        assertThat(LlmChatModelFactory.buildAnthropicMessagesUrl("https://api.kimi.com/coding/v1"))
                .isEqualTo("https://api.kimi.com/coding/v1/messages");
    }

    @Test
    void normalizeAnthropicSdkBaseUrlKeepsAnthropicStyleHosts() {
        assertThat(LlmChatModelFactory.normalizeAnthropicSdkBaseUrl("https://api.minimaxi.com/anthropic"))
                .isEqualTo("https://api.minimaxi.com/anthropic");
        assertThat(LlmChatModelFactory.normalizeAnthropicSdkBaseUrl("https://api.moonshot.cn/anthropic"))
                .isEqualTo("https://api.moonshot.cn/anthropic");
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
    void openAiFullEndpointAsBaseUrlDoublesPathLikeSdk() {
        // Connectivity must not special-case a pasted /chat/completions Base URL;
        // otherwise it passes while AgentScope still appends /v1/chat/completions.
        assertThat(LlmChatModelFactory.buildOpenAiChatCompletionsUrl(
                "https://open.bigmodel.cn/api/paas/v4/chat/completions"))
                .isEqualTo("https://open.bigmodel.cn/api/paas/v4/chat/completions/v1/chat/completions");
        assertThat(LlmChatModelFactory.buildOpenAiChatCompletionsUrl(
                "https://api.openai.com/v1/chat/completions"))
                .isEqualTo("https://api.openai.com/v1/chat/completions/v1/chat/completions");
    }

    @Test
    void anthropicFullEndpointAsBaseUrlDoublesPathLikeSdk() {
        assertThat(LlmChatModelFactory.buildAnthropicMessagesUrl(
                "https://api.minimaxi.com/anthropic/v1/messages"))
                .isEqualTo("https://api.minimaxi.com/anthropic/v1/messages/v1/messages");
        assertThat(LlmChatModelFactory.normalizeAnthropicSdkBaseUrl(
                "https://api.minimaxi.com/anthropic/v1/messages"))
                .isEqualTo("https://api.minimaxi.com/anthropic/v1/messages");
    }

    @Test
    void buildsOpenAiModelsUrlForVersionedBaseUrl() {
        assertThat(LlmChatModelFactory.buildOpenAiModelsUrl("https://api.openai.com/v1"))
                .isEqualTo("https://api.openai.com/v1/models");
        assertThat(LlmChatModelFactory.buildOpenAiModelsUrl("https://dashscope.aliyuncs.com/compatible-mode/v1"))
                .isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1/models");
    }
}
