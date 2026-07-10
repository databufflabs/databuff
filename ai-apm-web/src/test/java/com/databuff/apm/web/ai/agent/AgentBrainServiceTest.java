package com.databuff.apm.web.ai.agent;

import com.databuff.apm.web.ai.TestAiSupport;
import com.databuff.apm.web.ai.UpdateLlmProviderRequest;
import com.databuff.apm.web.ai.tool.ApmToolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentBrainServiceTest {

    private ApmToolkit apmToolkit;
    private TestAiSupport.AiFixture aiFixture;
    private AgentBrainService service;

    @BeforeEach
    void setUp() {
        apmToolkit = mock(ApmToolkit.class);
        aiFixture = TestAiSupport.aiFixture();
        service = aiFixture.agentBrain(apmToolkit, new AiSessionStore());
    }

    @Test
    void answersErrorRateQuestion() {
        when(apmToolkit.listServiceHealth(anyLong())).thenReturn(java.util.List.of(
                new ApmToolkit.ServiceHealthSummary("demo-order", 10, 2, 0.2)));
        AgentBrainService.ChatResponse response = service.chat(
                new AgentBrainService.ChatRequest(null, "最近错误率多少？"));
        assertThat(response.sessionId()).isNotBlank();
        assertThat(response.reply()).contains("demo-order");
        assertThat(response.llmReady()).isFalse();
    }

    @Test
    void answersTraceQuestion() {
        when(apmToolkit.countRecentSpans(anyLong())).thenReturn(42);
        AgentBrainService.ChatResponse response = service.chat(
                new AgentBrainService.ChatRequest(null, "最近 trace 有多少"));
        assertThat(response.reply()).contains("42");
    }

    @Test
    void keepsSessionHistory() {
        when(apmToolkit.countRecentSpans(anyLong())).thenReturn(1);
        AgentBrainService.ChatResponse first = service.chat(
                new AgentBrainService.ChatRequest(null, "trace"));
        service.chat(new AgentBrainService.ChatRequest(first.sessionId(), "help"));
        assertThat(service.countSessions()).isEqualTo(1);
        assertThat(service.sessionMessages(first.sessionId())).hasSize(4);
    }

    @Test
    void reportsEmptyMetrics() {
        when(apmToolkit.listServiceHealth(anyLong())).thenReturn(java.util.List.of());
        AgentBrainService.ChatResponse response = service.chat(
                new AgentBrainService.ChatRequest(null, "错误率"));
        assertThat(response.reply()).contains("暂无服务指标数据");
    }

    @Test
    void brainChatUsesExpertRuntimeWhenProviderConfigured() throws Exception {
        TestAiSupport.AiFixture aiFixture = TestAiSupport.aiFixture();
        aiFixture.agentRuntimeConfig().setAgentscopeEnabled(false);
        aiFixture.store().updateProvider("openai", new UpdateLlmProviderRequest(
                null, "sk-test", null, true));
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = ("{\"choices\":[{\"message\":{\"content\":\"brain-runtime-ok\"}}]}").getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            aiFixture.store().updateProvider("openai", new UpdateLlmProviderRequest(
                    "http://127.0.0.1:" + port + "/v1", "sk-test", null, true));
            AgentBrainService service = aiFixture.agentBrain(mock(ApmToolkit.class), new AiSessionStore());
            AgentBrainService.ChatResponse response = service.chat(
                    new AgentBrainService.ChatRequest(null, "请用一句话介绍你自己"));
            assertThat(response.llmReady()).isTrue();
            assertThat(response.reply()).contains("brain-runtime-ok");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usesAgentscopeWhenEnabled() throws Exception {
        aiFixture.agentRuntimeConfig().setAgentscopeEnabled(true);
        aiFixture.store().updateProvider("openai", new UpdateLlmProviderRequest(
                null, "sk-test", null, true));
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = ("{\"choices\":[{\"message\":{\"content\":\"AgentScope path\"}}]}").getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            aiFixture.store().updateProvider("openai", new UpdateLlmProviderRequest(
                    "http://127.0.0.1:" + port + "/v1", "sk-test", null, true));
            AgentBrainService readyService = aiFixture.agentBrain(apmToolkit, new AiSessionStore());
            AgentBrainService.ChatResponse response = readyService.chat(
                    new AgentBrainService.ChatRequest(null, "请总结当前系统状态"));
            assertThat(response.llmReady()).isTrue();
            assertThat(response.reply()).isNotBlank();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void agentscopeRuntimeUsesLlmWhenEnabled() throws Exception {
        aiFixture.agentRuntimeConfig().setAgentscopeEnabled(true);
        aiFixture.store().updateProvider("openai", new UpdateLlmProviderRequest(
                null, "sk-test", null, true));
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = ("{\"choices\":[{\"message\":{\"content\":\"scoped-ok\"}}]}").getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            aiFixture.store().updateProvider("openai", new UpdateLlmProviderRequest(
                    "http://127.0.0.1:" + port + "/v1", "sk-test", null, true));
            AgentBrainService readyService = aiFixture.agentBrain(apmToolkit, new AiSessionStore());
            AgentBrainService.ChatResponse response = readyService.chat(
                    new AgentBrainService.ChatRequest(null, "分析 latency"));
            assertThat(response.reply()).isEqualTo("scoped-ok");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void agentscopeFailureFallsBackToHttpLlm() throws Exception {
        aiFixture.agentRuntimeConfig().setAgentscopeEnabled(true);
        aiFixture.store().updateProvider("openai", new UpdateLlmProviderRequest(
                null, "sk-test", null, true));
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = ("{\"choices\":[{\"message\":{\"content\":\"fallback ok\"}}]}").getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            aiFixture.store().updateProvider("openai", new UpdateLlmProviderRequest(
                    "http://127.0.0.1:" + port + "/v1", "sk-test", null, true));
            AgentBrainService readyService = aiFixture.agentBrain(apmToolkit, new AiSessionStore());
            AgentBrainService.ChatResponse response = readyService.chat(
                    new AgentBrainService.ChatRequest(null, "分析 latency"));
            assertThat(response.reply()).isEqualTo("fallback ok");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void agentscopeAndHttpBothFail() {
        aiFixture.agentRuntimeConfig().setAgentscopeEnabled(true);
        aiFixture.store().updateProvider("openai", new UpdateLlmProviderRequest(
                "http://127.0.0.1:1/v1", "sk-test", null, true));
        AgentBrainService readyService = aiFixture.agentBrain(apmToolkit, new AiSessionStore());
        AgentBrainService.ChatResponse response = readyService.chat(
                new AgentBrainService.ChatRequest(null, "分析 latency"));
        assertThat(response.reply()).satisfiesAnyOf(
                text -> assertThat(text).contains("LLM 调用失败"),
                text -> assertThat(text).contains("Retries exhausted"),
                text -> assertThat(text).contains("对话失败"));
    }

    @Test
    void usesLlmReadyFallback() throws Exception {
        aiFixture.store().updateProvider("openai", new UpdateLlmProviderRequest(
                null, "sk-test", null, true));
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = ("{\"choices\":[{\"message\":{\"content\":\"LLM answer\"}}]}").getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            aiFixture.store().updateProvider("openai", new UpdateLlmProviderRequest(
                    "http://127.0.0.1:" + port + "/v1", "sk-test", null, true));
            AgentBrainService readyService = aiFixture.agentBrain(apmToolkit, new AiSessionStore());
            AgentBrainService.ChatResponse response = readyService.chat(
                    new AgentBrainService.ChatRequest(null, "为什么 latency 升高？"));
            assertThat(response.llmReady()).isTrue();
            assertThat(response.reply()).contains("LLM answer");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void promptsConfigureLlmForGenericQuestion() {
        AgentBrainService.ChatResponse response = service.chat(
                new AgentBrainService.ChatRequest(null, "帮我分析一下 latency"));
        assertThat(response.reply()).contains("AI 大模型尚未配置");
    }

    @Test
    void reportsLlmFailureWhenProviderConfigured() {
        aiFixture.store().updateProvider("openai", new UpdateLlmProviderRequest(
                "http://127.0.0.1:1/v1", "sk-test", null, true));
        AgentBrainService readyService = aiFixture.agentBrain(apmToolkit, new AiSessionStore());
        AgentBrainService.ChatResponse response = readyService.chat(
                new AgentBrainService.ChatRequest(null, "请总结当前系统状态"));
        assertThat(response.llmReady()).isTrue();
        assertThat(response.reply()).satisfiesAnyOf(
                text -> assertThat(text).contains("LLM 调用失败"),
                text -> assertThat(text).contains("Retries exhausted"),
                text -> assertThat(text).contains("对话失败"));
    }

    @Test
    void answersCapabilityQuestion() {
        AgentBrainService.ChatResponse response = service.chat(
                new AgentBrainService.ChatRequest(null, "能做什么"));
        assertThat(response.reply()).contains("DataBuff APM 助手");
    }
}
