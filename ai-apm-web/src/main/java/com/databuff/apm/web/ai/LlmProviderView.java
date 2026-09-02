package com.databuff.apm.web.ai;

public record LlmProviderView(
        String providerCode,
        String displayName,
        String baseUrl,
        String defaultModel,
        String apiType,
        int modelCount,
        boolean enabled,
        boolean configured,
        boolean defaultProvider,
        boolean builtIn) {
}
