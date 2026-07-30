package com.databuff.apm.web.ai;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

public final class LlmChatModelFactory {

    private static final Pattern VERSION_IN_PATH = Pattern.compile(".*/v\\d+$");
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);

    private LlmChatModelFactory() {
    }

    public static Model build(
            OpenAiCompatibleChatClient.ResolvedLlmProvider provider,
            String modelName,
            boolean stream) {
        String resolvedModel = modelName == null || modelName.isBlank()
                ? provider.defaultModel()
                : modelName;
        String apiKey = provider.apiKey() == null ? "" : provider.apiKey();
        String baseUrl = normalizeBaseUrl(provider.baseUrl());
        if (LlmApiTypes.isAnthropic(provider.apiType())) {
            // Anthropic Java SDK always appends /v1/messages to baseUrl.
            return AnthropicChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(resolvedModel)
                    .baseUrl(normalizeAnthropicSdkBaseUrl(baseUrl))
                    .stream(stream)
                    .build();
        }
        // OpenAI Java SDK always appends /v1/chat/completions (with /v1 stripped when base ends in /vN).
        return OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(resolvedModel)
                .baseUrl(baseUrl)
                .stream(stream)
                .build();
    }

    /**
     * Connectivity probe through the same AgentScope {@link Model} stack used by expert Q&A.
     * Throws on failure so callers can surface the SDK/HTTP error message.
     */
    public static void probe(OpenAiCompatibleChatClient.ResolvedLlmProvider provider, String modelName) {
        if (provider == null) {
            throw new IllegalArgumentException("provider is required");
        }
        Model model = build(provider, modelName, false);
        GenerateOptions options = GenerateOptions.builder()
                .maxTokens(8)
                .executionConfig(ExecutionConfig.builder()
                        .timeout(PROBE_TIMEOUT)
                        .maxAttempts(1)
                        .build())
                .build();
        Msg ping = Msg.builder()
                .role(MsgRole.USER)
                .textContent("ping")
                .build();
        model.stream(List.of(ping), List.of(), options).blockLast(PROBE_TIMEOUT.plusSeconds(5));
    }

    public static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://api.openai.com/v1";
        }
        return baseUrl.trim().replaceAll("/$", "");
    }

    /**
     * Final Anthropic Messages URL used by connectivity tests and HTTP clients.
     * Must match AgentScope Anthropic SDK: {@link #normalizeAnthropicSdkBaseUrl} + {@code /v1/messages}.
     * Does not rewrite a full endpoint pasted as Base URL — wrong input fails connectivity and runtime alike.
     */
    public static String buildAnthropicMessagesUrl(String baseUrl) {
        return normalizeAnthropicSdkBaseUrl(baseUrl) + "/v1/messages";
    }

    /**
     * Base URL for AgentScope's Anthropic SDK client. The SDK always adds {@code /v1/messages},
     * so a correct provider base ending in {@code /v1} (e.g. Kimi coding API) must be stripped
     * to avoid {@code /v1/v1/messages}. This is SDK contract handling, not user-input correction.
     */
    public static String normalizeAnthropicSdkBaseUrl(String baseUrl) {
        String normalized = normalizeBaseUrl(baseUrl);
        if (normalized.endsWith("/v1")) {
            return normalized.substring(0, normalized.length() - 3);
        }
        return normalized;
    }

    /**
     * Final OpenAI Chat Completions URL used by connectivity tests and HTTP clients.
     * Must match AgentScope OpenAI SDK path joining (no special-case for a full endpoint as Base URL).
     */
    public static String buildOpenAiChatCompletionsUrl(String baseUrl) {
        return buildVersionedEndpoint(baseUrl, "/v1/chat/completions");
    }

    public static String buildOpenAiModelsUrl(String baseUrl) {
        return buildVersionedEndpoint(baseUrl, "/v1/models");
    }

    /**
     * Mirrors AgentScope OpenAIClient.buildApiUrl: if base path already ends with {@code /vN},
     * strip the {@code /v1} prefix from the endpoint before joining. No early-return when the
     * base already looks like a full {@code /chat/completions} URL — that would make connectivity
     * pass while the SDK still doubles the path at runtime.
     */
    private static String buildVersionedEndpoint(String baseUrl, String defaultEndpoint) {
        String normalized = normalizeBaseUrl(baseUrl);
        String endpoint = defaultEndpoint;
        try {
            URI uri = URI.create(normalized);
            String path = uri.getPath();
            if (path != null && !path.isBlank() && !"/".equals(path)) {
                String trimmedPath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
                if (VERSION_IN_PATH.matcher(trimmedPath).matches()) {
                    if (endpoint.startsWith("/v1/")) {
                        endpoint = endpoint.substring(3);
                    } else if ("/v1".equals(endpoint)) {
                        endpoint = "";
                    }
                }
                String joinedPath = joinPaths(trimmedPath, endpoint);
                URI rebuilt = new URI(uri.getScheme(), uri.getAuthority(), joinedPath, uri.getQuery(), uri.getFragment());
                return rebuilt.toString();
            }
        } catch (Exception ignored) {
            // fall through to simple concatenation
        }
        return normalized + endpoint;
    }

    private static String joinPaths(String basePath, String endpoint) {
        if (basePath == null || basePath.isBlank() || "/".equals(basePath)) {
            return endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        }
        if (endpoint == null || endpoint.isBlank()) {
            return basePath;
        }
        String left = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
        String right = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        return left + right;
    }
}
