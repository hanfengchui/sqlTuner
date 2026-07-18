package com.codex.sqltuner.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class ConfigurableLlmClientStreamTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void streamsOnlyContentAndCountsReasoningProgressUntilDone() throws Exception {
        startServer(exchange -> write(exchange, 200, "text/event-stream",
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\"}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"content\":\"B\"}}]}\n\n"
                        + "data: [DONE]\n\n"));
        ConfigurableLlmClient client = client();
        List<String> contents = new ArrayList<String>();
        List<Integer> received = new ArrayList<Integer>();

        LlmResponse response = client.analyze(new LlmRequest("system", "user", false), new LlmStreamListener() {
            @Override
            public void onContent(String accumulatedContent, int receivedChars) {
                contents.add(accumulatedContent);
                received.add(receivedChars);
            }
        });

        assertThat(response.getContent()).isEqualTo("AB");
        assertThat(contents).contains("", "A", "AB");
        assertThat(contents).doesNotContain("thinking");
        assertThat(received.get(0)).isGreaterThan(0);
        assertThat(received.get(received.size() - 1)).isEqualTo("thinkingAB".length());
    }

    @Test
    void fallsBackWhenProviderIgnoresStreamAndReturnsPlainJson() throws Exception {
        startServer(exchange -> write(exchange, 200, "application/json",
                "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}"));
        ConfigurableLlmClient client = client();
        List<String> contents = new ArrayList<String>();

        LlmResponse response = client.analyze(new LlmRequest("system", "user", false), new LlmStreamListener() {
            @Override
            public void onContent(String accumulatedContent, int receivedChars) {
                contents.add(accumulatedContent);
            }
        });

        assertThat(response.getContent()).isEqualTo("{\"ok\":true}");
        assertThat(contents).containsExactly("{\"ok\":true}");
    }

    @Test
    void acceptsAStopFinishReasonWhenProviderOmitsDoneSentinel() throws Exception {
        startServer(exchange -> write(exchange, 200, "text/event-stream",
                "data: {\"choices\":[{\"delta\":{\"content\":\"A\"},\"finish_reason\":null}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"));

        LlmResponse response = client().analyze(new LlmRequest("system", "user", false), new LlmStreamListener() {
            @Override
            public void onContent(String accumulatedContent, int receivedChars) {
            }
        });

        assertThat(response.getContent()).isEqualTo("A");
    }

    @Test
    void rejectsEarlyEofInsteadOfRepairingPartialJson() throws Exception {
        startServer(exchange -> write(exchange, 200, "text/event-stream",
                "data: {\"choices\":[{\"delta\":{\"content\":\"{partial\"}}]}\n\n"));

        assertThatThrownBy(() -> client().analyze(new LlmRequest("system", "user", false), new LlmStreamListener() {
            @Override
            public void onContent(String accumulatedContent, int receivedChars) {
            }
        })).isInstanceOf(LlmCallException.class)
                .hasMessageContaining("提前结束")
                .hasMessageNotContaining("partial");
    }

    @Test
    void rejectsStreamErrorEnvelopeWithoutEchoingProviderMessage() throws Exception {
        startServer(exchange -> write(exchange, 200, "text/event-stream",
                "data: {\"error\":{\"code\":\"rate_limit_exceeded\",\"message\":\"sensitive prompt text\"}}\n\n"));

        assertThatThrownBy(() -> client().analyze(new LlmRequest("system", "user", false), new LlmStreamListener() {
            @Override
            public void onContent(String accumulatedContent, int receivedChars) {
            }
        })).isInstanceOf(LlmCallException.class)
                .hasMessageContaining("rate_limit_exceeded")
                .hasMessageNotContaining("sensitive prompt text");
    }

    @Test
    void marksTransientStreamErrorEnvelopeAsRetryable() throws Exception {
        startServer(exchange -> write(exchange, 200, "text/event-stream",
                "data: {\"error\":{\"code\":\"server_error\",\"message\":\"temporary\"}}\n\n"));

        Throwable thrown = catchThrowable(() -> client().analyze(
                new LlmRequest("system", "user", false),
                new LlmStreamListener() {
                    @Override
                    public void onContent(String accumulatedContent, int receivedChars) {
                    }
                }));

        assertThat(thrown).isInstanceOf(LlmCallException.class);
        assertThat(((LlmCallException) thrown).isRetryable()).isTrue();
    }

    @Test
    void malformedChunkErrorDoesNotEchoReasoningOrPayload() throws Exception {
        startServer(exchange -> write(exchange, 200, "text/event-stream",
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"SECRET_REASONING\"}}]\n\n"));

        assertThatThrownBy(() -> client().analyze(new LlmRequest("system", "user", false), new LlmStreamListener() {
            @Override
            public void onContent(String accumulatedContent, int receivedChars) {
            }
        })).isInstanceOf(LlmCallException.class)
                .hasMessageContaining("payloadLength")
                .hasMessageNotContaining("SECRET_REASONING");
    }

    @Test
    void failsOnNonSuccessStreamResponse() throws Exception {
        startServer(exchange -> write(exchange, 401, "application/json",
                "{\"code\":\"InvalidApiKey\",\"message\":\"bad key\"}"));
        ConfigurableLlmClient client = client();

        assertThatThrownBy(() -> client.analyze(new LlmRequest("system", "user", false), new LlmStreamListener() {
            @Override
            public void onContent(String accumulatedContent, int receivedChars) {
            }
        })).isInstanceOf(LlmCallException.class)
                .hasMessageContaining("模型流式调用失败");
    }

    private ConfigurableLlmClient client() {
        LlmProperties properties = new LlmProperties();
        properties.setProvider("openai");
        properties.setApiKey("test-key");
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setModel("test-model");
        properties.setTimeoutMs(5000);
        return new ConfigurableLlmClient(properties, new ObjectMapper());
    }

    private void startServer(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", handler);
        server.start();
    }

    private static void write(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
