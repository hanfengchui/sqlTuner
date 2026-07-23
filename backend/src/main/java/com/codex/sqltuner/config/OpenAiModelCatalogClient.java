package com.codex.sqltuner.config;

import com.codex.sqltuner.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClients;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class OpenAiModelCatalogClient implements ModelCatalogClient {
    private static final int MAX_MODELS = 500;
    private final ObjectMapper objectMapper;
    private final ModelEndpointPolicy endpointPolicy;

    public OpenAiModelCatalogClient(ObjectMapper objectMapper) {
        this(objectMapper, new ModelEndpointPolicy(false));
    }

    @Autowired
    public OpenAiModelCatalogClient(ObjectMapper objectMapper, ModelEndpointPolicy endpointPolicy) {
        this.objectMapper = objectMapper;
        this.endpointPolicy = endpointPolicy;
    }

    @Override
    public ModelCatalogView discover(String baseUrl, String apiKey, int timeoutMs) {
        String endpoint = modelsEndpoint(baseUrl);
        int boundedTimeout = Math.max(1000, Math.min(timeoutMs, 15000));
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(boundedTimeout)
                .setSocketTimeout(boundedTimeout)
                .setConnectionRequestTimeout(boundedTimeout)
                .build();
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(
                HttpClients.custom()
                        .setDefaultRequestConfig(requestConfig)
                        .setDnsResolver(endpointPolicy.dnsResolver())
                        .disableCookieManagement()
                        .disableRedirectHandling()
                        .build());
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
        return endpointPolicy.modelsEndpoint("openai-compatible", baseUrl);
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
