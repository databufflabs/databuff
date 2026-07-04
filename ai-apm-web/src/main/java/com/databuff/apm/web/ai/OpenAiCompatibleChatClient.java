package com.databuff.apm.web.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
public class OpenAiCompatibleChatClient {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public ChatResult chat(ResolvedLlmProvider provider, String userMessage) {
        if (provider == null || userMessage == null || userMessage.isBlank()) {
            return ChatResult.failed("provider or message is empty");
        }
        if (LlmApiTypes.isAnthropic(provider.apiType())) {
            return chatAnthropic(provider, userMessage);
        }
        return chatOpenAi(provider, userMessage);
    }

    private ChatResult chatOpenAi(ResolvedLlmProvider provider, String userMessage) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", provider.defaultModel(),
                    "messages", new Object[] {
                            Map.of("role", "user", "content", userMessage)
                    }));
            URI uri = URI.create(LlmChatModelFactory.buildOpenAiChatCompletionsUrl(provider.baseUrl()));
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (provider.apiKey() != null && !provider.apiKey().isBlank()) {
                builder.header("Authorization", "Bearer " + provider.apiKey().trim());
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return ChatResult.failed("HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                return ChatResult.failed("empty model response");
            }
            return ChatResult.ok(content.asText());
        } catch (Exception e) {
            return ChatResult.failed(e.getMessage() == null ? "chat failed" : e.getMessage());
        }
    }

    private ChatResult chatAnthropic(ResolvedLlmProvider provider, String userMessage) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", provider.defaultModel(),
                    "max_tokens", 4096,
                    "messages", new Object[] {
                            Map.of("role", "user", "content", userMessage)
                    }));
            URI uri = URI.create(LlmChatModelFactory.buildAnthropicMessagesUrl(provider.baseUrl()));
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            applyAnthropicAuth(builder, provider.apiKey());
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return ChatResult.failed("HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode contentBlocks = root.path("content");
            if (!contentBlocks.isArray() || contentBlocks.isEmpty()) {
                return ChatResult.failed("empty model response");
            }
            StringBuilder content = new StringBuilder();
            for (JsonNode block : contentBlocks) {
                if ("text".equals(block.path("type").asText())) {
                    content.append(block.path("text").asText());
                }
            }
            if (content.isEmpty()) {
                return ChatResult.failed("empty model response");
            }
            return ChatResult.ok(content.toString());
        } catch (Exception e) {
            return ChatResult.failed(e.getMessage() == null ? "chat failed" : e.getMessage());
        }
    }

    public record ResolvedLlmProvider(
            String providerCode,
            String baseUrl,
            String defaultModel,
            String apiKey,
            String apiType) {

        public ResolvedLlmProvider(String providerCode, String baseUrl, String defaultModel, String apiKey) {
            this(providerCode, baseUrl, defaultModel, apiKey, "openai-completions");
        }
    }

    static void applyAnthropicAuth(HttpRequest.Builder builder, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return;
        }
        String trimmed = apiKey.trim();
        builder.header("x-api-key", trimmed);
        builder.header("Authorization", "Bearer " + trimmed);
    }

    public record ChatResult(boolean ok, String content, String error) {
        public static ChatResult ok(String content) {
            return new ChatResult(true, content, null);
        }

        public static ChatResult failed(String error) {
            return new ChatResult(false, null, error);
        }
    }
}
