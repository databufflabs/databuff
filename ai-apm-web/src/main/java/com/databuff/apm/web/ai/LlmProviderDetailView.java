package com.databuff.apm.web.ai;

import java.util.List;

public record LlmProviderDetailView(
        String providerCode,
        String providerName,
        String apiType,
        String baseUrl,
        boolean configured,
        String apiKey,
        boolean apiKeyMasked,
        boolean enabled,
        boolean defaultProvider,
        List<LlmModelView> models) {
}
