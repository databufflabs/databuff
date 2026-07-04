package com.databuff.apm.web.ai;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleChatClientTest {

    @Test
    void parsesChatCompletion() throws Exception {
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = ("{\"choices\":[{\"message\":{\"content\":\"hello from model\"}}]}").getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            OpenAiCompatibleChatClient client = new OpenAiCompatibleChatClient();
            OpenAiCompatibleChatClient.ChatResult result = client.chat(
                    new OpenAiCompatibleChatClient.ResolvedLlmProvider(
                            "openai", "http://127.0.0.1:" + port + "/v1", "gpt-4o-mini", "sk"),
                    "hi");
            assertThat(result.ok()).isTrue();
            assertThat(result.content()).contains("hello from model");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void parsesAnthropicMessage() throws Exception {
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/anthropic/v1/messages", exchange -> {
            byte[] body = ("{\"content\":[{\"type\":\"text\",\"text\":\"hello from minimax\"}]}").getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            OpenAiCompatibleChatClient client = new OpenAiCompatibleChatClient();
            OpenAiCompatibleChatClient.ChatResult result = client.chat(
                    new OpenAiCompatibleChatClient.ResolvedLlmProvider(
                            "minimax",
                            "http://127.0.0.1:" + port + "/anthropic",
                            "MiniMax-M3",
                            "sk",
                            LlmApiTypes.ANTHROPIC_MESSAGES),
                    "hi");
            assertThat(result.ok()).isTrue();
            assertThat(result.content()).contains("hello from minimax");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsEmptyMessage() {
        OpenAiCompatibleChatClient client = new OpenAiCompatibleChatClient();
        assertThat(client.chat(null, " ").ok()).isFalse();
    }

    @Test
    void handlesHttpErrorAndEmptyBody() throws Exception {
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"\"}}]}".getBytes();
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            OpenAiCompatibleChatClient client = new OpenAiCompatibleChatClient();
            OpenAiCompatibleChatClient.ResolvedLlmProvider provider =
                    new OpenAiCompatibleChatClient.ResolvedLlmProvider(
                            "openai", "http://127.0.0.1:" + port + "/v1/", "gpt-4o-mini", " sk ");
            assertThat(client.chat(provider, "hello").ok()).isFalse();
        } finally {
            server.stop(0);
        }
    }
}
