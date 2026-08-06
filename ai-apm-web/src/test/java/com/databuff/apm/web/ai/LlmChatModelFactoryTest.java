package com.databuff.apm.web.ai;

import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmChatModelFactoryTest {

    @Test
    void resolveMaxOutputTokensNullWhenUnconfigured() {
        assertThat(LlmChatModelFactory.resolveMaxOutputTokens(null)).isNull();
        assertThat(LlmChatModelFactory.resolveMaxOutputTokens(0)).isNull();
        assertThat(LlmChatModelFactory.resolveMaxOutputTokens(-1)).isNull();
        assertThat(LlmChatModelFactory.resolveMaxOutputTokens(65536)).isEqualTo(65536);
        assertThat(LlmChatModelFactory.generateOptions(null).getMaxTokens()).isNull();
        assertThat(LlmChatModelFactory.generateOptions(8192).getMaxTokens()).isEqualTo(8192);
    }

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
    void probeDoesNotSendH2cUpgradeWhenForceHttp11Enabled() throws Exception {
        // Opt-in apm.agent.llm-force-http11: local http:// servers (uvicorn etc.) reject h2c Upgrade.
        boolean previous = LlmChatModelFactory.isForceHttp11();
        LlmChatModelFactory.configureForceHttp11(true);
        java.net.ServerSocket ss = new java.net.ServerSocket();
        ss.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
        int port = ss.getLocalPort();
        java.util.concurrent.ExecutorService es = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<String> raw = es.submit(() -> {
            try (java.net.Socket s = ss.accept()) {
                s.setSoTimeout(5000);
                java.io.InputStream in = s.getInputStream();
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] tmp = new byte[4096];
                long deadline = System.currentTimeMillis() + 3000;
                while (System.currentTimeMillis() < deadline) {
                    if (in.available() > 0) {
                        int n = in.read(tmp);
                        if (n < 0) {
                            break;
                        }
                        buf.write(tmp, 0, n);
                        String soFar = buf.toString(java.nio.charset.StandardCharsets.ISO_8859_1);
                        if (soFar.contains("\r\n\r\n")) {
                            Thread.sleep(50);
                            while (in.available() > 0) {
                                n = in.read(tmp);
                                if (n < 0) {
                                    break;
                                }
                                buf.write(tmp, 0, n);
                            }
                            break;
                        }
                    } else {
                        Thread.sleep(10);
                    }
                }
                byte[] respBody = """
                        {"id":"t","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"pong"},"finish_reason":"stop"}]}
                        """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] hdr = ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                        + respBody.length + "\r\nConnection: close\r\n\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                s.getOutputStream().write(hdr);
                s.getOutputStream().write(respBody);
                s.getOutputStream().flush();
                return buf.toString(java.nio.charset.StandardCharsets.ISO_8859_1);
            }
        });
        try {
            var provider = new OpenAiCompatibleChatClient.ResolvedLlmProvider(
                    "local",
                    "http://127.0.0.1:" + port + "/v1",
                    "deepseek-v4-flash",
                    "sk-test",
                    LlmApiTypes.OPENAI_COMPLETIONS);
            LlmChatModelFactory.probe(provider, "deepseek-v4-flash");
            String request = raw.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(request).contains("POST /v1/chat/completions HTTP/1.1");
            assertThat(request).doesNotContainIgnoringCase("Upgrade: h2c");
            assertThat(request).doesNotContainIgnoringCase("HTTP2-Settings");
        } finally {
            LlmChatModelFactory.configureForceHttp11(previous);
            ss.close();
            es.shutdownNow();
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
