package com.codex.sqltuner.llm;

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
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
public class ConfigurableLlmClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(ConfigurableLlmClient.class);
    // 限制同时进行的真实模型调用数，避免高并发打爆线程池 + 撞模型侧限流雪崩。
    // mock 不占许可。许可数与线程池 maxPoolSize 协调，保守取 8。
    private static final int MAX_CONCURRENT_LLM = 8;
    private final Semaphore llmSlots = new Semaphore(MAX_CONCURRENT_LLM, true);
    private final LlmProperties properties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final HttpComponentsClientHttpRequestFactory requestFactory;

    public ConfigurableLlmClient(LlmProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.requestFactory = buildRequestFactory();
        this.restTemplate = new RestTemplate(requestFactory);
    }

    @Override
    public LlmResponse analyze(LlmRequest request) {
        long start = System.currentTimeMillis();
        String provider = properties.getProvider() == null ? "mock" : properties.getProvider();
        log.info("analyze request 请求: provider: {}, model: {}, promptLength: {}, deepAnalysis: {}",
                provider, properties.getModel(), request.getUserPrompt() == null ? 0 : request.getUserPrompt().length(), request.isDeepAnalysis());
        // 配置性 mock：provider=mock 或缺 key，正常返回 mock（内容带显眼标记，不会被误当真模型）。
        if ("mock".equalsIgnoreCase(provider) || properties.getApiKey() == null || properties.getApiKey().trim().isEmpty()) {
            String content = mockResponse(request);
            log.info("analyze response 响应: provider: mock, reason: configured-mock-or-no-key, elapsedMs: {}", System.currentTimeMillis() - start);
            return new LlmResponse("mock", properties.getModel(), content, System.currentTimeMillis() - start, true);
        }
        // 真实调用路径：限并发，避免高并发打爆线程池 + 撞模型限流雪崩。
        boolean acquired = false;
        try {
            try {
                // 等不到许可就抛异常让任务失败，而不是无限排队占满队列。
                long timeoutMs = properties.getTimeoutMs() <= 0 ? 30000L : properties.getTimeoutMs();
                if (!llmSlots.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                    throw new LlmCallException("模型调用并发已达上限 " + MAX_CONCURRENT_LLM + "，请稍后重试", null);
                }
                acquired = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmCallException("模型调用在等待并发许可时被中断", e);
            }
            refreshTimeouts();

            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", properties.getModel());
            ArrayNode messages = body.putArray("messages");
            ObjectNode system = objectMapper.createObjectNode();
            system.put("role", "system");
            system.put("content", request.getSystemPrompt());
            ObjectNode user = objectMapper.createObjectNode();
            user.put("role", "user");
            user.put("content", request.getUserPrompt());
            messages.add(system);
            messages.add(user);
            body.put("temperature", request.isDeepAnalysis() ? 0.2 : 0.1);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // API Key 只通过环境变量进入后端，请求日志禁止输出 Authorization。
            headers.setBearerAuth(properties.getApiKey());
            String url = properties.getBaseUrl();
            if (url != null && !url.endsWith("/chat/completions")) {
                url = url.replaceAll("/+$", "") + "/chat/completions";
            }
            ResponseEntity<String> response = restTemplate.postForEntity(url, new HttpEntity<String>(body.toString(), headers), String.class);
            String content = extractContent(response.getBody(), response.getStatusCodeValue());
            log.info("analyze response 响应: status: {}, elapsedMs: {}, contentLength: {}",
                    response.getStatusCodeValue(), System.currentTimeMillis() - start, content == null ? 0 : content.length());
            return new LlmResponse(provider, properties.getModel(), content, System.currentTimeMillis() - start, false);
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
        return "{"
                + "\"summary\":\"【示例·非真实模型输出】已完成离线规则分析。当前使用 mock LLM，配置 DASHSCOPE_API_KEY 后可调用千问生成更细建议。\","
                + "\"findings\":[{\"title\":\"【示例】优先检查执行计划中的扫描行数和索引命中情况\",\"evidence\":\"规则扫描结果和手工上下文\",\"impact\":\"可能影响查询延迟\",\"confidence\":\"medium\"}],"
                + "\"rewriteSql\":\"-- 【示例占位】请配置真实模型后再参考改写建议\\n\","
                + "\"indexSuggestions\":[{\"indexName\":\"idx_示例_占位_请勿使用\",\"columns\":[\"filter_column\",\"sort_column\"],\"benefit\":\"减少过滤后排序成本\",\"risk\":\"增加写入维护成本\",\"validation\":\"EXPLAIN 对比 type/key/rows\"}],"
                + "\"validationSteps\":[\"在测试库对比优化前后 EXPLAIN\",\"记录 rows、key、Extra、耗时\",\"确认结果集语义一致\"],"
                + "\"riskWarnings\":[\"本次为 mock 示例输出，未调用真实模型，请勿直接用于生产\",\"不要直接在生产创建索引，先评估写入成本和磁盘空间\"],"
                + "\"needMoreInfo\":[\"表行数\",\"现有索引\",\"完整 EXPLAIN\"]"
                + "}";
    }
}
