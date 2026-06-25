package com.databuff.apm.web.ai;

import com.databuff.apm.web.persistence.LlmProviderPersistence;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AiConfigService {

    private final InMemoryLlmProviderStore store;
    private final LlmCatalogService catalogService;
    private final LlmProviderPersistence llmProviderPersistence;
    private final AiLlmProviderProperties providerProperties;

    public AiConfigService(
            InMemoryLlmProviderStore store,
            LlmCatalogService catalogService,
            LlmProviderPersistence llmProviderPersistence,
            AiLlmProviderProperties providerProperties) {
        this.store = store;
        this.catalogService = catalogService;
        this.llmProviderPersistence = llmProviderPersistence;
        this.providerProperties = providerProperties;
    }

    public List<LlmProviderView> listProviders() {
        return store.listProviders();
    }

    public LlmProviderDetailView getProviderDetail(String providerCode) {
        return store.getProviderDetail(providerCode);
    }

    public LlmProviderView saveProviderDetail(SaveLlmProviderRequest request) {
        LlmProviderView view = store.saveProviderDetail(request);
        llmProviderPersistence.persistDetail(request, view);
        return view;
    }

    public LlmProviderView updateProvider(String providerCode, UpdateLlmProviderRequest request) {
        LlmProviderView view = store.updateProvider(providerCode, request);
        llmProviderPersistence.persistUpdate(providerCode, request, view);
        return view;
    }

    public LlmProviderView createProvider(CreateLlmProviderRequest request) {
        LlmProviderView view = store.createProvider(request);
        llmProviderPersistence.persistCreate(request, view);
        return view;
    }

    public LlmProviderView setDefaultProvider(String providerCode) {
        return store.setDefaultProvider(providerCode);
    }

    public TestLlmProviderResult testProvider(TestLlmProviderRequest request) {
        return store.testConnection(request);
    }

    public List<LlmModelView> fetchModels(FetchLlmModelsRequest request) {
        String apiKey = store.resolveApiKey(request.providerCode(), request.apiKey());
        if (apiKey != null && !apiKey.equals(request.apiKey())) {
            request = new FetchLlmModelsRequest(
                    request.providerCode(),
                    request.apiType(),
                    request.baseUrl(),
                    apiKey);
        }
        return catalogService.fetchModels(request);
    }

    public boolean aiReady() {
        return store.hasEnabledProvider();
    }

    public boolean maskProviderApiKey() {
        return providerProperties.maskApiKey();
    }
}
