package com.codex.sqltuner.llm;

import com.codex.sqltuner.config.QueueProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurableLlmClientConcurrencyTest {
    private HttpServer server;
    private ExecutorService serverExecutor;
    private ExecutorService callerExecutor;

    @AfterEach
    void shutdown() {
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
        if (callerExecutor != null) {
            callerExecutor.shutdownNow();
        }
    }

    @Test
    void allowsTenSimultaneousConnectionsToOneModelEndpoint() throws Exception {
        CountDownLatch tenArrived = new CountDownLatch(10);
        CountDownLatch releaseResponses = new CountDownLatch(1);
        startServer(exchange -> {
            tenArrived.countDown();
            try {
                releaseResponses.await(10, TimeUnit.SECONDS);
                writeSuccess(exchange);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });

        ConfigurableLlmClient client = client(10);
        callerExecutor = Executors.newFixedThreadPool(10);
        List<Future<LlmResponse>> responses = new ArrayList<Future<LlmResponse>>();
        for (int index = 0; index < 10; index++) {
            responses.add(callerExecutor.submit(() -> client.analyze(
                    new LlmRequest("system", "request-" + System.nanoTime(), false))));
        }

        boolean allConnectionsArrived;
        try {
            allConnectionsArrived = tenArrived.await(5, TimeUnit.SECONDS);
        } finally {
            releaseResponses.countDown();
        }
        for (Future<LlmResponse> response : responses) {
            assertThat(response.get(10, TimeUnit.SECONDS).getContent()).isEqualTo("ok");
        }
        assertThat(allConnectionsArrived).isTrue();
    }

    private ConfigurableLlmClient client(int maxConcurrentLlm) {
        LlmProperties properties = new LlmProperties();
        properties.setProvider("openai");
        properties.setApiKey("test-key");
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setModel("test-model");
        properties.setTimeoutMs(10000);
        QueueProperties queueProperties = new QueueProperties();
        queueProperties.setMaxRunning(maxConcurrentLlm);
        return new ConfigurableLlmClient(properties, new ObjectMapper(), queueProperties);
    }

    private void startServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newFixedThreadPool(10);
        server.setExecutor(serverExecutor);
        server.createContext("/chat/completions", handler);
        server.start();
    }

    private static void writeSuccess(HttpExchange exchange) throws IOException {
        byte[] body = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
