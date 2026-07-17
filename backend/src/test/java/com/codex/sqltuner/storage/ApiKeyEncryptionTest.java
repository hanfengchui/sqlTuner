package com.codex.sqltuner.storage;

import com.codex.sqltuner.config.ModelConfigService;
import com.codex.sqltuner.config.ModelConfigUpdateRequest;
import com.codex.sqltuner.config.ModelConfigView;
import com.codex.sqltuner.config.ModelCatalogView;
import com.codex.sqltuner.llm.ConfigurableLlmClient;
import com.codex.sqltuner.llm.LlmProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;


import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 API Key 加密落盘：磁盘上不含明文 key，重启后能解密回用，前端视图不回传 key。
 */
class ApiKeyEncryptionTest {
    @Test
    void apiKeyIsEncryptedOnDiskAndDecryptableAfterRestart() throws Exception {
        ObjectMapper objectMapper = objectMapper();
        LlmProperties properties = new LlmProperties();
        JdbcTemplate jdbcTemplate = JdbcTestSupport.jdbcTemplate();
        ModelConfigService service = service(jdbcTemplate, properties, objectMapper);

        ModelConfigUpdateRequest request = new ModelConfigUpdateRequest();
        request.setProvider("dashscope");
        request.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        request.setModel("qwen-plus");
        request.setApiKey("sk-super-secret-123456");
        request.setTimeoutMs(30000);
        ModelConfigView view = service.update(request);

        // 视图不回传明文 key。
        assertThat(view.isApiKeyConfigured()).isTrue();
        assertThat(ModelConfigView.class.getDeclaredFields()).extracting("name").doesNotContain("apiKey");

        // DB 中不含明文 key。
        String stored = jdbcTemplate.queryForObject("SELECT encrypted_api_key FROM model_config WHERE id = 1", String.class);
        assertThat(stored).doesNotContain("sk-super-secret-123456");
        assertThat(stored).contains("enc:v1:");

        // 重启后能解密回用：runtime() 返回的 apiKey 是明文。
        ModelConfigService restartedService = service(jdbcTemplate, properties, objectMapper);
        ModelConfigRecord runtime = restartedService.runtime();
        assertThat(runtime.getApiKey()).isEqualTo("sk-super-secret-123456");
    }

    private ModelConfigService service(JdbcTemplate jdbcTemplate, LlmProperties properties, ObjectMapper objectMapper) {
        return new ModelConfigService(
                jdbcTemplate,
                properties,
                new ConfigurableLlmClient(properties, objectMapper),
                new CryptoSupport(),
                (baseUrl, apiKey, timeoutMs) -> new ModelCatalogView(baseUrl + "/models", java.util.Arrays.asList("model-a")));
    }

    @Test
    void encryptIsIdempotentAndDecryptRoundTrips() {
        CryptoSupport crypto = new CryptoSupport();
        String cipher = crypto.encrypt("hello-key");
        assertThat(crypto.isEncrypted(cipher)).isTrue();
        assertThat(crypto.encrypt(cipher)).isEqualTo(cipher); // 已加密不再重复加密
        assertThat(crypto.decrypt(cipher)).isEqualTo("hello-key");
        // 空值不加密
        assertThat(crypto.encrypt(null)).isNull();
        assertThat(crypto.encrypt("")).isEmpty();
        // 历史明文兼容：decrypt 遇到非加密前缀原样返回
        assertThat(crypto.decrypt("legacy-plain-key")).isEqualTo("legacy-plain-key");
    }

    private ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return objectMapper;
    }
}
