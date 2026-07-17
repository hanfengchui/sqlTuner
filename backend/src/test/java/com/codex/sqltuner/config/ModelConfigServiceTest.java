package com.codex.sqltuner.config;

import com.codex.sqltuner.llm.LlmProperties;
import com.codex.sqltuner.llm.ConfigurableLlmClient;
import com.codex.sqltuner.storage.CryptoSupport;
import com.codex.sqltuner.storage.JdbcTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class ModelConfigServiceTest {
    @Test
    void updateStoresApiKeyWithoutReturningPlaintext() {
        LlmProperties properties = new LlmProperties();
        ObjectMapper objectMapper = objectMapper();
        JdbcTemplate jdbcTemplate = JdbcTestSupport.jdbcTemplate();
        ModelConfigService service = service(jdbcTemplate, properties, objectMapper);
        ModelConfigUpdateRequest request = new ModelConfigUpdateRequest();
        request.setProvider("dashscope");
        request.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        request.setModel("qwen-plus");
        request.setVisionModel("qwen-vl-max");
        request.setApiKey("demo-secret-key");
        request.setTimeoutMs(30000);

        ModelConfigView view = service.update(request);

        assertThat(view.isApiKeyConfigured()).isTrue();
        assertThat(properties.getApiKey()).isEqualTo("demo-secret-key");
        assertThat(properties.getVisionModel()).isEqualTo("qwen-vl-max");
        assertThat(ModelConfigView.class.getDeclaredFields())
                .extracting("name")
                .doesNotContain("apiKey");
    }

    @Test
    void healthViewMarksRealProviderWithoutKeyAsMisconfigured() {
        LlmProperties properties = new LlmProperties();
        properties.setProvider("dashscope");
        ObjectMapper objectMapper = objectMapper();
        JdbcTemplate jdbcTemplate = JdbcTestSupport.jdbcTemplate();
        jdbcTemplate.update("UPDATE model_config SET provider = 'dashscope', model = 'qwen-plus', encrypted_api_key = NULL WHERE id = 1");
        ModelConfigService service = service(jdbcTemplate, properties, objectMapper);

        RuntimeHealthView health = service.healthView();

        assertThat(health.getProvider()).isEqualTo("dashscope");
        assertThat(health.getMockState()).isEqualTo("missing-key");
        assertThat(health.isApiKeyConfigured()).isFalse();
    }

    private ModelConfigService service(JdbcTemplate jdbcTemplate, LlmProperties properties, ObjectMapper objectMapper) {
        return new ModelConfigService(
                jdbcTemplate,
                properties,
                new ConfigurableLlmClient(properties, objectMapper),
                new CryptoSupport(),
                (baseUrl, apiKey, timeoutMs) -> new ModelCatalogView(baseUrl + "/models", java.util.Arrays.asList("model-a")));
    }

    private ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return objectMapper;
    }
}
