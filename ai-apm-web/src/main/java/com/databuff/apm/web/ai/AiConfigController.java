package com.databuff.apm.web.ai;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/config/ai")
public class AiConfigController {

    private final AiConfigService aiConfigService;

    public AiConfigController(AiConfigService aiConfigService) {
        this.aiConfigService = aiConfigService;
    }

    @GetMapping("/providers")
    public List<LlmProviderView> listProviders() {
        return aiConfigService.listProviders();
    }

    @PostMapping("/providers/test")
    public TestLlmProviderResult testProvider(@RequestBody TestLlmProviderRequest request) {
        return aiConfigService.testProvider(request);
    }

    @GetMapping("/providers/{providerCode}/detail")
    public LlmProviderDetailView getProviderDetail(@PathVariable String providerCode) {
        return aiConfigService.getProviderDetail(providerCode);
    }

    @GetMapping("/status")
    public Map<String, Boolean> status() {
        return Map.of(
                "ready", aiConfigService.aiReady(),
                "maskProviderApiKey", aiConfigService.maskProviderApiKey());
    }

    @PutMapping("/providers/{providerCode}")
    public LlmProviderView updateProvider(
            @PathVariable String providerCode,
            @RequestBody UpdateLlmProviderRequest request) {
        return aiConfigService.updateProvider(providerCode, request);
    }

    @PutMapping("/providers/{providerCode}/detail")
    public LlmProviderView saveProviderDetail(
            @PathVariable String providerCode,
            @RequestBody SaveLlmProviderRequest request) {
        SaveLlmProviderRequest payload = new SaveLlmProviderRequest(
                providerCode,
                request.providerName(),
                request.apiType(),
                request.baseUrl(),
                request.apiKey(),
                request.enabled(),
                request.defaultProvider(),
                request.defaultModelId(),
                request.models());
        return aiConfigService.saveProviderDetail(payload);
    }

    @PostMapping("/providers")
    public LlmProviderView createProvider(@RequestBody CreateLlmProviderRequest request) {
        return aiConfigService.createProvider(request);
    }

    @PutMapping("/providers/{providerCode}/default")
    public LlmProviderView setDefaultProvider(@PathVariable String providerCode) {
        return aiConfigService.setDefaultProvider(providerCode);
    }

    @PostMapping("/providers/fetch-models")
    public List<LlmModelView> fetchModels(@RequestBody FetchLlmModelsRequest request) {
        return aiConfigService.fetchModels(request);
    }
}
