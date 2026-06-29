package com.codex.sqltuner.config;

import com.codex.sqltuner.llm.LlmProperties;
import com.codex.sqltuner.llm.ConfigurableLlmClient;
import com.codex.sqltuner.storage.PersistentStateStore;
import com.codex.sqltuner.storage.StorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ModelConfigServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void updateStoresApiKeyWithoutReturningPlaintext() {
        LlmProperties properties = new LlmProperties();
        ObjectMapper objectMapper = objectMapper();
        ModelConfigService service = new ModelConfigService(stateStore(properties, objectMapper), properties, new ConfigurableLlmClient(properties, objectMapper));
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
    void healthViewMarksMissingKeyAsMock() {
        LlmProperties properties = new LlmProperties();
        properties.setProvider("dashscope");
        ObjectMapper objectMapper = objectMapper();
        ModelConfigService service = new ModelConfigService(stateStore(properties, objectMapper), properties, new ConfigurableLlmClient(properties, objectMapper));

        RuntimeHealthView health = service.healthView();

        assertThat(health.getProvider()).isEqualTo("dashscope");
        assertThat(health.getMockState()).isEqualTo("mock");
        assertThat(health.isApiKeyConfigured()).isFalse();
    }

    private PersistentStateStore stateStore(LlmProperties properties, ObjectMapper objectMapper) {
        StorageProperties storageProperties = new StorageProperties();
        storageProperties.setDataDir(tempDir.toString());
        PersistentStateStore stateStore = new PersistentStateStore(storageProperties, properties, objectMapper, new com.codex.sqltuner.storage.CryptoSupport());
        stateStore.init();
        return stateStore;
    }

    private ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return objectMapper;
    }
}
