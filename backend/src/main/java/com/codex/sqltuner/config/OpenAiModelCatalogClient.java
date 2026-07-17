package com.codex.sqltuner.config;

import com.codex.sqltuner.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class OpenAiModelCatalogClient implements ModelCatalogClient {
    private static final int MAX_MODELS = 500;
    private final ObjectMapper objectMapper;

    public OpenAiModelCatalogClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ModelCatalogView discover(String baseUrl, String apiKey, int timeoutMs) {
        String endpoint = modelsEndpoint(baseUrl);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int boundedTimeout = Math.max(1000, Math.min(timeoutMs, 15000));
        factory.setConnectTimeout(boundedTimeout);
        factory.setReadTimeout(boundedTimeout);
        RestTemplate restTemplate = new RestTemplate(factory);

        HttpHeaders headers = new HttpHeaders();
        if (hasText(apiKey)) {
            headers.setBearerAuth(apiKey.trim());
        }
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    endpoint,
                    HttpMethod.GET,
                    new HttpEntity<Void>(headers),
                    String.class);
            List<String> models = extractModels(response.getBody());
            if (models.isEmpty()) {
                throw new ApiException(502, "MODEL_CATALOG_EMPTY", "接口返回成功，但没有识别到 OpenAI-compatible 模型列表");
            }
            return new ModelCatalogView(endpoint, models);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(502, "MODEL_CATALOG_FAILED", "读取模型列表失败: " + safeMessage(e));
        }
    }

    String modelsEndpoint(String baseUrl) {
        if (!hasText(baseUrl)) {
            throw new ApiException(400, "MODEL_BASE_URL_REQUIRED", "请先填写 OpenAI-compatible Base URL");
        }
        String normalized = baseUrl.trim().replaceAll("/+$", "");
        if (normalized.endsWith("/chat/completions")) {
            normalized = normalized.substring(0, normalized.length() - "/chat/completions".length());
        }
        String endpoint = normalized.endsWith("/models") ? normalized : normalized + "/models";
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "MODEL_BASE_URL_INVALID", "Base URL 不是合法地址");
        }
        String scheme = uri.getScheme();
        if (scheme == null || uri.getHost() == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new ApiException(400, "MODEL_BASE_URL_INVALID", "Base URL 仅支持 http 或 https 地址");
        }
        return endpoint;
    }

    List<String> extractModels(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody == null ? "" : responseBody);
        JsonNode items = root.isArray() ? root : root.path("data");
        if (!items.isArray()) {
            items = root.path("models");
        }
        Set<String> unique = new LinkedHashSet<String>();
        if (items.isArray()) {
            for (JsonNode item : items) {
                String id = item.isTextual() ? item.asText() : firstText(item, "id", "name", "model");
                if (hasText(id)) {
                    unique.add(id.trim());
                }
                if (unique.size() >= MAX_MODELS) {
                    break;
                }
            }
        }
        List<String> models = new ArrayList<String>(unique);
        Collections.sort(models, String.CASE_INSENSITIVE_ORDER);
        return models;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("");
            if (hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        if (!hasText(message)) {
            return error.getClass().getSimpleName();
        }
        return message.length() <= 180 ? message : message.substring(0, 180) + "...";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
