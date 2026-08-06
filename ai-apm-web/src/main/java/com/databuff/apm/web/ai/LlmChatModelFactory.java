package com.databuff.apm.web.ai;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.core.model.transport.HttpVersion;
import io.agentscope.core.model.transport.JdkHttpTransport;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public final class LlmChatModelFactory {

    private static final Pattern VERSION_IN_PATH = Pattern.compile(".*/v\\d+$");
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);
    private static final AtomicBoolean FORCE_HTTP11 = new AtomicBoolean(false);
    private static final AtomicBoolean HTTP11_TRANSPORT_INSTALLED = new AtomicBoolean(false);

    private LlmChatModelFactory() {
    }

    /**
     * Opt-in: when {@code true}, AgentScope LLM calls use HTTP/1.1 instead of the SDK default
     * HTTP/2. Needed for cleartext {@code http://} local model servers (uvicorn / vLLM / SGLang
     * etc.) that reject JDK's {@code Upgrade: h2c} with HTTP 400
     * {@code Invalid HTTP request received.} Default is {@code false} (SDK default unchanged).
     */
    public static void configureForceHttp11(boolean enabled) {
        FORCE_HTTP11.set(enabled);
        if (enabled) {
            installHttp11TransportIfNeeded();
        }
    }

    public static boolean isForceHttp11() {
        return FORCE_HTTP11.get();
    }

    private static void installHttp11TransportIfNeeded() {
        if (HTTP11_TRANSPORT_INSTALLED.get()) {
            return;
        }
        synchronized (HTTP11_TRANSPORT_INSTALLED) {
            if (HTTP11_TRANSPORT_INSTALLED.get()) {
                return;
            }
            HttpTransportConfig config = HttpTransportConfig.builder()
                    .httpVersion(HttpVersion.HTTP_1_1)
                    .build();
            HttpTransport transport = JdkHttpTransport.builder().config(config).build();
            HttpTransportFactory.setDefault(transport);
            HTTP11_TRANSPORT_INSTALLED.set(true);
        }
    }

    private static HttpTransport http11Transport() {
        installHttp11TransportIfNeeded();
        return HttpTransportFactory.getDefault();
    }

    /**
     * Resolved max output tokens, or {@code null} when not configured. A {@code null} result
     * means "do not send {@code max_tokens}"; the model / SDK then applies its own default
     * (e.g. AgentScope AnthropicChatModel's built-in 4096). DataBuff intentionally ships no
     * cross-model default — a single default cannot fit every vendor's max output limit and
     * oversized values make the LLM API reject the request (HTTP 400).
     */
    public static Integer resolveMaxOutputTokens(Integer configured) {
        if (configured != null && configured > 0) {
            return configured;
        }
        return null;
    }

    public static GenerateOptions generateOptions(Integer configuredMaxOutputTokens) {
        Integer resolved = resolveMaxOutputTokens(configuredMaxOutputTokens);
        GenerateOptions.Builder builder = GenerateOptions.builder();
        if (resolved != null) {
            builder.maxTokens(resolved);
        }
        return builder.build();
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
            // Uses HttpTransportFactory.getDefault() (HTTP/1.1 only when force-http11 is on).
            if (FORCE_HTTP11.get()) {
                installHttp11TransportIfNeeded();
            }
            return AnthropicChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(resolvedModel)
                    .baseUrl(normalizeAnthropicSdkBaseUrl(baseUrl))
                    .stream(stream)
                    .build();
        }
        // OpenAI Java SDK always appends /v1/chat/completions (with /v1 stripped when base ends in /vN).
        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(resolvedModel)
                .baseUrl(baseUrl)
                .stream(stream);
        if (FORCE_HTTP11.get()) {
            builder.httpTransport(http11Transport());
        }
        return builder.build();
    }

    /**
     * Connectivity probe through the same AgentScope {@link Model} stack used by expert Q&A.
     * Uses the same resolved {@code max_tokens} as real chat (configured value, or none when
     * unconfigured) so the probe reflects actual request parameters — a model that rejects the
     * configured max_tokens fails here too, rather than passing connectivity but failing every
     * real turn. Throws on failure so callers can surface the SDK/HTTP error message.
     */
    public static void probe(OpenAiCompatibleChatClient.ResolvedLlmProvider provider, String modelName) {
        if (provider == null) {
            throw new IllegalArgumentException("provider is required");
        }
        Model model = build(provider, modelName, false);
        Integer resolvedMaxTokens = resolveMaxOutputTokens(provider.maxOutputTokens());
        GenerateOptions.Builder optionsBuilder = GenerateOptions.builder()
                .executionConfig(ExecutionConfig.builder()
                        .timeout(PROBE_TIMEOUT)
                        .maxAttempts(1)
                        .build());
        if (resolvedMaxTokens != null) {
            optionsBuilder.maxTokens(resolvedMaxTokens);
        }
        Msg ping = Msg.builder()
                .role(MsgRole.USER)
                .textContent("ping")
                .build();
        model.stream(List.of(ping), List.of(), optionsBuilder.build()).blockLast(PROBE_TIMEOUT.plusSeconds(5));
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
