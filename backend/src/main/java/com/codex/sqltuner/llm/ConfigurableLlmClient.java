package com.codex.sqltuner.llm;

import com.codex.sqltuner.config.QueueProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResponseExtractor;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
public class ConfigurableLlmClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(ConfigurableLlmClient.class);
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 10000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 30000;
    private static final int DEFAULT_CALL_TIMEOUT_MS = 120000;
    private static final int DEFAULT_TEXT_MAX_TOKENS = 4096;
    private static final int DEFAULT_VISION_MAX_TOKENS = 2048;
    private static final int DEFAULT_MAX_OUTPUT_CHARS = 64 * 1024;
    private static final long STREAM_CALLBACK_MIN_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(250L);
    private static final int STREAM_CALLBACK_CHAR_STEP = 256;
    // mock 不占许可；真实模型并发与持久化 worker 上限保持一致。
    private final int maxConcurrentLlm;
    private final Semaphore llmSlots;
    private final LlmProperties properties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final HttpComponentsClientHttpRequestFactory requestFactory;
    private final ThreadLocal<Integer> activeCallTimeoutMs = new ThreadLocal<Integer>();

    public ConfigurableLlmClient(LlmProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, 4);
    }

    @Autowired
    public ConfigurableLlmClient(LlmProperties properties, ObjectMapper objectMapper, QueueProperties queueProperties) {
        this(properties, objectMapper, Math.max(1, queueProperties.getMaxRunning()));
    }

    private ConfigurableLlmClient(LlmProperties properties, ObjectMapper objectMapper, int maxConcurrentLlm) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.maxConcurrentLlm = maxConcurrentLlm;
        this.llmSlots = new Semaphore(maxConcurrentLlm, true);
        this.requestFactory = buildRequestFactory();
        this.requestFactory.setHttpContextFactory((method, uri) -> requestContext());
        this.restTemplate = new RestTemplate(requestFactory);
    }

    @Override
    public LlmResponse analyze(LlmRequest request) {
        return analyze(request, null);
    }

    @Override
    public LlmResponse analyze(LlmRequest request, LlmStreamListener listener) {
        long start = System.currentTimeMillis();
        int callTimeoutMs = resolveCallTimeoutMs(request);
        String provider = properties.getProvider() == null ? "mock" : properties.getProvider();
        String model = resolveModel(request);
        log.info("analyze request 请求: provider: {}, model: {}, promptLength: {}, deepAnalysis: {}, imageCount: {}",
                provider, model, request.getUserPrompt() == null ? 0 : request.getUserPrompt().length(),
                request.isDeepAnalysis(), request.getImages() == null ? 0 : request.getImages().size());
        // 只有明确选择 provider=mock 才返回标记过的模拟结果。
        if ("mock".equalsIgnoreCase(provider)) {
            applyMockDelay();
            String content = mockResponse(request);
            log.info("analyze response 响应: provider: mock, reason: explicitly-configured, elapsedMs: {}", System.currentTimeMillis() - start);
            return new LlmResponse("mock", properties.getModel(), content, System.currentTimeMillis() - start, true);
        }
        if (properties.getApiKey() == null || properties.getApiKey().trim().isEmpty()) {
            throw new LlmCallException("模型 API Key 未配置，真实 provider 不允许降级为 mock", null);
        }
        // 真实调用路径：限并发，避免高并发打爆线程池 + 撞模型限流雪崩。
        boolean acquired = false;
        try {
            try {
                // 等不到许可就抛异常让任务失败，而不是无限排队占满队列。
                long timeoutMs = Math.min(
                        positiveOrDefault(properties.getTimeoutMs(), DEFAULT_READ_TIMEOUT_MS),
                        callTimeoutMs);
                if (!llmSlots.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                    throw new LlmCallException("模型调用并发已达上限 " + maxConcurrentLlm + "，请稍后重试", null);
                }
                acquired = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmCallException("模型调用在等待并发许可时被中断", e);
            }
            refreshTimeouts();
            ensureWithinCallTimeout(start, callTimeoutMs);
            activeCallTimeoutMs.set(remainingCallTimeoutMs(start, callTimeoutMs));

            boolean streamEnabled = listener != null && !request.hasImages();
            ObjectNode body = buildChatRequestBody(request, streamEnabled);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // API Key 只通过环境变量进入后端，请求日志禁止输出 Authorization。
            headers.setBearerAuth(properties.getApiKey());
            String url = properties.getBaseUrl();
            if (url != null && !url.endsWith("/chat/completions")) {
                url = url.replaceAll("/+$", "") + "/chat/completions";
            }
            if (streamEnabled) {
                String content = streamAnalyze(url, headers, body, listener, start, callTimeoutMs);
                log.info("analyze response 响应: status: streamed, elapsedMs: {}, contentLength: {}",
                        System.currentTimeMillis() - start, content == null ? 0 : content.length());
                return new LlmResponse(provider, model, content, System.currentTimeMillis() - start, false);
            }
            String content = nonStreamingAnalyze(url, headers, body, start, callTimeoutMs);
            if (listener != null && content != null && !content.isEmpty()) {
                listener.onContent(content, content.length());
            }
            log.info("analyze response 响应: status: completed, elapsedMs: {}, contentLength: {}",
                    System.currentTimeMillis() - start, content == null ? 0 : content.length());
            return new LlmResponse(provider, model, content, System.currentTimeMillis() - start, false);
        } catch (LlmCallException e) {
            // extractContent 抛出的业务错误，原样上抛，不再降级 mock。
            throw e;
        } catch (RestClientResponseException e) {
            throw new LlmCallException("模型调用失败: " + summarizeHttpError(e), e);
        } catch (RestClientException e) {
            if (callTimeoutExceeded(start, callTimeoutMs)) {
                throw new LlmCallException("模型调用超过总时限 " + callTimeoutMs + "ms", e, true);
            }
            throw new LlmCallException("模型调用失败: " + e.getMessage(), e);
        } catch (Exception e) {
            // 真实调用失败（网络/超时/限流/鉴权）：抛出，由上层把任务标记为 FAILED，
            // 不再静默回退成 mock 当成功（避免逼真假建议被当真执行）。
            log.error("analyze error 异常: provider: {}, reason: {}", provider, e.getMessage());
            throw new LlmCallException("模型调用失败: " + e.getMessage(), e);
        } finally {
            activeCallTimeoutMs.remove();
            if (acquired) {
                llmSlots.release();
            }
        }
    }

    private String nonStreamingAnalyze(String url,
                                       HttpHeaders headers,
                                       ObjectNode body,
                                       long callStartedAtMs,
                                       int callTimeoutMs) {
        RequestCallback callback = request -> {
            request.getHeaders().putAll(headers);
            request.getBody().write(body.toString().getBytes(StandardCharsets.UTF_8));
        };
        return restTemplate.execute(url, HttpMethod.POST, callback, new ResponseExtractor<String>() {
            @Override
            public String extractData(ClientHttpResponse response) throws IOException {
                String responseBody = readResponseBody(response, callStartedAtMs, callTimeoutMs);
                ensureWithinCallTimeout(callStartedAtMs, callTimeoutMs);
                return extractContent(responseBody, response.getRawStatusCode());
            }
        });
    }

    private String readResponseBody(ClientHttpResponse response,
                                    long callStartedAtMs,
                                    int callTimeoutMs) throws IOException {
        Charset charset = response.getHeaders().getContentType() != null
                && response.getHeaders().getContentType().getCharset() != null
                ? response.getHeaders().getContentType().getCharset()
                : StandardCharsets.UTF_8;
        StringBuilder responseBody = new StringBuilder();
        char[] buffer = new char[4096];
        try (Reader reader = new InputStreamReader(response.getBody(), charset)) {
            int read;
            while ((read = reader.read(buffer)) != -1) {
                ensureWithinCallTimeout(callStartedAtMs, callTimeoutMs);
                responseBody.append(buffer, 0, read);
                ensureRawResponseWithinLimit(responseBody.length());
            }
        }
        return responseBody.toString();
    }

    private void applyMockDelay() {
        int delayMs = Math.max(0, properties.getMockDelayMs());
        if (delayMs == 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmCallException("Fake LLM 延迟被中断", e);
        }
    }

    /**
     * 解析 OpenAI 兼容响应，取 choices[0].message.content。
     * 若响应不是标准 choices 结构（如 dashscope 的错误信封 {"code":"InvalidApiKey",...}），
     * 视为调用失败抛 LlmCallException，携带错误体，而不是把错误 JSON 当成"内容"返回。
     */
    private String extractContent(String responseBody, int statusCode) throws LlmCallException {
        if (responseBody == null || responseBody.trim().isEmpty()) {
            throw new LlmCallException("模型返回空响应, status: " + statusCode, null);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (Exception e) {
            throw new LlmCallException("模型响应不是合法 JSON, bodyLength=" + responseBody.length(), e);
        }
        JsonNode choices = root.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            String content = choices.get(0).path("message").path("content").asText();
            if (content == null || content.isEmpty()) {
                throw new LlmCallException("模型响应缺少 content, status: " + statusCode, null);
            }
            ensureOutputWithinLimit(utf8Length(content));
            return content;
        }
        // 没有 choices：通常是错误信封。只暴露安全 code，不回显可能含提示词/SQL 的 message/body。
        String code = safeErrorCode(root.path("code").asText("provider_error"));
        throw new LlmCallException("模型返回错误信封, status: " + statusCode + ", code=" + code, null);
    }

    ObjectNode buildChatRequestBody(LlmRequest request) {
        return buildChatRequestBody(request, false);
    }

    ObjectNode buildChatRequestBody(LlmRequest request, boolean streamEnabled) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", resolveModel(request));
        ArrayNode messages = body.putArray("messages");
        ObjectNode system = objectMapper.createObjectNode();
        system.put("role", "system");
        system.put("content", request.getSystemPrompt());
        ObjectNode user = objectMapper.createObjectNode();
        user.put("role", "user");
        if (request.hasImages()) {
            ArrayNode content = user.putArray("content");
            ObjectNode text = objectMapper.createObjectNode();
            text.put("type", "text");
            text.put("text", request.getUserPrompt());
            content.add(text);
            for (LlmRequestImage image : request.getImages()) {
                ObjectNode imagePart = objectMapper.createObjectNode();
                imagePart.put("type", "image_url");
                ObjectNode imageUrl = imagePart.putObject("image_url");
                imageUrl.put("url", image.getDataUrl());
                content.add(imagePart);
            }
        } else {
            user.put("content", request.getUserPrompt());
        }
        messages.add(system);
        messages.add(user);
        if (!request.hasImages() && request.isJsonOutput()) {
            // DashScope/OpenAI 兼容 JSON mode：提示词已明确包含 JSON，仍由后端做严格结构与证据校验。
            body.putObject("response_format").put("type", "json_object");
        }
        if (streamEnabled) {
            body.put("stream", true);
        }
        body.put("max_tokens", resolveMaxTokens(request));
        String reasoningEffort = reasoningEffort();
        if (!request.hasImages() && reasoningEffort != null) {
            body.put("reasoning_effort", reasoningEffort);
        }
        body.put("temperature", request.isDeepAnalysis() ? 0.2 : 0.1);
        return body;
    }

    String streamAnalyze(String url, HttpHeaders headers, ObjectNode body, LlmStreamListener listener, long callStartedAtMs, int callTimeoutMs) {
        StringBuilder content = new StringBuilder();
        RequestCallback callback = request -> {
            request.getHeaders().putAll(headers);
            request.getBody().write(body.toString().getBytes(StandardCharsets.UTF_8));
        };
        ResponseExtractor<String> extractor = new ResponseExtractor<String>() {
            @Override
            public String extractData(ClientHttpResponse response) throws IOException {
                Charset charset = response.getHeaders().getContentType() != null && response.getHeaders().getContentType().getCharset() != null
                        ? response.getHeaders().getContentType().getCharset()
                        : StandardCharsets.UTF_8;
                StringBuilder plainBody = new StringBuilder();
                boolean sawServerSentEvent = false;
                boolean completed = false;
                int receivedChars = 0;
                int outputBytes = 0;
                int lastPublishedChars = 0;
                long lastPublishedAt = 0L;
                StringBuilder pendingDelta = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.getBody(), charset))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        ensureWithinCallTimeout(callStartedAtMs, callTimeoutMs);
                        if (!line.startsWith("data:")) {
                            plainBody.append(line);
                            ensureRawResponseWithinLimit(plainBody.length());
                            continue;
                        }
                        sawServerSentEvent = true;
                        String payload = line.substring("data:".length()).trim();
                        if (payload.isEmpty()) {
                            continue;
                        }
                        if ("[DONE]".equals(payload)) {
                            publishPendingDelta(listener, pendingDelta, receivedChars);
                            completed = true;
                            break;
                        }
                        try {
                            JsonNode chunk = objectMapper.readTree(payload);
                            String errorCode = extractStreamErrorCode(chunk);
                            if (errorCode != null) {
                                throw new LlmCallException(
                                        "模型流式返回错误, code=" + errorCode,
                                        null,
                                        isRetryableStreamErrorCode(errorCode));
                            }
                            String delta = extractStreamContent(chunk);
                            String reasoning = extractStreamReasoning(chunk);
                            String finishReason = extractStreamFinishReason(chunk);
                            receivedChars += delta.length() + reasoning.length();
                            if (delta != null && !delta.isEmpty()) {
                                int deltaBytes = utf8Length(delta);
                                ensureOutputWithinLimit(outputBytes + deltaBytes);
                                outputBytes += deltaBytes;
                                content.append(delta);
                                pendingDelta.append(delta);
                            }
                            if (listener != null
                                    && pendingDelta.length() > 0
                                    && shouldPublishStream(receivedChars, lastPublishedChars, lastPublishedAt)) {
                                listener.onContent(pendingDelta.toString(), receivedChars);
                                pendingDelta.setLength(0);
                                lastPublishedChars = receivedChars;
                                lastPublishedAt = System.nanoTime();
                            }
                            if (finishReason != null) {
                                if (!"stop".equalsIgnoreCase(finishReason)) {
                                    throw new LlmCallException("模型流式输出未完整完成, finishReason=" + safeErrorCode(finishReason), null);
                                }
                                publishPendingDelta(listener, pendingDelta, receivedChars);
                                completed = true;
                                break;
                            }
                        } catch (LlmCallException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new IOException("无法解析流式响应片段, payloadLength=" + payload.length(), e);
                        }
                    }
                }
                if (!sawServerSentEvent) {
                    String fallback = extractContent(plainBody.toString(), response.getRawStatusCode());
                    ensureOutputWithinLimit(fallback == null ? 0 : utf8Length(fallback));
                    if (listener != null && fallback != null && !fallback.isEmpty()) {
                        listener.onContent(fallback, fallback.length());
                    }
                    return fallback;
                }
                if (!completed) {
                    throw new LlmCallException(
                            "模型流式连接提前结束: missing completion marker",
                            new EOFException("stream ended before completion"));
                }
                return content.toString();
            }
        };
        try {
            return restTemplate.execute(url, HttpMethod.POST, callback, extractor);
        } catch (RestClientResponseException e) {
            throw new LlmCallException("模型流式调用失败: " + summarizeHttpError(e), e);
        } catch (RestClientException e) {
            if (callTimeoutExceeded(callStartedAtMs, callTimeoutMs)) {
                throw new LlmCallException("模型调用超过总时限 " + callTimeoutMs + "ms", e, true);
            }
            throw new LlmCallException("模型流式调用失败: " + e.getMessage(), e);
        }
    }

    private String extractStreamContent(JsonNode chunk) {
        JsonNode delta = firstStreamDelta(chunk);
        return delta == null ? "" : delta.path("content").asText("");
    }

    private String extractStreamReasoning(JsonNode chunk) {
        JsonNode delta = firstStreamDelta(chunk);
        return delta == null ? "" : delta.path("reasoning_content").asText("");
    }

    private String extractStreamFinishReason(JsonNode chunk) {
        if (chunk == null || !chunk.path("choices").isArray() || chunk.path("choices").isEmpty()) {
            return null;
        }
        JsonNode reason = chunk.path("choices").get(0).get("finish_reason");
        return reason == null || reason.isNull() || reason.asText("").trim().isEmpty()
                ? null
                : reason.asText().trim();
    }

    private String extractStreamErrorCode(JsonNode chunk) {
        if (chunk == null) {
            return null;
        }
        JsonNode error = chunk.get("error");
        if (error != null && !error.isNull()) {
            if (error.isObject()) {
                String code = error.path("code").asText(error.path("type").asText("provider_error"));
                return safeErrorCode(code);
            }
            return "provider_error";
        }
        if (!chunk.has("choices") && (chunk.has("code") || chunk.has("message"))) {
            return safeErrorCode(chunk.path("code").asText("provider_error"));
        }
        return null;
    }

    private JsonNode firstStreamDelta(JsonNode chunk) {
        if (chunk == null || !chunk.has("choices")) {
            return null;
        }
        JsonNode choices = chunk.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return null;
        }
        JsonNode delta = choices.get(0).path("delta");
        if (delta == null || delta.isMissingNode()) {
            return null;
        }
        return delta;
    }

    private String reasoningEffort() {
        if (properties.getReasoningEffort() == null || properties.getReasoningEffort().trim().isEmpty()) {
            return null;
        }
        String value = properties.getReasoningEffort().trim().toLowerCase(Locale.ROOT);
        if ("low".equals(value) || "medium".equals(value) || "high".equals(value) || "xhigh".equals(value)) {
            return value;
        }
        throw new LlmCallException("LLM_REASONING_EFFORT 仅支持 low、medium、high 或 xhigh", null);
    }

    private String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private String safeErrorCode(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "provider_error";
        }
        String safe = value.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
        return abbreviate(safe, 80);
    }

    private boolean isRetryableStreamErrorCode(String code) {
        String normalized = code == null ? "" : code.toLowerCase(Locale.ROOT);
        return normalized.contains("429")
                || normalized.contains("502")
                || normalized.contains("503")
                || normalized.contains("504")
                || normalized.contains("rate_limit")
                || normalized.contains("throttl")
                || normalized.contains("server_error")
                || normalized.contains("internal_error")
                || normalized.contains("overload")
                || normalized.contains("service_unavailable")
                || normalized.contains("temporarily_unavailable");
    }

    private void ensureWithinCallTimeout(long callStartedAtMs, int callTimeoutMs) {
        long timeoutMs = positiveOrDefault(callTimeoutMs, DEFAULT_CALL_TIMEOUT_MS);
        if (callTimeoutExceeded(callStartedAtMs, timeoutMs)) {
            throw new LlmCallException("模型调用超过总时限 " + timeoutMs + "ms", null, true);
        }
    }

    private boolean callTimeoutExceeded(long callStartedAtMs, long timeoutMs) {
        // 底层 socket timeout 与总预算正好落在同一毫秒时，也必须归类为总时限。
        return System.currentTimeMillis() - callStartedAtMs >= timeoutMs;
    }

    private void ensureOutputWithinLimit(int outputBytes) {
        int max = positiveOrDefault(properties.getMaxOutputChars(), DEFAULT_MAX_OUTPUT_CHARS);
        if (outputBytes > max) {
            throw new LlmCallException("模型输出超过上限 " + max + " 字节, code=LLM_OUTPUT_TOO_LARGE", null, false);
        }
    }

    private void ensureRawResponseWithinLimit(int responseChars) {
        int maxOutputBytes = positiveOrDefault(properties.getMaxOutputChars(), DEFAULT_MAX_OUTPUT_CHARS);
        // OpenAI 兼容响应还会包含 JSON 信封；保留两倍余量，仍防止异常供应商无限扩张内存。
        int maxResponseChars = Math.max(1024, maxOutputBytes * 2);
        if (responseChars > maxResponseChars) {
            throw new LlmCallException("模型响应超过安全上限, code=LLM_OUTPUT_TOO_LARGE", null, false);
        }
    }

    private int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private int positiveOrDefault(int value, int defaultValue) {
        return value > 0 ? value : defaultValue;
    }

    private boolean shouldPublishStream(int receivedChars, int lastPublishedChars, long lastPublishedAt) {
        if (lastPublishedAt == 0L) {
            return true;
        }
        return receivedChars - lastPublishedChars >= STREAM_CALLBACK_CHAR_STEP
                || System.nanoTime() - lastPublishedAt >= STREAM_CALLBACK_MIN_INTERVAL_NANOS;
    }

    private void publishPendingDelta(LlmStreamListener listener, StringBuilder pendingDelta, int receivedChars) {
        if (listener == null || pendingDelta.length() == 0) {
            return;
        }
        listener.onContent(pendingDelta.toString(), receivedChars);
        pendingDelta.setLength(0);
    }

    private int resolveCallTimeoutMs(LlmRequest request) {
        if (request != null && request.getCallTimeoutMs() != null && request.getCallTimeoutMs() > 0) {
            return request.getCallTimeoutMs();
        }
        return positiveOrDefault(properties.getCallTimeoutMs(), DEFAULT_CALL_TIMEOUT_MS);
    }

    private int remainingCallTimeoutMs(long callStartedAtMs, int callTimeoutMs) {
        long elapsedMs = System.currentTimeMillis() - callStartedAtMs;
        long remaining = (long) callTimeoutMs - elapsedMs;
        if (remaining <= 0L) {
            throw new LlmCallException("模型调用超过总时限 " + callTimeoutMs + "ms", null, true);
        }
        return remaining > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remaining;
    }

    private int resolveMaxTokens(LlmRequest request) {
        int configured = request != null && request.hasImages()
                ? positiveOrDefault(properties.getVisionMaxTokens(), DEFAULT_VISION_MAX_TOKENS)
                : positiveOrDefault(properties.getTextMaxTokens(), DEFAULT_TEXT_MAX_TOKENS);
        if (request != null && request.getMaxTokens() != null && request.getMaxTokens() > 0) {
            return Math.min(request.getMaxTokens(), configured);
        }
        return configured;
    }

    private String summarizeHttpError(RestClientResponseException error) {
        String body = error.getResponseBodyAsString();
        String code = "";
        if (body != null && !body.trim().isEmpty()) {
            try {
                JsonNode root = objectMapper.readTree(body);
                JsonNode envelope = root.has("error") && root.path("error").isObject() ? root.path("error") : root;
                code = safeErrorCode(envelope.path("code").asText(envelope.path("type").asText("")));
            } catch (Exception ignored) {
                code = "";
            }
        }
        return error.getRawStatusCode() + " " + error.getStatusText() + (code.isEmpty() ? "" : ", code=" + code);
    }

    private HttpComponentsClientHttpRequestFactory buildRequestFactory() {
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        int maxPerRoute = Math.max(1, maxConcurrentLlm);
        connectionManager.setMaxTotal(Math.max(32, maxPerRoute * 2));
        connectionManager.setDefaultMaxPerRoute(maxPerRoute);
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(positiveOrDefault(properties.getConnectTimeoutMs(), DEFAULT_CONNECT_TIMEOUT_MS))
                .setSocketTimeout(positiveOrDefault(properties.getReadTimeoutMs(), DEFAULT_READ_TIMEOUT_MS))
                .setConnectionRequestTimeout(positiveOrDefault(properties.getConnectTimeoutMs(), DEFAULT_CONNECT_TIMEOUT_MS))
                .build();
        CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(config)
                .disableCookieManagement()
                .evictIdleConnections(30, TimeUnit.SECONDS)
                .build();
        return new HttpComponentsClientHttpRequestFactory(client);
    }

    private HttpClientContext requestContext() {
        int connectTimeoutMs = positiveOrDefault(properties.getConnectTimeoutMs(), DEFAULT_CONNECT_TIMEOUT_MS);
        int readTimeoutMs = positiveOrDefault(properties.getReadTimeoutMs(), DEFAULT_READ_TIMEOUT_MS);
        Integer activeTimeout = activeCallTimeoutMs.get();
        if (activeTimeout != null && activeTimeout > 0) {
            readTimeoutMs = Math.min(readTimeoutMs, activeTimeout);
        }
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(connectTimeoutMs)
                .setSocketTimeout(readTimeoutMs)
                .setConnectionRequestTimeout(connectTimeoutMs)
                .build();
        HttpClientContext context = HttpClientContext.create();
        context.setRequestConfig(requestConfig);
        return context;
    }

    /**
     * timeout 允许运行期通过管理页调整。
     * 这里在共享 requestFactory 上刷新超时，避免每次 new RestTemplate 丢掉连接池收益。
     */
    private void refreshTimeouts() {
        int connectTimeoutMs = positiveOrDefault(properties.getConnectTimeoutMs(), DEFAULT_CONNECT_TIMEOUT_MS);
        int readTimeoutMs = positiveOrDefault(properties.getReadTimeoutMs(), DEFAULT_READ_TIMEOUT_MS);
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);
        requestFactory.setConnectionRequestTimeout(connectTimeoutMs);
    }

    /**
     * mock 输出：内容里带显眼前缀和"请勿用于生产"风险提示，
     * 避免被误当成真实模型建议直接执行。schema 与真实输出一致以保证前端可解析。
     */
    private String mockResponse(LlmRequest request) {
        if (request.hasImages()) {
            return "{"
                    + "\"readable\":true,"
                    + "\"operators\":[\"MOCK_PLAN_IMAGE\"],"
                    + "\"tables\":[],"
                    + "\"rowEstimates\":[],"
                    + "\"warnings\":[\"mock vision output\"],"
                    + "\"rawTextSummary\":\"【示例·非真实视觉输出】图片已进入视觉抽取流程。\""
                    + "}";
        }
        String prompt = request.getUserPrompt() == null ? "" : request.getUserPrompt();
        boolean sqlOnly = prompt.contains("contextCompleteness: SQL_ONLY") || prompt.contains("\"completeness\":\"SQL_ONLY\"");
        boolean review = prompt.contains("独立审查器");
        String verdict = review ? "PASS" : "NOT_REQUESTED";
        String outcome = sqlOnly ? "NEEDS_INPUT" : "ADVICE";
        // mock 只验证工作流，不编造具体改写或索引对象；确定性建议必须来自真实模型和用户证据。
        String rewriteCandidates = "[]";
        String indexCandidates = "[]";
        return "{"
                + "\"outcome\":\"" + outcome + "\","
                + "\"summary\":\"【示例·非真实模型输出】已完成离线规则分析。当前明确配置为 mock LLM，配置真实 provider 与 API Key 后可调用模型生成更细建议。\","
                + "\"analysisNarrative\":{\"conclusion\":\"【示例·非真实模型输出】当前仅用于验证叙事结果的展示和证据门禁，不能作为生产调优结论。\",\"sections\":[{\"kind\":\"EVIDENCE\",\"title\":\"当前可确认的事实\",\"body\":\"已从用户输入中识别到 SQL 和有限上下文；mock 不会生成可执行的真实建议。\",\"evidenceRefs\":[\"E_SQL\"]},{\"kind\":\"ACTION\",\"title\":\"下一步\",\"body\":\"配置真实模型后，补充执行计划、索引和统计信息再验证。\",\"evidenceRefs\":[\"E_SQL\"]}]},"
                + "\"contextAssessment\":{\"completeness\":\"" + (sqlOnly ? "SQL_ONLY" : "SQL_SCHEMA_INDEX") + "\",\"maxConfidence\":\"" + (sqlOnly ? "LOW" : "MEDIUM") + "\",\"availableEvidence\":[\"E_SQL\"],\"missingInformation\":[\"表结构 DDL\",\"现有索引定义\",\"完整 EXPLAIN\",\"表统计信息\",\"OceanBase 版本\"],\"policyNotes\":[\"mock 输出遵守证据门禁\"]},"
                + "\"evidenceCatalog\":[{\"id\":\"E_SQL\",\"source\":\"USER_SQL\",\"summary\":\"用户提供的脱敏 SQL\",\"trustLevel\":\"HIGH\"},{\"id\":\"E_SCHEMA\",\"source\":\"USER_SCHEMA\",\"summary\":\"用户提供的表结构上下文\",\"trustLevel\":\"MEDIUM\"},{\"id\":\"E_INDEX\",\"source\":\"USER_INDEX\",\"summary\":\"用户提供的索引上下文\",\"trustLevel\":\"MEDIUM\"},{\"id\":\"E_EXPLAIN\",\"source\":\"USER_EXPLAIN\",\"summary\":\"用户提供的执行计划上下文\",\"trustLevel\":\"MEDIUM\"}],"
                + "\"diagnoses\":[{\"severity\":\"WARN\",\"title\":\"【示例】优先检查扫描行数、索引命中和谓词形态\",\"impact\":\"可能影响查询延迟\",\"confidence\":\"LOW\",\"precondition\":\"mock 输出仅用于界面和流程验证\",\"evidenceRefs\":[\"E_SQL\"]}],"
                + "\"rewriteCandidates\":" + rewriteCandidates + ","
                + "\"indexCandidates\":" + indexCandidates + ","
                + "\"validationPlan\":[{\"action\":\"在测试库对比优化前后 EXPLAIN\",\"expectedSignal\":\"key/rows/Extra 改善且结果集一致\",\"evidenceRefs\":[\"E_SQL\"]}],"
                + "\"missingInformation\":[\"表行数\",\"现有索引\",\"完整 EXPLAIN\",\"OceanBase 版本\"],"
                + "\"safetyWarnings\":[\"本次为 mock 示例输出，未调用真实模型，请勿直接用于生产\",\"不要直接在生产创建索引，先评估写入成本和磁盘空间\"],"
                + "\"review\":{\"verdict\":\"" + verdict + "\",\"notes\":\"mock review\"}"
                + "}";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String resolveModel(LlmRequest request) {
        if (hasText(request.getModelOverride())) {
            return request.getModelOverride().trim();
        }
        if (request.hasImages() && hasText(properties.getVisionModel())) {
            return properties.getVisionModel().trim();
        }
        return properties.getModel();
    }
}
