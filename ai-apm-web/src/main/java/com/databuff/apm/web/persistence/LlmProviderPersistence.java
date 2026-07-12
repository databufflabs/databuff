package com.databuff.apm.web.persistence;

import com.databuff.apm.web.ai.InMemoryLlmProviderStore;
import com.databuff.apm.web.ai.LlmProviderView;
import com.databuff.apm.web.ai.CreateLlmProviderRequest;
import com.databuff.apm.web.ai.SaveLlmProviderRequest;
import com.databuff.apm.web.ai.UpdateLlmProviderRequest;

import com.databuff.apm.common.storage.ApmConfigRepository;
import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.web.config.ApiKeyCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.databuff.apm.web.config.ApmStorageProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LlmProviderPersistence {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderPersistence.class);

    private final ApmReadRepository readRepository;
    private final InMemoryLlmProviderStore memoryStore;
    private final String configDatabase;
    private volatile boolean persistenceEnabled;

    public LlmProviderPersistence(
            ApmReadRepository readRepository,
            InMemoryLlmProviderStore memoryStore,
            ApmStorageProperties storageProperties) {
        this.readRepository = readRepository;
        this.memoryStore = memoryStore;
        this.configDatabase = storageProperties.configDatabase();
    }

    synchronized void reloadFromStore() {
        ApmConfigRepository repository = new ApmConfigRepository(readRepository, configDatabase);
        if (!repository.schemaReady()) {
            persistenceEnabled = false;
            log.info("Config store not ready; LLM providers stay in-memory only");
            return;
        }
        persistenceEnabled = true;

        List<ApmConfigRepository.LlmProviderRow> rows = List.of();
        List<ApmConfigRepository.LlmModelRow> modelRows = List.of();
        try {
            rows = repository.loadLlmProviders();
        } catch (Exception e) {
            log.warn("Failed to load LLM providers from store: {}", e.getMessage());
        }
        try {
            modelRows = repository.loadLlmModels();
        } catch (Exception e) {
            log.warn("Failed to load LLM models from store: {}", e.getMessage());
        }
        if (!rows.isEmpty() || !modelRows.isEmpty()) {
            memoryStore.applyPersistedRows(rows, modelRows);
        }
        log.info("LLM provider persistence enabled ({} providers, {} models from store)",
                rows.size(), modelRows.size());
    }

    public void persistDetail(SaveLlmProviderRequest request, LlmProviderView view) {
        if (!ensurePersistence()) {
            return;
        }
        try {
            ApmConfigRepository repository = new ApmConfigRepository(readRepository, configDatabase);
            String cipher = request.apiKey() != null && !request.apiKey().isBlank()
                    ? ApiKeyCipher.encode(request.apiKey().trim())
                    : memoryStore.apiKeyCipher(view.providerCode());
            repository.upsertLlmProvider(new ApmConfigRepository.LlmProviderRow(
                    view.providerCode(),
                    view.displayName(),
                    view.baseUrl(),
                    view.enabled(),
                    cipher,
                    view.defaultModel(),
                    view.apiType()));
            repository.replaceLlmModels(view.providerCode(), memoryStore.exportModelRows(view.providerCode()));
        } catch (Exception e) {
            log.error("Failed to persist LLM provider detail {}: {}", view.providerCode(), e.getMessage(), e);
            throw new IllegalStateException("保存模型配置到数据库失败: " + e.getMessage(), e);
        }
    }

    public void persistUpdate(String providerCode, UpdateLlmProviderRequest request, LlmProviderView view) {
        if (!ensurePersistence()) {
            return;
        }
        try {
            ApmConfigRepository repository = new ApmConfigRepository(readRepository, configDatabase);
            String cipher = request.apiKey() != null && !request.apiKey().isBlank()
                    ? ApiKeyCipher.encode(request.apiKey().trim())
                    : memoryStore.apiKeyCipher(providerCode);
            repository.upsertLlmProvider(new ApmConfigRepository.LlmProviderRow(
                    view.providerCode(),
                    view.displayName(),
                    view.baseUrl(),
                    view.enabled(),
                    cipher,
                    view.defaultModel(),
                    view.apiType()));
            repository.replaceLlmModels(view.providerCode(), memoryStore.exportModelRows(view.providerCode()));
        } catch (Exception e) {
            log.error("Failed to persist LLM provider {}: {}", providerCode, e.getMessage(), e);
            throw new IllegalStateException("保存模型配置到数据库失败: " + e.getMessage(), e);
        }
    }

    public void persistCreate(CreateLlmProviderRequest request, LlmProviderView view) {
        if (!ensurePersistence()) {
            return;
        }
        try {
            ApmConfigRepository repository = new ApmConfigRepository(readRepository, configDatabase);
            String cipher = request.apiKey() != null && !request.apiKey().isBlank()
                    ? ApiKeyCipher.encode(request.apiKey().trim())
                    : null;
            repository.upsertLlmProvider(new ApmConfigRepository.LlmProviderRow(
                    view.providerCode(),
                    view.displayName(),
                    view.baseUrl(),
                    view.enabled(),
                    cipher,
                    view.defaultModel(),
                    view.apiType()));
            repository.replaceLlmModels(view.providerCode(), memoryStore.exportModelRows(view.providerCode()));
        } catch (Exception e) {
            log.error("Failed to persist new LLM provider {}: {}", view.providerCode(), e.getMessage(), e);
            throw new IllegalStateException("保存模型配置到数据库失败: " + e.getMessage(), e);
        }
    }

    private synchronized boolean ensurePersistence() {
        if (!persistenceEnabled) {
            reloadFromStore();
        }
        if (!persistenceEnabled) {
            log.warn("LLM provider config saved in memory only; Doris store unavailable");
            return false;
        }
        return true;
    }

    boolean persistenceEnabled() {
        return persistenceEnabled;
    }
}
