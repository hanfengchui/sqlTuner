package com.codex.sqltuner.storage;

import com.codex.sqltuner.config.ModelEndpointPolicy;
import com.codex.sqltuner.llm.LlmProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseBootstrapTest {
    @Test
    void startupBackfillsBindingForExistingEncryptedModelKey() {
        JdbcTemplate jdbcTemplate = JdbcTestSupport.jdbcTemplate();
        CryptoSupport cryptoSupport = new CryptoSupport();
        jdbcTemplate.update(
                "UPDATE model_config SET provider = 'dashscope', base_url = 'https://dashscope.aliyuncs.com/compatible-mode/v1/', encrypted_api_key = ?, api_key_binding = NULL WHERE id = 1",
                cryptoSupport.encrypt("legacy-key"));
        LlmProperties properties = new LlmProperties();
        DatabaseBootstrap bootstrap = new DatabaseBootstrap(
                jdbcTemplate,
                new BCryptPasswordEncoder(4),
                properties,
                cryptoSupport,
                new ModelEndpointPolicy(true));

        bootstrap.init();

        assertThat(jdbcTemplate.queryForObject("SELECT base_url FROM model_config WHERE id = 1", String.class))
                .isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThat(jdbcTemplate.queryForObject("SELECT api_key_binding FROM model_config WHERE id = 1", String.class))
                .isEqualTo("dashscope@dashscope.aliyuncs.com");
        assertThat(properties.getApiKey()).isEqualTo("legacy-key");
    }

    @Test
    void startupLeavesUnsafeExistingModelKeyUnbound() {
        JdbcTemplate jdbcTemplate = JdbcTestSupport.jdbcTemplate();
        CryptoSupport cryptoSupport = new CryptoSupport();
        jdbcTemplate.update(
                "UPDATE model_config SET provider = 'dashscope', base_url = 'http://127.0.0.1:18080/v1', encrypted_api_key = ?, api_key_binding = NULL WHERE id = 1",
                cryptoSupport.encrypt("legacy-key"));
        LlmProperties properties = new LlmProperties();
        DatabaseBootstrap bootstrap = new DatabaseBootstrap(
                jdbcTemplate,
                new BCryptPasswordEncoder(4),
                properties,
                cryptoSupport,
                new ModelEndpointPolicy(true));

        bootstrap.init();

        assertThat(jdbcTemplate.queryForObject("SELECT api_key_binding FROM model_config WHERE id = 1", String.class))
                .isNull();
        assertThat(properties.getApiKey()).isNull();
    }
}
