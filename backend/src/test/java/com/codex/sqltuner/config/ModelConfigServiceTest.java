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
        ModelConfigService service = new ModelConfigService(jdbcTemplate, properties, new ConfigurableLlmClient(properties, objectMapper), new CryptoSupport());
        ModelConfigUpdateRequest request = new ModelConfigUpdateRequest();
        request.setProvider("dashscope");
        request.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        request.setModel("qwen-plus");
        request.setApiKey("demo-secret-key");
        request.setTimeoutMs(30000);

        ModelConfigView view = service.update(request);

        assertThat(view.isApiKeyConfigured()).isTrue();
        assertThat(properties.getApiKey()).isEqualTo("demo-secret-key");
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
        ModelConfigService service = new ModelConfigService(jdbcTemplate, properties, new ConfigurableLlmClient(properties, objectMapper), new CryptoSupport());

        RuntimeHealthView health = service.healthView();

        assertThat(health.getProvider()).isEqualTo("dashscope");
        assertThat(health.getMockState()).isEqualTo("missing-key");
        assertThat(health.isApiKeyConfigured()).isFalse();
    }

    private ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return objectMapper;
    }
}
