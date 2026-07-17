package com.codex.sqltuner.config;

import com.codex.sqltuner.llm.LlmClient;
import com.codex.sqltuner.llm.LlmRequest;
import com.codex.sqltuner.llm.LlmResponse;
import com.codex.sqltuner.storage.CryptoSupport;
import com.codex.sqltuner.storage.ModelConfigRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Service
public class ModelConfigService {
    private final JdbcTemplate jdbcTemplate;
    private final com.codex.sqltuner.llm.LlmProperties llmProperties;
    private final LlmClient llmClient;
    private final CryptoSupport cryptoSupport;

    public ModelConfigService(JdbcTemplate jdbcTemplate,
                              com.codex.sqltuner.llm.LlmProperties llmProperties,
                              LlmClient llmClient,
                              CryptoSupport cryptoSupport) {
        this.jdbcTemplate = jdbcTemplate;
        this.llmProperties = llmProperties;
        this.llmClient = llmClient;
        this.cryptoSupport = cryptoSupport;
    }

    public ModelConfigView view() {
        ModelConfigRecord record = runtime();
        return new ModelConfigView(
                record.getProvider(),
                record.getBaseUrl(),
                record.getModel(),
                record.getTimeoutMs(),
                hasApiKey(record),
                mockState(record)
        );
    }

    public RuntimeHealthView healthView() {
        ModelConfigRecord record = runtime();
        return new RuntimeHealthView(
                record.getProvider(),
                record.getModel(),
                mockState(record),
                hasApiKey(record)
        );
    }

    public ModelConfigRecord runtime() {
        ModelConfigRecord record = jdbcTemplate.queryForObject(
                "SELECT * FROM model_config WHERE id = 1",
                new org.springframework.jdbc.core.RowMapper<ModelConfigRecord>() {
                    @Override
                    public ModelConfigRecord mapRow(ResultSet rs, int rowNum) throws java.sql.SQLException {
                        ModelConfigRecord record = new ModelConfigRecord(
                                rs.getString("provider"),
                                rs.getString("base_url"),
                                rs.getString("model"),
                                rs.getInt("timeout_ms"));
                        String encrypted = rs.getString("encrypted_api_key");
                        String decrypted = encrypted == null || encrypted.trim().isEmpty() ? "" : cryptoSupport.decrypt(encrypted);
                        if (cryptoSupport.isEncrypted(encrypted) && decrypted == null) {
                            throw new IllegalStateException("SQL_TUNER_DATA_KEY 无法解密数据库中的模型 API Key");
                        }
                        record.setApiKey(decrypted);
                        return record;
                    }
                });
        if (record != null) {
            llmProperties.apply(record);
        }
        return record;
    }

    public List<ModelProviderOption> providers() {
        return Arrays.asList(
                new ModelProviderOption("dashscope", "阿里云百炼 / 千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", true),
                new ModelProviderOption("openai-compatible", "OpenAI 兼容网关", "https://your-gateway.example.com/v1", "qwen-plus", true),
                new ModelProviderOption("mock", "本地 Mock（不调用模型）", "", "mock", false)
        );
    }

    public ModelConfigView update(final ModelConfigUpdateRequest request) {
        ModelConfigRecord record = runtime();
        if (request.getProvider() != null && !request.getProvider().trim().isEmpty()) {
            record.setProvider(request.getProvider().trim());
        }
        if (request.getBaseUrl() != null && !request.getBaseUrl().trim().isEmpty()) {
            record.setBaseUrl(request.getBaseUrl().trim());
        }
        if (request.getModel() != null && !request.getModel().trim().isEmpty()) {
            record.setModel(request.getModel().trim());
        }
        if (request.getApiKey() != null && !request.getApiKey().trim().isEmpty()) {
            record.setApiKey(request.getApiKey().trim());
        }
        if (request.getTimeoutMs() != null && request.getTimeoutMs() > 0) {
            record.setTimeoutMs(request.getTimeoutMs());
        }
        jdbcTemplate.update(
                "UPDATE model_config SET provider = ?, base_url = ?, model = ?, encrypted_api_key = ?, timeout_ms = ?, updated_at = ? WHERE id = 1",
                record.getProvider(),
                record.getBaseUrl(),
                record.getModel(),
                record.getApiKey() == null || record.getApiKey().trim().isEmpty() ? null : cryptoSupport.encrypt(record.getApiKey().trim()),
                record.getTimeoutMs(),
                Timestamp.valueOf(LocalDateTime.now()));
        llmProperties.apply(record);
        return view();
    }

    public ModelTestResult testConnection() {
        ModelTestResult result = new ModelTestResult();
        ModelConfigRecord record = runtime();
        result.setProvider(record.getProvider());
        result.setModel(record.getModel());
        long start = System.currentTimeMillis();
        try {
            LlmResponse response = llmClient.analyze(new LlmRequest(
                    "你是连接测试助手。只输出一个 JSON 对象。",
                    "请只返回 {\"ok\":true,\"message\":\"pong\"}，不要解释。",
                    false
            ));
            result.setElapsedMs(response.getElapsedMs());
            result.setProvider(response.getProvider());
            result.setModel(response.getModel());
            result.setMock(response.isMock());
            result.setSuccess(!response.isMock());
            result.setMessage(response.isMock() ? "当前明确配置为 mock，未调用真实模型。" : "真实模型调用成功。");
            result.setSample(response.getContent() == null ? "" : abbreviate(response.getContent(), 240));
        } catch (Exception e) {
            result.setElapsedMs(System.currentTimeMillis() - start);
            result.setSuccess(false);
            result.setMessage("连接测试失败: " + e.getMessage());
        }
        return result;
    }

    private boolean hasApiKey(ModelConfigRecord record) {
        return record.getApiKey() != null && !record.getApiKey().trim().isEmpty();
    }

    private String mockState(ModelConfigRecord record) {
        String provider = record.getProvider();
        if (provider != null && "mock".equalsIgnoreCase(provider.trim())) {
            return "mock";
        }
        return hasApiKey(record) ? "ready" : "missing-key";
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}
