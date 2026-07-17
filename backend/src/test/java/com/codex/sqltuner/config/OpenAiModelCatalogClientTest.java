package com.codex.sqltuner.config;

import com.codex.sqltuner.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiModelCatalogClientTest {
    private final OpenAiModelCatalogClient client = new OpenAiModelCatalogClient(new ObjectMapper());

    @Test
    void normalizesChatCompletionUrlToModelsEndpoint() {
        assertThat(client.modelsEndpoint("https://gateway.example.com/v1/chat/completions"))
                .isEqualTo("https://gateway.example.com/v1/models");
        assertThat(client.modelsEndpoint("https://gateway.example.com/v1/"))
                .isEqualTo("https://gateway.example.com/v1/models");
    }

    @Test
    void readsStandardAndCompatibleModelCatalogShapes() throws Exception {
        assertThat(client.extractModels("{\"data\":[{\"id\":\"gpt-4.1\"},{\"id\":\"qwen-max\"}]}"))
                .containsExactly("gpt-4.1", "qwen-max");
        assertThat(client.extractModels("{\"models\":[\"glm-4.5\",{\"name\":\"deepseek-v3\"}]}"))
                .containsExactly("deepseek-v3", "glm-4.5");
    }

    @Test
    void rejectsNonHttpEndpoints() {
        assertThatThrownBy(() -> client.modelsEndpoint("file:///etc/passwd"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("http");
    }
}
