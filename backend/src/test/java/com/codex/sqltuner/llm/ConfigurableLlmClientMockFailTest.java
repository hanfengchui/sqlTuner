package com.codex.sqltuner.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C3+C4：mock 输出带显眼标记、真实调用失败抛 LlmCallException 不静默回退、错误信封抛异常。
 */
class ConfigurableLlmClientMockFailTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void configuredMockReturnsMarkedMockContent() throws Exception {
        LlmProperties properties = new LlmProperties();
        properties.setProvider("mock");
        ConfigurableLlmClient client = new ConfigurableLlmClient(properties, objectMapper);

        LlmResponse response = client.analyze(new LlmRequest("system", "user", true));

        assertThat(response.isMock()).isTrue();
        // mock 内容带显眼前缀，不会被误当成真实模型建议。
        assertThat(response.getContent()).contains("【示例·非真实模型输出】");
        assertThat(response.getContent()).contains("请勿直接用于生产");
        // 仍是合法 JSON，前端可解析。
        JsonNode parsed = objectMapper.readTree(response.getContent());
        assertThat(parsed.path("summary").asText()).contains("mock LLM");
    }

    @Test
    void missingKeyReturnsMockNotThrows() {
        // 缺 key 时走配置性 mock，不抛异常（行为兼容），但内容带标记。
        LlmProperties properties = new LlmProperties();
        properties.setProvider("dashscope");
        properties.setApiKey("");
        ConfigurableLlmClient client = new ConfigurableLlmClient(properties, objectMapper);

        LlmResponse response = client.analyze(new LlmRequest("system", "user", true));
        assertThat(response.isMock()).isTrue();
        assertThat(response.getContent()).contains("【示例·非真实模型输出】");
    }

    @Test
    void extractContentThrowsOnEmptyAndErrorEnvelope() {
        // 通过反射不可达，直接用错误信封走真实路径：配一个不可达 baseUrl + 假 key，
        // 调用必抛 LlmCallException（网络失败），不静默回退 mock。
        LlmProperties properties = new LlmProperties();
        properties.setProvider("dashscope");
        properties.setBaseUrl("http://127.0.0.1:9/no-such-server");
        properties.setApiKey("fake-key");
        properties.setTimeoutMs(500);
        ConfigurableLlmClient client = new ConfigurableLlmClient(properties, objectMapper);

        assertThatThrownBy(new org.assertj.core.api.ThrowableAssert.ThrowingCallable() {
            @Override
            public void call() {
                client.analyze(new LlmRequest("system", "user", true));
            }
        })
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("模型调用失败");
    }
}
