package com.databuff.apm.web.ai;

import com.databuff.apm.common.storage.ApmConfigRepository;
import com.databuff.apm.web.ai.platform.runtime.ExpertRuntimeRegistry;
import com.databuff.apm.web.config.ApiKeyCipher;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryLlmProviderStore {

    private static final String DEFAULT_API_TYPE = LlmApiTypes.OPENAI_COMPLETIONS;
    private static final String ANTHROPIC_API_TYPE = LlmApiTypes.ANTHROPIC_MESSAGES;

    private final Map<String, ProviderState> providers = new LinkedHashMap<>();
    private final Map<String, List<ModelState>> modelsByProvider = new LinkedHashMap<>();
    private final Map<String, String> apiKeys = new ConcurrentHashMap<>();
    private final Map<String, Long> providerVersions = new ConcurrentHashMap<>();
    @Autowired
    private ObjectProvider<ExpertRuntimeRegistry> runtimeRegistry;
    @Autowired
    private AiLlmProviderProperties providerProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile String defaultProviderCode;

    @PostConstruct
    void initDefaults() {
        seed("kimi", "Kimi", "https://api.moonshot.cn/v1", "kimi-k2.6", DEFAULT_API_TYPE);
        seed("volcengine", "火山引擎", "https://ark.cn-beijing.volces.com/api/coding/v3", "kimi-k2.6", DEFAULT_API_TYPE);
        seed("minimax", "MiniMax", "https://api.minimaxi.com/anthropic", "MiniMax-M3", ANTHROPIC_API_TYPE);
        seed("bailian", "百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", DEFAULT_API_TYPE);
        seed("deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-v4-flash", DEFAULT_API_TYPE);
        seed("zhipu", "智谱", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash", DEFAULT_API_TYPE);
        seed("qianfan", "千帆", "https://qianfan.baidubce.com/v2", "ernie-4.0-8k", DEFAULT_API_TYPE);
        seed("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", DEFAULT_API_TYPE);
        seed("ollama", "Ollama", "http://127.0.0.1:11434/v1", "llama3", DEFAULT_API_TYPE);
    }

    public List<LlmProviderView> listProviders() {
        return providers.values().stream().map(this::toView).toList();
    }

    public LlmProviderDetailView getProviderDetail(String providerCode) {
        ProviderState state = requireProvider(providerCode);
        return toDetail(state);
    }

    public LlmProviderView saveProviderDetail(SaveLlmProviderRequest request) {
        if (request.providerCode() == null || request.providerCode().isBlank()) {
            throw new IllegalArgumentException("providerCode is required");
        }
        String code = request.providerCode().trim();
        ProviderState state = providers.get(code);
        if (state == null) {
            throw new IllegalArgumentException("unknown provider: " + code);
        }
        if (request.providerName() != null && !request.providerName().isBlank()) {
            state.displayName = request.providerName().trim();
        }
        if (request.apiType() != null && !request.apiType().isBlank()) {
            state.apiType = request.apiType().trim();
        }
        if (request.baseUrl() != null && !request.baseUrl().isBlank()) {
            state.baseUrl = request.baseUrl().trim();
        }
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            apiKeys.put(code, request.apiKey().trim());
            if (request.enabled() == null) {
                state.enabled = true;
            }
        }
        if (request.enabled() != null) {
            state.enabled = request.enabled();
        }
        if (request.models() != null) {
            replaceModels(code, request.models(), request.defaultModelId());
        }
        if (Boolean.TRUE.equals(request.defaultProvider())) {
            defaultProviderCode = code;
        }
        bumpProviderVersion(code);
        invalidateByProvider(code);
        maybeSetDefaultProvider(code, state);
        return toView(state);
    }

    public LlmProviderView createProvider(CreateLlmProviderRequest request) {
        if (request.providerCode() == null || request.providerCode().isBlank()) {
            throw new IllegalArgumentException("providerCode is required");
        }
        String code = request.providerCode().trim().toLowerCase();
        if (!code.matches("^[a-z][a-z0-9_-]{1,31}$")) {
            throw new IllegalArgumentException("invalid providerCode: " + code);
        }
        if (providers.containsKey(code)) {
            throw new IllegalArgumentException("provider already exists: " + code);
        }
        if (request.displayName() == null || request.displayName().isBlank()) {
            throw new IllegalArgumentException("displayName is required");
        }
        if (request.baseUrl() == null || request.baseUrl().isBlank()) {
            throw new IllegalArgumentException("baseUrl is required");
        }
        String defaultModel = request.defaultModel() == null || request.defaultModel().isBlank()
                ? "default"
                : request.defaultModel().trim();
        boolean enabled = request.enabled() != null && request.enabled();
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            enabled = true;
        }
        ProviderState state = new ProviderState(
                code,
                request.displayName().trim(),
                request.baseUrl().trim(),
                defaultModel,
                DEFAULT_API_TYPE,
                enabled);
        providers.put(code, state);
        modelsByProvider.put(code, List.of(new ModelState(defaultModel, defaultModel, null, null, List.of(), true)));
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            apiKeys.put(code, request.apiKey().trim());
        }
        bumpProviderVersion(code);
        maybeSetDefaultProvider(code, state);
        return toView(state);
    }

    public LlmProviderView setDefaultProvider(String providerCode) {
        ProviderState state = requireProvider(providerCode);
        defaultProviderCode = providerCode;
        invalidateByProvider(providerCode);
        return toView(state);
    }

    public LlmProviderView updateProvider(String providerCode, UpdateLlmProviderRequest request) {
        ProviderState state = requireProvider(providerCode);
        if (request.baseUrl() != null && !request.baseUrl().isBlank()) {
            state.baseUrl = request.baseUrl().trim();
        }
        if (request.defaultModel() != null && !request.defaultModel().isBlank()) {
            state.defaultModel = request.defaultModel().trim();
            List<ModelState> models = modelsByProvider.computeIfAbsent(providerCode, key -> new ArrayList<>());
            if (models.isEmpty()) {
                models.add(new ModelState(state.defaultModel, state.defaultModel, null, null, List.of(), true));
            } else {
                for (ModelState model : models) {
                    model.isDefault = state.defaultModel.equals(model.modelId);
                }
            }
        }
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            apiKeys.put(providerCode, request.apiKey().trim());
            if (request.enabled() == null) {
                state.enabled = true;
            }
        }
        if (request.enabled() != null) {
            state.enabled = request.enabled();
        }
        bumpProviderVersion(providerCode);
        invalidateByProvider(providerCode);
        maybeSetDefaultProvider(providerCode, state);
        return toView(state);
    }

    public java.util.Optional<OpenAiCompatibleChatClient.ResolvedLlmProvider> resolveProvider(String providerCode) {
        if (providerCode == null || providerCode.isBlank()) {
            return firstEnabledProvider();
        }
        ProviderState state = providers.get(providerCode);
        if (state == null || !state.enabled || !apiKeys.containsKey(providerCode)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new OpenAiCompatibleChatClient.ResolvedLlmProvider(
                state.code,
                state.baseUrl,
                resolveDefaultModelId(providerCode, state),
                apiKeys.get(state.code),
                state.apiType));
    }

    public long providerVersion(String providerCode) {
        if (providerCode == null || providerCode.isBlank()) {
            return 0L;
        }
        return providerVersions.getOrDefault(providerCode, 0L);
    }

    public String resolveApiKey(String providerCode, String requestApiKey) {
        if (requestApiKey != null && !requestApiKey.isBlank()) {
            return requestApiKey.trim();
        }
        if (providerCode == null || providerCode.isBlank()) {
            return null;
        }
        return apiKeys.get(providerCode.trim());
    }

    public TestLlmProviderResult testConnection(TestLlmProviderRequest request) {
        if (request.baseUrl() == null || request.baseUrl().isBlank()) {
            return new TestLlmProviderResult(false, "baseUrl is required");
        }
        TestLlmProviderRequest resolved = withResolvedApiKey(request);
        String apiType = LlmApiTypes.normalize(resolved.apiType());
        String modelId = resolveTestModelId(resolved);
        if (modelId == null) {
            return new TestLlmProviderResult(false, "请先配置模型 ID");
        }
        try {
            if (LlmApiTypes.isAnthropic(apiType)) {
                return testAnthropicMessage(resolved, modelId);
            }
            return testOpenAiChat(resolved, modelId);
        } catch (Exception e) {
            return new TestLlmProviderResult(false, e.getMessage() == null ? "connection failed" : e.getMessage());
        }
    }

    private String resolveTestModelId(TestLlmProviderRequest request) {
        if (request.modelId() != null && !request.modelId().isBlank()) {
            return request.modelId().trim();
        }
        if (request.providerCode() == null || request.providerCode().isBlank()) {
            return null;
        }
        String providerCode = request.providerCode().trim();
        String fromModels = modelsByProvider.getOrDefault(providerCode, List.of()).stream()
                .filter(model -> model.isDefault)
                .map(model -> model.modelId)
                .filter(modelId -> modelId != null && !modelId.isBlank())
                .findFirst()
                .orElse(null);
        if (fromModels != null) {
            return fromModels;
        }
        fromModels = modelsByProvider.getOrDefault(providerCode, List.of()).stream()
                .map(model -> model.modelId)
                .filter(modelId -> modelId != null && !modelId.isBlank())
                .findFirst()
                .orElse(null);
        if (fromModels != null) {
            return fromModels;
        }
        ProviderState state = providers.get(providerCode);
        if (state != null && state.defaultModel != null && !state.defaultModel.isBlank()) {
            return state.defaultModel.trim();
        }
        return null;
    }

    private TestLlmProviderRequest withResolvedApiKey(TestLlmProviderRequest request) {
        String apiKey = resolveApiKey(request.providerCode(), request.apiKey());
        if (apiKey == null || apiKey.equals(request.apiKey())) {
            return request;
        }
        return new TestLlmProviderRequest(
                request.baseUrl(),
                apiKey,
                request.apiType(),
                request.modelId(),
                request.providerCode());
    }

    public boolean hasEnabledProvider() {
        return providers.values().stream().anyMatch(state -> state.enabled && apiKeys.containsKey(state.code));
    }

    public java.util.Optional<OpenAiCompatibleChatClient.ResolvedLlmProvider> firstEnabledProvider() {
        if (defaultProviderCode != null) {
            java.util.Optional<OpenAiCompatibleChatClient.ResolvedLlmProvider> preferred =
                    resolveProvider(defaultProviderCode);
            if (preferred.isPresent()) {
                return preferred;
            }
        }
        return providers.values().stream()
                .filter(state -> state.enabled && apiKeys.containsKey(state.code))
                .findFirst()
                .map(state -> new OpenAiCompatibleChatClient.ResolvedLlmProvider(
                        state.code,
                        state.baseUrl,
                        resolveDefaultModelId(state.code, state),
                        apiKeys.get(state.code),
                        state.apiType));
    }

    public void applyPersistedRows(
            java.util.List<ApmConfigRepository.LlmProviderRow> rows,
            java.util.List<ApmConfigRepository.LlmModelRow> modelRows) {
        for (ApmConfigRepository.LlmProviderRow row : rows) {
            ProviderState state = providers.get(row.providerCode());
            String apiType = row.apiType() == null || row.apiType().isBlank() ? DEFAULT_API_TYPE : row.apiType();
            if (state == null) {
                state = new ProviderState(
                        row.providerCode(),
                        row.displayName(),
                        row.baseUrl(),
                        row.defaultModel(),
                        apiType,
                        row.enabled());
                providers.put(row.providerCode(), state);
            } else {
                state.displayName = row.displayName();
                state.baseUrl = row.baseUrl();
                state.defaultModel = row.defaultModel();
                state.apiType = apiType;
                state.enabled = row.enabled();
            }
            String plain = ApiKeyCipher.decode(row.apiKeyCipher());
            if (plain != null && !plain.isBlank()) {
                apiKeys.put(row.providerCode(), plain);
            }
            bumpProviderVersion(row.providerCode());
            invalidateByProvider(row.providerCode());
            if (defaultProviderCode == null && row.enabled() && plain != null && !plain.isBlank()) {
                defaultProviderCode = row.providerCode();
            }
        }
        if (modelRows != null && !modelRows.isEmpty()) {
            Map<String, List<ModelState>> grouped = new LinkedHashMap<>();
            for (ApmConfigRepository.LlmModelRow row : modelRows) {
                grouped.computeIfAbsent(row.providerCode(), key -> new ArrayList<>())
                        .add(fromPersistedModel(row));
            }
            modelsByProvider.putAll(grouped);
        }
        for (Map.Entry<String, ProviderState> entry : providers.entrySet()) {
            modelsByProvider.computeIfAbsent(entry.getKey(), key -> defaultModelsFor(entry.getValue()));
        }
    }

    public void applyPersistedRows(java.util.List<ApmConfigRepository.LlmProviderRow> rows) {
        applyPersistedRows(rows, List.of());
    }

    public List<ApmConfigRepository.LlmModelRow> exportModelRows(String providerCode) {
        return modelsByProvider.getOrDefault(providerCode, List.of()).stream()
                .map(model -> new ApmConfigRepository.LlmModelRow(
                        providerCode,
                        model.modelId,
                        model.displayName,
                        model.contextWindow,
                        model.maxOutputTokens,
                        encodeEnvVars(model.envVars),
                        model.isDefault,
                        true))
                .toList();
    }

    public String apiKeyCipher(String providerCode) {
        String key = apiKeys.get(providerCode);
        return key == null ? null : ApiKeyCipher.encode(key);
    }

    public String resolvedApiType(String providerCode) {
        ProviderState state = providers.get(providerCode);
        return state == null ? DEFAULT_API_TYPE : state.apiType;
    }

    private ProviderState requireProvider(String providerCode) {
        ProviderState state = providers.get(providerCode);
        if (state == null) {
            throw new IllegalArgumentException("unknown provider: " + providerCode);
        }
        return state;
    }

    private void replaceModels(String providerCode, List<LlmModelView> models, String defaultModelId) {
        if (models.isEmpty()) {
            throw new IllegalArgumentException("至少配置一个模型");
        }
        List<ModelState> next = new ArrayList<>();
        String resolvedDefault = defaultModelId;
        if (resolvedDefault == null || resolvedDefault.isBlank()) {
            resolvedDefault = models.stream().filter(LlmModelView::defaultModel).map(LlmModelView::modelId)
                    .findFirst().orElse(models.get(0).modelId());
        }
        for (LlmModelView model : models) {
            if (model.modelId() == null || model.modelId().isBlank()) {
                continue;
            }
            String modelId = model.modelId().trim();
            String displayName = model.displayName() == null || model.displayName().isBlank()
                    ? modelId
                    : model.displayName().trim();
            next.add(new ModelState(
                    modelId,
                    displayName,
                    model.contextWindow(),
                    model.maxOutputTokens(),
                    copyEnvVars(model.envVars()),
                    modelId.equals(resolvedDefault)));
        }
        if (next.isEmpty()) {
            throw new IllegalArgumentException("至少配置一个有效模型");
        }
        boolean hasDefault = next.stream().anyMatch(model -> model.isDefault);
        if (!hasDefault) {
            next.get(0).isDefault = true;
        }
        modelsByProvider.put(providerCode, next);
        ProviderState state = requireProvider(providerCode);
        state.defaultModel = next.stream().filter(model -> model.isDefault).map(model -> model.modelId)
                .findFirst().orElse(next.get(0).modelId);
    }

    private List<ModelState> defaultModelsFor(ProviderState state) {
        return new ArrayList<>(List.of(new ModelState(
                state.defaultModel,
                state.defaultModel,
                null,
                null,
                List.of(),
                true)));
    }

    private ModelState fromPersistedModel(ApmConfigRepository.LlmModelRow row) {
        return new ModelState(
                row.modelId(),
                row.displayName() == null || row.displayName().isBlank() ? row.modelId() : row.displayName(),
                row.contextWindow(),
                row.maxOutputTokens(),
                decodeEnvVars(row.envVarsJson()),
                row.isDefault());
    }

    private String resolveDefaultModelId(String providerCode, ProviderState state) {
        return modelsByProvider.getOrDefault(providerCode, List.of()).stream()
                .filter(model -> model.isDefault)
                .map(model -> model.modelId)
                .findFirst()
                .orElse(state.defaultModel);
    }

    private LlmProviderDetailView toDetail(ProviderState state) {
        List<LlmModelView> models = modelsByProvider.getOrDefault(state.code, defaultModelsFor(state)).stream()
                .map(this::toModelView)
                .toList();
        String storedKey = apiKeys.get(state.code);
        boolean maskApiKey = providerProperties == null || providerProperties.maskApiKey();
        boolean configured = storedKey != null;
        boolean apiKeyMasked = maskApiKey && configured;
        String apiKey = apiKeyMasked ? null : storedKey;
        return new LlmProviderDetailView(
                state.code,
                state.displayName,
                state.apiType,
                state.baseUrl,
                configured,
                apiKey,
                apiKeyMasked,
                state.enabled,
                state.code.equals(defaultProviderCode),
                models);
    }

    private LlmModelView toModelView(ModelState model) {
        return new LlmModelView(
                model.modelId,
                model.displayName,
                model.contextWindow,
                model.maxOutputTokens,
                model.envVars.stream().map(item -> new LlmEnvVarItem(item.key, item.value)).toList(),
                model.isDefault);
    }

    private void maybeSetDefaultProvider(String providerCode, ProviderState state) {
        if (!state.enabled || !apiKeys.containsKey(state.code)) {
            return;
        }
        if (defaultProviderCode == null || !isProviderUsable(defaultProviderCode)) {
            defaultProviderCode = providerCode;
        }
    }

    private boolean isProviderUsable(String providerCode) {
        ProviderState state = providers.get(providerCode);
        return state != null && state.enabled && apiKeys.containsKey(providerCode);
    }

    private void seed(String code, String name, String baseUrl, String defaultModel, String apiType) {
        providers.put(code, new ProviderState(code, name, baseUrl, defaultModel, apiType, false));
        modelsByProvider.put(code, defaultModelsFor(providers.get(code)));
        providerVersions.put(code, 1L);
    }

    private void bumpProviderVersion(String providerCode) {
        if (providerCode == null || providerCode.isBlank()) {
            return;
        }
        providerVersions.merge(providerCode, 1L, (current, delta) -> current + delta);
    }

    private void invalidateByProvider(String providerCode) {
        if (runtimeRegistry != null) {
            runtimeRegistry.ifAvailable(registry -> registry.invalidateByProvider(providerCode));
        }
    }

    private LlmProviderView toView(ProviderState state) {
        return new LlmProviderView(
                state.code,
                state.displayName,
                state.baseUrl,
                resolveDefaultModelId(state.code, state),
                state.apiType,
                modelsByProvider.getOrDefault(state.code, List.of()).size(),
                state.enabled,
                apiKeys.containsKey(state.code),
                state.code.equals(defaultProviderCode));
    }

    private TestLlmProviderResult testOpenAiChat(TestLlmProviderRequest request, String modelId) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "model", modelId,
                "messages", List.of(Map.of("role", "user", "content", "ping")),
                "max_tokens", 8));
        URI uri = URI.create(LlmChatModelFactory.buildOpenAiChatCompletionsUrl(request.baseUrl()));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + request.apiKey().trim());
        }
        HttpResponse<String> response = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return new TestLlmProviderResult(true, "连接成功");
        }
        return new TestLlmProviderResult(false, "HTTP " + response.statusCode());
    }

    private TestLlmProviderResult testAnthropicMessage(TestLlmProviderRequest request, String modelId) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "model", modelId,
                "max_tokens", 8,
                "messages", List.of(Map.of("role", "user", "content", "ping"))));
        URI uri = URI.create(LlmChatModelFactory.buildAnthropicMessagesUrl(request.baseUrl()));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        OpenAiCompatibleChatClient.applyAnthropicAuth(builder, request.apiKey());
        HttpResponse<String> response = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return new TestLlmProviderResult(true, "连接成功");
        }
        return new TestLlmProviderResult(false, "HTTP " + response.statusCode());
    }

    private String encodeEnvVars(List<EnvVarState> envVars) {
        try {
            List<LlmEnvVarItem> items = envVars.stream()
                    .map(item -> new LlmEnvVarItem(item.key, item.value))
                    .toList();
            return objectMapper.writeValueAsString(items);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<EnvVarState> decodeEnvVars(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<LlmEnvVarItem> items = objectMapper.readValue(json, new TypeReference<List<LlmEnvVarItem>>() {});
            return copyEnvVars(items);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<EnvVarState> copyEnvVars(List<LlmEnvVarItem> envVars) {
        if (envVars == null || envVars.isEmpty()) {
            return List.of();
        }
        List<EnvVarState> copied = new ArrayList<>();
        for (LlmEnvVarItem item : envVars) {
            if (item.key() == null || item.key().isBlank()) {
                continue;
            }
            copied.add(new EnvVarState(item.key().trim(), item.value() == null ? "" : item.value()));
        }
        return copied;
    }

    private static final class ProviderState {
        private final String code;
        private String displayName;
        private String baseUrl;
        private String defaultModel;
        private String apiType;
        private boolean enabled;

        private ProviderState(
                String code,
                String displayName,
                String baseUrl,
                String defaultModel,
                String apiType,
                boolean enabled) {
            this.code = code;
            this.displayName = displayName;
            this.baseUrl = baseUrl;
            this.defaultModel = defaultModel;
            this.apiType = apiType == null || apiType.isBlank() ? DEFAULT_API_TYPE : apiType;
            this.enabled = enabled;
        }
    }

    private static final class ModelState {
        private final String modelId;
        private String displayName;
        private Integer contextWindow;
        private Integer maxOutputTokens;
        private List<EnvVarState> envVars;
        private boolean isDefault;

        private ModelState(
                String modelId,
                String displayName,
                Integer contextWindow,
                Integer maxOutputTokens,
                List<EnvVarState> envVars,
                boolean isDefault) {
            this.modelId = modelId;
            this.displayName = displayName;
            this.contextWindow = contextWindow;
            this.maxOutputTokens = maxOutputTokens;
            this.envVars = envVars == null ? List.of() : new ArrayList<>(envVars);
            this.isDefault = isDefault;
        }
    }

    private static final class EnvVarState {
        private final String key;
        private final String value;

        private EnvVarState(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }
}
