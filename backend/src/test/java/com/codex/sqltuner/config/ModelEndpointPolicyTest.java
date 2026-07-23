package com.codex.sqltuner.config;

import com.codex.sqltuner.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelEndpointPolicyTest {
    @Test
    void normalizesBaseUrlAndDerivesBoundEndpoints() {
        ModelEndpointPolicy policy = new ModelEndpointPolicy(true);

        ModelEndpointPolicy.Endpoint endpoint = policy.normalizeBaseUrl(
                "DashScope",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions/");

        assertThat(endpoint.getBaseUrl()).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThat(endpoint.getModelsUrl()).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1/models");
        assertThat(endpoint.getChatCompletionsUrl()).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions");
        assertThat(endpoint.getApiKeyBinding()).isEqualTo("dashscope@dashscope.aliyuncs.com");
    }

    @Test
    void productionRejectsHttpUserInfoAndLocalDestinations() {
        ModelEndpointPolicy policy = new ModelEndpointPolicy(true);

        assertThatThrownBy(() -> policy.normalizeBaseUrl("dashscope", "http://api.example.com/v1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> policy.normalizeBaseUrl("dashscope", "https://token@api.example.com/v1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("用户信息");
        assertThatThrownBy(() -> policy.normalizeBaseUrl("dashscope", "https://localhost/v1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("localhost");
        assertThatThrownBy(() -> policy.normalizeBaseUrl("dashscope", "https://127.0.0.1/v1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("内网");
        assertThatThrownBy(() -> policy.normalizeBaseUrl("dashscope", "https://169.254.169.254/latest"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("内网");
    }

    @Test
    void developmentModeAllowsLocalHttpForTests() {
        ModelEndpointPolicy policy = new ModelEndpointPolicy(false);

        assertThat(policy.normalizeBaseUrl("test", "http://localhost:18080/v1").getChatCompletionsUrl())
                .isEqualTo("http://localhost:18080/v1/chat/completions");
    }

    @Test
    void apiKeyBindingMustMatchNormalizedProviderAndHost() {
        ModelEndpointPolicy policy = new ModelEndpointPolicy(false);
        ModelEndpointPolicy.Endpoint endpoint = policy.normalizeBaseUrl("dashscope", "https://dashscope.aliyuncs.com/v1");

        assertThatThrownBy(() -> policy.requireApiKeyBinding(endpoint, "dashscope@gateway.example.com"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("重新填写模型 API Key");
    }
}
