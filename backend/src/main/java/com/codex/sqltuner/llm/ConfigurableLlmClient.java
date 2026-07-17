package com.codex.sqltuner.llm;

import com.codex.sqltuner.config.QueueProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
public class ConfigurableLlmClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(ConfigurableLlmClient.class);
    // mock 不占许可；真实模型并发默认与持久化 worker 上限一致（生产为 4）。
    private final int maxConcurrentLlm;
    private final Semaphore llmSlots;
    private final LlmProperties properties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final HttpComponentsClientHttpRequestFactory requestFactory;

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
        this.restTemplate = new RestTemplate(requestFactory);
    }

    @Override
    public LlmResponse analyze(LlmRequest request) {
        long start = System.currentTimeMillis();
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
                long timeoutMs = properties.getTimeoutMs() <= 0 ? 30000L : properties.getTimeoutMs();
                if (!llmSlots.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                    throw new LlmCallException("模型调用并发已达上限 " + maxConcurrentLlm + "，请稍后重试", null);
                }
                acquired = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmCallException("模型调用在等待并发许可时被中断", e);
            }
            refreshTimeouts();

            ObjectNode body = buildChatRequestBody(request);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // API Key 只通过环境变量进入后端，请求日志禁止输出 Authorization。
            headers.setBearerAuth(properties.getApiKey());
            String url = properties.getBaseUrl();
            if (url != null && !url.endsWith("/chat/completions")) {
                url = url.replaceAll("/+$", "") + "/chat/completions";
            }
            ResponseEntity<String> response = restTemplate.postForEntity(
                    url,
                    new HttpEntity<byte[]>(body.toString().getBytes(StandardCharsets.UTF_8), headers),
                    String.class);
            String content = extractContent(response.getBody(), response.getStatusCodeValue());
            log.info("analyze response 响应: status: {}, elapsedMs: {}, contentLength: {}",
                    response.getStatusCodeValue(), System.currentTimeMillis() - start, content == null ? 0 : content.length());
            return new LlmResponse(provider, model, content, System.currentTimeMillis() - start, false);
        } catch (LlmCallException e) {
            // extractContent 抛出的业务错误，原样上抛，不再降级 mock。
            throw e;
        } catch (Exception e) {
            // 真实调用失败（网络/超时/限流/鉴权）：抛出，由上层把任务标记为 FAILED，
            // 不再静默回退成 mock 当成功（避免逼真假建议被当真执行）。
            log.error("analyze error 异常: provider: {}, reason: {}", provider, e.getMessage());
            throw new LlmCallException("模型调用失败: " + e.getMessage(), e);
        } finally {
            if (acquired) {
                llmSlots.release();
            }
        }
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
            throw new LlmCallException("模型响应不是合法 JSON: " + abbreviate(responseBody, 200), e);
        }
        JsonNode choices = root.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            String content = choices.get(0).path("message").path("content").asText();
            if (content == null || content.isEmpty()) {
                throw new LlmCallException("模型响应缺少 content, status: " + statusCode + ", body: " + abbreviate(responseBody, 200), null);
            }
            return content;
        }
        // 没有 choices：通常是错误信封。提取 code/message 暴露给上层。
        String code = root.path("code").asText("");
        String message = root.path("message").asText("");
        String detail = (code.isEmpty() && message.isEmpty())
                ? abbreviate(responseBody, 200)
                : "code=" + code + ", message=" + message;
        throw new LlmCallException("模型返回错误信封, status: " + statusCode + ", " + detail, null);
    }

    ObjectNode buildChatRequestBody(LlmRequest request) {
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
        if (!request.hasImages()) {
            // DashScope/OpenAI 兼容 JSON mode：提示词已明确包含 JSON，仍由后端做严格结构与证据校验。
            body.putObject("response_format").put("type", "json_object");
        }
        body.put("temperature", request.isDeepAnalysis() ? 0.2 : 0.1);
        return body;
    }

    private String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private HttpComponentsClientHttpRequestFactory buildRequestFactory() {
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(32);
        connectionManager.setDefaultMaxPerRoute(8);
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(properties.getTimeoutMs())
                .setSocketTimeout(properties.getTimeoutMs())
                .setConnectionRequestTimeout(properties.getTimeoutMs())
                .build();
        CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(config)
                .disableCookieManagement()
                .evictIdleConnections(30, TimeUnit.SECONDS)
                .build();
        return new HttpComponentsClientHttpRequestFactory(client);
    }

    /**
     * timeout 允许运行期通过管理页调整。
     * 这里在共享 requestFactory 上刷新超时，避免每次 new RestTemplate 丢掉连接池收益。
     */
    private void refreshTimeouts() {
        int timeoutMs = properties.getTimeoutMs() <= 0 ? 30000 : properties.getTimeoutMs();
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);
        requestFactory.setConnectionRequestTimeout(timeoutMs);
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
                + "\"contextAssessment\":{\"completeness\":\"" + (sqlOnly ? "SQL_ONLY" : "SQL_SCHEMA_INDEX") + "\",\"maxConfidence\":\"" + (sqlOnly ? "LOW" : "MEDIUM") + "\",\"availableEvidence\":[\"E_SQL\"],\"missingInformation\":[\"表结构 DDL\",\"现有索引定义\",\"完整 EXPLAIN\",\"表统计信息\",\"OceanBase 版本\"],\"policyNotes\":[\"mock 输出遵守证据门禁\"]},"
                + "\"evidenceCatalog\":[{\"id\":\"E_SQL\",\"source\":\"USER_SQL\",\"summary\":\"用户提供的脱敏 SQL\",\"trustLevel\":\"HIGH\"},{\"id\":\"E_SCHEMA\",\"source\":\"USER_SCHEMA\",\"summary\":\"用户提供的表结构上下文\",\"trustLevel\":\"MEDIUM\"},{\"id\":\"E_INDEX\",\"source\":\"USER_INDEX\",\"summary\":\"用户提供的索引上下文\",\"trustLevel\":\"MEDIUM\"},{\"id\":\"E_EXPLAIN\",\"source\":\"USER_EXPLAIN\",\"summary\":\"用户提供的执行计划上下文\",\"trustLevel\":\"MEDIUM\"}],"
                + "\"diagnoses\":[{\"severity\":\"WARN\",\"title\":\"【示例】优先检查扫描行数、索引命中和谓词形态\",\"impact\":\"可能影响查询延迟\",\"confidence\":\"LOW\",\"precondition\":\"mock 输出仅用于界面和流程验证\",\"evidenceRefs\":[\"E_SQL\"]}],"
                + "\"rewriteCandidates\":" + rewriteCandidates + ","
                + "\"indexCandidates\":" + indexCandidates + ","
                + "\"validationPlan\":[{\"action\":\"在测试库对比优化前后 EXPLAIN\",\"expectedSignal\":\"key/rows/Extra 改善且结果集一致\",\"evidenceRefs\":[\"E_SQL\"]}],"
                + "\"missingInformation\":[\"表行数\",\"现有索引\",\"完整 EXPLAIN\",\"OceanBase 版本\"],"
                + "\"safetyWarnings\":[\"本次为 mock 示例输出，未调用真实模型，请勿直接用于生产\",\"不要直接在生产创建索引，先评估写入成本和磁盘空间\"],"
                + "\"review\":{\"verdict\":\"" + verdict + "\",\"notes\":\"mock review\"},"
                + "\"findings\":[{\"title\":\"【示例】优先检查执行计划中的扫描行数和索引命中情况\",\"evidence\":\"E_SQL\",\"impact\":\"可能影响查询延迟\",\"confidence\":\"LOW\"}],"
                + "\"validationSteps\":[\"在测试库对比优化前后 EXPLAIN\"],"
                + "\"riskWarnings\":[\"本次为 mock 示例输出，未调用真实模型，请勿直接用于生产\"],"
                + "\"needMoreInfo\":[\"表行数\",\"现有索引\",\"完整 EXPLAIN\"]"
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
