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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(jdbcTemplate.queryForObject("SELECT api_key_binding FROM model_config WHERE id = 1", String.class))
                .isEqualTo("dashscope@dashscope.aliyuncs.com");
    }

    @Test
    void baseUrlHostChangeRequiresANewApiKey() {
        LlmProperties properties = new LlmProperties();
        ObjectMapper objectMapper = objectMapper();
        JdbcTemplate jdbcTemplate = JdbcTestSupport.jdbcTemplate();
        ModelConfigService service = service(jdbcTemplate, properties, objectMapper);
        ModelConfigUpdateRequest initial = new ModelConfigUpdateRequest();
        initial.setProvider("dashscope");
        initial.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        initial.setModel("qwen-plus");
        initial.setApiKey("demo-secret-key");
        service.update(initial);

        ModelConfigUpdateRequest hostChange = new ModelConfigUpdateRequest();
        hostChange.setBaseUrl("https://gateway.example.com/v1");

        assertThatThrownBy(() -> service.update(hostChange))
                .hasMessageContaining("必须重新填写模型 API Key");
    }

    @Test
    void modelCatalogDoesNotForwardStoredKeyToDifferentRequestedHost() {
        LlmProperties properties = new LlmProperties();
        ObjectMapper objectMapper = objectMapper();
        JdbcTemplate jdbcTemplate = JdbcTestSupport.jdbcTemplate();
        RecordingCatalogClient catalogClient = new RecordingCatalogClient();
        ModelConfigService service = new ModelConfigService(
                jdbcTemplate,
                properties,
                new ConfigurableLlmClient(properties, objectMapper),
                new CryptoSupport(),
                catalogClient,
                new ModelEndpointPolicy(false));
        ModelConfigUpdateRequest initial = new ModelConfigUpdateRequest();
        initial.setProvider("dashscope");
        initial.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        initial.setModel("qwen-plus");
        initial.setApiKey("demo-secret-key");
        service.update(initial);

        ModelCatalogRequest request = new ModelCatalogRequest();
        request.setBaseUrl("https://gateway.example.com/v1");

        assertThatThrownBy(() -> service.discoverModels(request))
                .hasMessageContaining("必须重新填写模型 API Key");
        assertThat(catalogClient.lastApiKey).isNull();
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

    @Test
    void previewUsesTheConfiguredModelWithoutPersistingATuningTask() {
        LlmProperties properties = new LlmProperties();
        ObjectMapper objectMapper = objectMapper();
        JdbcTemplate jdbcTemplate = JdbcTestSupport.jdbcTemplate();
        ModelConfigService service = service(jdbcTemplate, properties, objectMapper);
        ModelPreviewRequest request = new ModelPreviewRequest();
        request.setSystemPrompt("给出直接分析");
        request.setUserPrompt("select * from orders where id = 1");
        request.setDeepAnalysis(true);
        Integer before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tuning_tasks", Integer.class);

        ModelPreviewResult result = service.preview(request);

        assertThat(result.isMock()).isTrue();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getOutput()).contains("【示例·非真实模型输出】");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tuning_tasks", Integer.class)).isEqualTo(before);
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

    private static class RecordingCatalogClient implements ModelCatalogClient {
        private String lastApiKey;

        @Override
        public ModelCatalogView discover(String baseUrl, String apiKey, int timeoutMs) {
            this.lastApiKey = apiKey;
            return new ModelCatalogView(baseUrl + "/models", java.util.Arrays.asList("model-a"));
        }
    }
}
