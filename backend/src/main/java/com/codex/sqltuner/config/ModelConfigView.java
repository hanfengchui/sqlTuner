package com.codex.sqltuner.config;

public class ModelConfigView {
    private String provider;
    private String baseUrl;
    private String model;
    private Integer timeoutMs;
    private boolean apiKeyConfigured;
    // mockState: "mock"=当前走 mock 或缺 key；"ready"=已配置真实 provider+key。
    // 前端据此在顶部展示醒目横幅，避免 mock 输出被当成真实模型建议。
    private String mockState;

    public ModelConfigView() {
    }

    public ModelConfigView(String provider, String baseUrl, String model, Integer timeoutMs, boolean apiKeyConfigured, String mockState) {
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.model = model;
        this.timeoutMs = timeoutMs;
        this.apiKeyConfigured = apiKeyConfigured;
        this.mockState = mockState;
    }

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

    public Integer getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Integer timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public boolean isApiKeyConfigured() {
        return apiKeyConfigured;
    }

    public void setApiKeyConfigured(boolean apiKeyConfigured) {
        this.apiKeyConfigured = apiKeyConfigured;
    }

    public String getMockState() {
        return mockState;
    }

    public void setMockState(String mockState) {
        this.mockState = mockState;
    }
}
