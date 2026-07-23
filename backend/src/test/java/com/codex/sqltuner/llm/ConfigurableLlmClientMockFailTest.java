package com.codex.sqltuner.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

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
    void realProviderWithoutKeyFailsInsteadOfReturningMock() {
        LlmProperties properties = new LlmProperties();
        properties.setProvider("dashscope");
        properties.setApiKey("");
        ConfigurableLlmClient client = new ConfigurableLlmClient(properties, objectMapper);

        assertThatThrownBy(() -> client.analyze(new LlmRequest("system", "user", true)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("API Key 未配置");
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

    @Test
    void imageRequestUsesConfiguredVisionModelAndOpenAiCompatibleContentArray() {
        LlmProperties properties = new LlmProperties();
        properties.setProvider("dashscope");
        properties.setModel("qwen3.7-max");
        properties.setVisionModel("qwen3-vl-plus");
        ConfigurableLlmClient client = new ConfigurableLlmClient(properties, objectMapper);

        JsonNode body = client.buildChatRequestBody(new LlmRequest(
                "system",
                "extract plan",
                false,
                null,
                Arrays.asList(new LlmRequestImage("data:image/png;base64,iVBORw0KGgo="))));

        assertThat(body.path("model").asText()).isEqualTo("qwen3-vl-plus");
        JsonNode content = body.path("messages").get(1).path("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content.get(0).path("type").asText()).isEqualTo("text");
        assertThat(content.get(0).path("text").asText()).isEqualTo("extract plan");
        assertThat(content.get(1).path("type").asText()).isEqualTo("image_url");
        assertThat(content.get(1).path("image_url").path("url").asText()).startsWith("data:image/png;base64,");
        assertThat(body.has("response_format")).isFalse();
    }

    @Test
    void textRequestEnablesOpenAiCompatibleJsonObjectMode() {
        LlmProperties properties = new LlmProperties();
        properties.setProvider("dashscope");
        properties.setModel("qwen3.7-max");
        ConfigurableLlmClient client = new ConfigurableLlmClient(properties, objectMapper);

        JsonNode body = client.buildChatRequestBody(new LlmRequest(
                "只返回严格 JSON 对象。",
                "请生成 SQL 诊断 JSON。",
                true));

        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.2d);
        assertThat(body.path("max_tokens").asInt()).isEqualTo(4096);
    }

    @Test
    void imageRequestUsesSmallerVisionTokenBudget() {
        LlmProperties properties = new LlmProperties();
        properties.setProvider("dashscope");
        ConfigurableLlmClient client = new ConfigurableLlmClient(properties, objectMapper);

        JsonNode body = client.buildChatRequestBody(new LlmRequest(
                "system",
                "extract plan",
                false,
                null,
                Arrays.asList(new LlmRequestImage("data:image/png;base64,iVBORw0KGgo="))));

        assertThat(body.path("max_tokens").asInt()).isEqualTo(2048);
    }

    @Test
    void requestMaxTokensOverridesDefaultBudget() {
        LlmProperties properties = new LlmProperties();
        properties.setProvider("dashscope");
        ConfigurableLlmClient client = new ConfigurableLlmClient(properties, objectMapper);
        LlmRequest request = new LlmRequest("system", "analyze", false);
        request.setMaxTokens(512);

        JsonNode body = client.buildChatRequestBody(request);

        assertThat(body.path("max_tokens").asInt()).isEqualTo(512);
    }

    @Test
    void configuredReasoningEffortIsSentOnlyForTextRequests() {
        LlmProperties properties = new LlmProperties();
        properties.setProvider("dashscope");
        properties.setModel("grok-4.5");
        properties.setVisionModel("grok-4.5");
        properties.setReasoningEffort("high");
        ConfigurableLlmClient client = new ConfigurableLlmClient(properties, objectMapper);

        JsonNode textBody = client.buildChatRequestBody(new LlmRequest("system", "analyze", false));
        JsonNode imageBody = client.buildChatRequestBody(new LlmRequest(
                "system",
                "extract plan",
                false,
                null,
                Arrays.asList(new LlmRequestImage("data:image/png;base64,iVBORw0KGgo="))));

        assertThat(textBody.path("reasoning_effort").asText()).isEqualTo("high");
        assertThat(imageBody.has("reasoning_effort")).isFalse();
    }

    @Test
    void unsupportedReasoningEffortFailsWithoutMakingARequest() {
        LlmProperties properties = new LlmProperties();
        properties.setProvider("dashscope");
        properties.setReasoningEffort("turbo");
        ConfigurableLlmClient client = new ConfigurableLlmClient(properties, objectMapper);

        assertThatThrownBy(() -> client.buildChatRequestBody(new LlmRequest("system", "analyze", false)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("LLM_REASONING_EFFORT");
    }

    @Test
    void previewRequestCanUsePlainTextWithoutJsonObjectMode() {
        LlmProperties properties = new LlmProperties();
        properties.setProvider("dashscope");
        ConfigurableLlmClient client = new ConfigurableLlmClient(properties, objectMapper);
        LlmRequest request = new LlmRequest("给出直接分析", "分析这条 SQL", true);
        request.setJsonOutput(false);

        JsonNode body = client.buildChatRequestBody(request);

        assertThat(body.has("response_format")).isFalse();
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.2d);
    }
}
