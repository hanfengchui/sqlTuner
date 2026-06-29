package com.codex.sqltuner.config;

import com.codex.sqltuner.storage.ModelConfigRecord;
import com.codex.sqltuner.storage.PersistentAppState;
import com.codex.sqltuner.storage.PersistentStateStore;
import com.codex.sqltuner.llm.LlmClient;
import com.codex.sqltuner.llm.LlmRequest;
import com.codex.sqltuner.llm.LlmResponse;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

@Service
public class ModelConfigService {
    private final PersistentStateStore stateStore;
    private final com.codex.sqltuner.llm.LlmProperties llmProperties;
    private final LlmClient llmClient;

    public ModelConfigService(PersistentStateStore stateStore, com.codex.sqltuner.llm.LlmProperties llmProperties, LlmClient llmClient) {
        this.stateStore = stateStore;
        this.llmProperties = llmProperties;
        this.llmClient = llmClient;
    }

    public ModelConfigView view() {
        return stateStore.read(new Function<PersistentAppState, ModelConfigView>() {
            @Override
            public ModelConfigView apply(PersistentAppState state) {
                ModelConfigRecord record = state.getModelConfig();
                return new ModelConfigView(
                        record.getProvider(),
                        record.getBaseUrl(),
                        record.getModel(),
                        record.getTimeoutMs(),
                        hasApiKey(record),
                        mockState(record)
                );
            }
        });
    }

    public RuntimeHealthView healthView() {
        return stateStore.read(new Function<PersistentAppState, RuntimeHealthView>() {
            @Override
            public RuntimeHealthView apply(PersistentAppState state) {
                ModelConfigRecord record = state.getModelConfig();
                return new RuntimeHealthView(
                        record.getProvider(),
                        record.getModel(),
                        mockState(record),
                        hasApiKey(record)
                );
            }
        });
    }

    public ModelConfigRecord runtime() {
        // 运行期需要的配置副本：含解密后的 apiKey，供 LLM client 使用。
        return stateStore.read(new Function<PersistentAppState, ModelConfigRecord>() {
            @Override
            public ModelConfigRecord apply(PersistentAppState state) {
                ModelConfigRecord record = state.getModelConfig();
                ModelConfigRecord copy = new ModelConfigRecord(record.getProvider(), record.getBaseUrl(), record.getModel(), record.getTimeoutMs());
                copy.setApiKey(record.getApiKey());
                return copy;
            }
        });
    }

    public List<ModelProviderOption> providers() {
        return Arrays.asList(
                new ModelProviderOption("dashscope", "阿里云百炼 / 千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", true),
                new ModelProviderOption("openai-compatible", "OpenAI 兼容网关", "https://your-gateway.example.com/v1", "qwen-plus", true),
                new ModelProviderOption("mock", "本地 Mock（不调用模型）", "", "mock", false)
        );
    }

    public ModelConfigView update(final ModelConfigUpdateRequest request) {
        ModelConfigView view = stateStore.mutate(new Function<PersistentAppState, ModelConfigView>() {
            @Override
            public ModelConfigView apply(PersistentAppState state) {
                ModelConfigRecord record = state.getModelConfig();
                if (request.getProvider() != null && !request.getProvider().trim().isEmpty()) {
                    record.setProvider(request.getProvider().trim());
                }
                if (request.getBaseUrl() != null && !request.getBaseUrl().trim().isEmpty()) {
                    record.setBaseUrl(request.getBaseUrl().trim());
                }
                if (request.getModel() != null && !request.getModel().trim().isEmpty()) {
                    record.setModel(request.getModel().trim());
                }
                if (request.getApiKey() != null && !request.getApiKey().trim().isEmpty()) {
                    record.setApiKey(request.getApiKey().trim());
                }
                if (request.getTimeoutMs() != null && request.getTimeoutMs() > 0) {
                    record.setTimeoutMs(request.getTimeoutMs());
                }
                llmProperties.apply(record);
                return new ModelConfigView(
                        record.getProvider(),
                        record.getBaseUrl(),
                        record.getModel(),
                        record.getTimeoutMs(),
                        hasApiKey(record),
                        mockState(record)
                );
            }
        });
        return view;
    }

    public ModelTestResult testConnection() {
        ModelTestResult result = new ModelTestResult();
        result.setProvider(llmProperties.getProvider());
        result.setModel(llmProperties.getModel());
        long start = System.currentTimeMillis();
        try {
            LlmResponse response = llmClient.analyze(new LlmRequest(
                    "你是连接测试助手。只输出一个 JSON 对象。",
                    "请只返回 {\"ok\":true,\"message\":\"pong\"}，不要解释。",
                    false
            ));
            result.setElapsedMs(response.getElapsedMs());
            result.setProvider(response.getProvider());
            result.setModel(response.getModel());
            result.setMock(response.isMock());
            result.setSuccess(!response.isMock());
            result.setMessage(response.isMock() ? "当前走的是 mock 或已回退到 mock，未确认真实模型连通。" : "真实模型调用成功。");
            result.setSample(response.getContent() == null ? "" : abbreviate(response.getContent(), 240));
        } catch (Exception e) {
            result.setElapsedMs(System.currentTimeMillis() - start);
            result.setSuccess(false);
            result.setMessage("连接测试失败: " + e.getMessage());
        }
        return result;
    }

    private boolean hasApiKey(ModelConfigRecord record) {
        if (record.getApiKey() != null && !record.getApiKey().trim().isEmpty()) {
            return true;
        }
        return llmProperties.getApiKey() != null && !llmProperties.getApiKey().trim().isEmpty();
    }

    // mockState：provider=mock 或缺 key 时为 "mock"，前端据此展示醒目横幅，防止 mock 输出被当真。
    private String mockState(ModelConfigRecord record) {
        String provider = record.getProvider();
        if (provider != null && "mock".equalsIgnoreCase(provider.trim())) {
            return "mock";
        }
        return hasApiKey(record) ? "ready" : "mock";
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}
