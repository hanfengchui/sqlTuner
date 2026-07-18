package com.codex.sqltuner.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {
    private String provider = "mock";
    private String baseUrl;
    private String model = "qwen-plus";
    private String visionModel = "qwen3-vl-plus";
    private String apiKey;
    private String reasoningEffort;
    private int timeoutMs = 30000;
    private int mockDelayMs;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getVisionModel() {
        return visionModel;
    }

    public void setVisionModel(String visionModel) {
        this.visionModel = visionModel;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getMockDelayMs() {
        return mockDelayMs;
    }

    public void setMockDelayMs(int mockDelayMs) {
        this.mockDelayMs = mockDelayMs;
    }

    public void apply(com.codex.sqltuner.storage.ModelConfigRecord record) {
        if (record == null) {
            return;
        }
        if (record.getProvider() != null && !record.getProvider().trim().isEmpty()) {
            this.provider = record.getProvider();
        }
        if (record.getBaseUrl() != null && !record.getBaseUrl().trim().isEmpty()) {
            this.baseUrl = record.getBaseUrl();
        }
        if (record.getModel() != null && !record.getModel().trim().isEmpty()) {
            this.model = record.getModel();
        }
        if (record.getVisionModel() != null && !record.getVisionModel().trim().isEmpty()) {
            this.visionModel = record.getVisionModel();
        } else {
            this.visionModel = this.model;
        }
        if (record.getApiKey() != null && !record.getApiKey().trim().isEmpty()) {
            this.apiKey = record.getApiKey();
        }
        if (record.getTimeoutMs() != null && record.getTimeoutMs() > 0) {
            this.timeoutMs = record.getTimeoutMs();
        }
    }
}
