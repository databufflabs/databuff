package com.databuff.apm.web.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class LlmCatalogService {

    private static final String API_TYPE_ANTHROPIC = "anthropic-messages";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public List<LlmModelView> fetchModels(FetchLlmModelsRequest request) {
        if (request.baseUrl() == null || request.baseUrl().isBlank()) {
            throw new IllegalArgumentException("baseUrl is required");
        }
        if (API_TYPE_ANTHROPIC.equals(normalizeApiType(request.apiType()))) {
            throw new IllegalArgumentException(
                    "Anthropic 兼容接口不支持统一 /models 目录，请手工添加模型");
        }
        String modelsUrl = LlmChatModelFactory.buildOpenAiModelsUrl(request.baseUrl());
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(modelsUrl))
                .timeout(Duration.ofSeconds(30))
                .GET();
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + request.apiKey().trim());
        }
        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("模型探测失败: HTTP " + response.statusCode());
            }
            return parseModelList(response.body());
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("模型探测失败: "
                    + (e.getMessage() == null ? "unknown error" : e.getMessage()), e);
        }
    }

    private List<LlmModelView> parseModelList(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode items = null;
        if (root.isArray()) {
            items = root;
        } else if (root.has("data") && root.get("data").isArray()) {
            items = root.get("data");
        } else if (root.has("models") && root.get("models").isArray()) {
            items = root.get("models");
        }
        if (items == null || !items.isArray()) {
            throw new IllegalStateException("提供商响应中未识别到模型列表");
        }
        List<LlmModelView> results = new ArrayList<>();
        for (JsonNode item : items) {
            String modelId = firstText(item, "id", "name");
            if (modelId == null || modelId.isBlank()) {
                continue;
            }
            String displayName = firstText(item, "display_name", "name");
            if (displayName == null || displayName.isBlank()) {
                displayName = modelId;
            }
            results.add(new LlmModelView(modelId.trim(), displayName.trim(), null, null, List.of(), false));
        }
        results.sort(Comparator.comparing(LlmModelView::modelId, String.CASE_INSENSITIVE_ORDER));
        return results;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private String normalizeApiType(String apiType) {
        if (apiType == null || apiType.isBlank()) {
            return "openai-completions";
        }
        return apiType.trim();
    }
}
