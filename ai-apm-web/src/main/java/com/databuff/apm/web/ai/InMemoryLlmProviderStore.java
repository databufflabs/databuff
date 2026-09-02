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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        state.configured = true;
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
                enabled,
                true,
                false);
        providers.put(code, state);
        modelsByProvider.put(code, List.of(new ModelState(
                defaultModel,
                defaultModel,
                null,
                null,
                List.of(),
                true)));
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            apiKeys.put(code, request.apiKey().trim());
        }
        bumpProviderVersion(code);
        maybeSetDefaultProvider(code, state);
        return toView(state);
    }

    public void validateProviderDeletion(String providerCode) {
        ProviderState state = requireProvider(providerCode);
        if (state.builtIn) {
            throw new IllegalArgumentException("内置模型提供商不支持删除");
        }
    }

    public void deleteProvider(String providerCode) {
        validateProviderDeletion(providerCode);
        removeProvider(providerCode);
    }

    public void rollbackCreatedProvider(String providerCode) {
        ProviderState state = providers.get(providerCode);
        if (state != null && !state.builtIn) {
            removeProvider(providerCode);
        }
    }

    private void removeProvider(String providerCode) {
        providers.remove(providerCode);
        modelsByProvider.remove(providerCode);
        apiKeys.remove(providerCode);
        providerVersions.remove(providerCode);
        if (providerCode.equals(defaultProviderCode)) {
            defaultProviderCode = providers.values().stream()
                    .filter(state -> isProviderUsable(state.code))
                    .map(state -> state.code)
                    .findFirst()
                    .orElse(null);
        }
        invalidateByProvider(providerCode);
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
                models.add(new ModelState(
                        state.defaultModel,
                        state.defaultModel,
                        null,
                        null,
                        List.of(),
                        true));
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
        state.configured = true;
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
        if (state == null || !state.enabled || !state.configured) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new OpenAiCompatibleChatClient.ResolvedLlmProvider(
                state.code,
                state.baseUrl,
                resolveDefaultModelId(providerCode, state),
                apiKeys.get(state.code),
                state.apiType,
                configuredMaxOutputTokens(providerCode, null)));
    }

    /**
     * Max output tokens for a provider model, or {@code null} when unconfigured (meaning
     * "do not send {@code max_tokens}"; the model / SDK applies its own default).
     * DataBuff does not clamp to vendor limits — oversized configured values may fail at the LLM API.
     */
    public Integer resolveMaxOutputTokens(String providerCode, String modelId) {
        return LlmChatModelFactory.resolveMaxOutputTokens(configuredMaxOutputTokens(providerCode, modelId));
    }

    private Integer configuredMaxOutputTokens(String providerCode, String modelId) {
        if (providerCode == null || providerCode.isBlank()) {
            return null;
        }
        List<ModelState> models = modelsByProvider.getOrDefault(providerCode, List.of());
        String resolvedId = modelId;
        if (resolvedId == null || resolvedId.isBlank()) {
            ProviderState state = providers.get(providerCode);
            resolvedId = state == null ? null : resolveDefaultModelId(providerCode, state);
        }
        if (resolvedId == null || resolvedId.isBlank()) {
            return null;
        }
        for (ModelState model : models) {
            if (resolvedId.equals(model.modelId)) {
                return model.maxOutputTokens;
            }
        }
        return null;
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
        String modelId = resolveTestModelId(resolved);
        if (modelId == null) {
            return new TestLlmProviderResult(false, "请先配置模型 ID");
        }
        try {
            // Same AgentScope Model path as expert Q&A (LlmChatModelFactory.build → OpenAI/AnthropicChatModel).
            LlmChatModelFactory.probe(
                    new OpenAiCompatibleChatClient.ResolvedLlmProvider(
                            resolved.providerCode(),
                            resolved.baseUrl().trim(),
                            modelId,
                            resolved.apiKey(),
                            LlmApiTypes.normalize(resolved.apiType()),
                            configuredMaxOutputTokens(resolved.providerCode(), modelId)),
                    modelId);
            return new TestLlmProviderResult(true, "连接成功");
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
        return providers.values().stream().anyMatch(state -> state.enabled && state.configured);
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
                .filter(state -> state.enabled && state.configured)
                .findFirst()
                .map(state -> new OpenAiCompatibleChatClient.ResolvedLlmProvider(
                        state.code,
                        state.baseUrl,
                        resolveDefaultModelId(state.code, state),
                        apiKeys.get(state.code),
                        state.apiType,
                        configuredMaxOutputTokens(state.code, null)));
    }

    public void applyPersistedRows(
            java.util.List<ApmConfigRepository.LlmProviderRow> rows,
            java.util.List<ApmConfigRepository.LlmModelRow> modelRows) {
        for (ApmConfigRepository.LlmProviderRow row : rows) {
            ProviderState state = providers.get(row.providerCode());
            String apiType = row.apiType() == null || row.apiType().isBlank() ? DEFAULT_API_TYPE : row.apiType();
            String plain = ApiKeyCipher.decode(row.apiKeyCipher());
            String existingKey = apiKeys.get(row.providerCode());
            boolean changed;
            if (state == null) {
                state = new ProviderState(
                        row.providerCode(),
                        row.displayName(),
                        row.baseUrl(),
                        row.defaultModel(),
                        apiType,
                        row.enabled(),
                        true,
                        false);
                providers.put(row.providerCode(), state);
                changed = true;
            } else {
                changed = !java.util.Objects.equals(state.displayName, row.displayName())
                        || !java.util.Objects.equals(state.baseUrl, row.baseUrl())
                        || !java.util.Objects.equals(state.defaultModel, row.defaultModel())
                        || !java.util.Objects.equals(state.apiType, apiType)
                        || state.enabled != row.enabled()
                        || !state.configured
                        || (plain != null && !plain.isBlank() && !java.util.Objects.equals(existingKey, plain));
                state.displayName = row.displayName();
                state.baseUrl = row.baseUrl();
                state.defaultModel = row.defaultModel();
                state.apiType = apiType;
                state.enabled = row.enabled();
                state.configured = true;
            }
            if (plain != null && !plain.isBlank()) {
                apiKeys.put(row.providerCode(), plain);
            }
            // Doris hydrate may bump the version so the *next* turn rebuilds lazily, but must
            // never invalidate/close live runtimes — that kills every digital expert mid-flight.
            if (changed) {
                bumpProviderVersion(row.providerCode());
            }
            if (defaultProviderCode == null && row.enabled()) {
                defaultProviderCode = row.providerCode();
            }
        }
        if (modelRows != null && !modelRows.isEmpty()) {
            Map<String, List<ModelState>> grouped = new LinkedHashMap<>();
            for (ApmConfigRepository.LlmModelRow row : modelRows) {
                grouped.computeIfAbsent(row.providerCode(), key -> new ArrayList<>())
                        .add(fromPersistedModel(row));
            }
            for (Map.Entry<String, List<ModelState>> entry : grouped.entrySet()) {
                List<ModelState> previous = modelsByProvider.getOrDefault(entry.getKey(), List.of());
                if (!modelListsEqual(previous, entry.getValue())) {
                    modelsByProvider.put(entry.getKey(), entry.getValue());
                    bumpProviderVersion(entry.getKey());
                }
            }
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

    public ProviderSnapshot snapshotProvider(String providerCode) {
        ProviderState state = requireProvider(providerCode);
        return new ProviderSnapshot(
                copyProviderState(state),
                copyModelStates(modelsByProvider.getOrDefault(providerCode, List.of())),
                apiKeys.get(providerCode),
                apiKeys.containsKey(providerCode),
                providerVersions.get(providerCode),
                defaultProviderCode);
    }

    public void restoreProvider(ProviderSnapshot snapshot) {
        String providerCode = snapshot.state.code;
        providers.put(providerCode, copyProviderState(snapshot.state));
        modelsByProvider.put(providerCode, copyModelStates(snapshot.models));
        if (snapshot.hadApiKey) {
            apiKeys.put(providerCode, snapshot.apiKey);
        } else {
            apiKeys.remove(providerCode);
        }
        if (snapshot.version == null) {
            providerVersions.remove(providerCode);
        } else {
            providerVersions.put(providerCode, snapshot.version);
        }
        defaultProviderCode = snapshot.defaultProviderCode;
        invalidateByProvider(providerCode);
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
        Set<String> modelIds = new HashSet<>();
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
            if (!modelIds.add(modelId)) {
                throw new IllegalArgumentException("模型 ID 不能重复: " + modelId);
            }
            if (model.contextWindow() != null && model.contextWindow() <= 0) {
                throw new IllegalArgumentException("上下文窗口必须为正整数: " + modelId);
            }
            if (model.maxOutputTokens() != null && model.maxOutputTokens() <= 0) {
                throw new IllegalArgumentException("最大输出 Token 必须为正整数: " + modelId);
            }
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

    private static boolean modelListsEqual(List<ModelState> left, List<ModelState> right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            ModelState a = left.get(i);
            ModelState b = right.get(i);
            if (!java.util.Objects.equals(a.modelId, b.modelId)
                    || !java.util.Objects.equals(a.displayName, b.displayName)
                    || !java.util.Objects.equals(a.contextWindow, b.contextWindow)
                    || !java.util.Objects.equals(a.maxOutputTokens, b.maxOutputTokens)
                    || a.isDefault != b.isDefault
                    || !envVarListsEqual(a.envVars, b.envVars)) {
                return false;
            }
        }
        return true;
    }

    private static boolean envVarListsEqual(List<EnvVarState> left, List<EnvVarState> right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            EnvVarState a = left.get(i);
            EnvVarState b = right.get(i);
            if (!java.util.Objects.equals(a.key, b.key) || !java.util.Objects.equals(a.value, b.value)) {
                return false;
            }
        }
        return true;
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
        boolean configured = state.configured;
        boolean apiKeyMasked = maskApiKey && storedKey != null;
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
                state.builtIn,
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
        if (!state.enabled || !state.configured) {
            return;
        }
        if (defaultProviderCode == null || !isProviderUsable(defaultProviderCode)) {
            defaultProviderCode = providerCode;
        }
    }

    private boolean isProviderUsable(String providerCode) {
        ProviderState state = providers.get(providerCode);
        return state != null && state.enabled && state.configured;
    }

    private void seed(String code, String name, String baseUrl, String defaultModel, String apiType) {
        providers.put(code, new ProviderState(code, name, baseUrl, defaultModel, apiType, false, false, true));
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
                state.configured,
                state.code.equals(defaultProviderCode),
                state.builtIn);
    }

    private ProviderState copyProviderState(ProviderState state) {
        return new ProviderState(
                state.code,
                state.displayName,
                state.baseUrl,
                state.defaultModel,
                state.apiType,
                state.enabled,
                state.configured,
                state.builtIn);
    }

    private List<ModelState> copyModelStates(List<ModelState> models) {
        List<ModelState> copied = new ArrayList<>();
        for (ModelState model : models) {
            copied.add(new ModelState(
                    model.modelId,
                    model.displayName,
                    model.contextWindow,
                    model.maxOutputTokens,
                    model.envVars.stream()
                            .map(env -> new EnvVarState(env.key, env.value))
                            .toList(),
                    model.isDefault));
        }
        return copied;
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
        private boolean configured;
        private final boolean builtIn;

        private ProviderState(
                String code,
                String displayName,
                String baseUrl,
                String defaultModel,
                String apiType,
                boolean enabled,
                boolean configured,
                boolean builtIn) {
            this.code = code;
            this.displayName = displayName;
            this.baseUrl = baseUrl;
            this.defaultModel = defaultModel;
            this.apiType = apiType == null || apiType.isBlank() ? DEFAULT_API_TYPE : apiType;
            this.enabled = enabled;
            this.configured = configured;
            this.builtIn = builtIn;
        }
    }

    public static final class ProviderSnapshot {
        private final ProviderState state;
        private final List<ModelState> models;
        private final String apiKey;
        private final boolean hadApiKey;
        private final Long version;
        private final String defaultProviderCode;

        private ProviderSnapshot(
                ProviderState state,
                List<ModelState> models,
                String apiKey,
                boolean hadApiKey,
                Long version,
                String defaultProviderCode) {
            this.state = state;
            this.models = models;
            this.apiKey = apiKey;
            this.hadApiKey = hadApiKey;
            this.version = version;
            this.defaultProviderCode = defaultProviderCode;
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
