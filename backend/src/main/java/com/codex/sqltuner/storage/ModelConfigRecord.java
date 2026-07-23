package com.codex.sqltuner.storage;

public class ModelConfigRecord {
    private String provider;
    private String baseUrl;
    private String model;
    private String visionModel;
    private String apiKey;
    private String apiKeyBinding;
    private Integer timeoutMs;

    public ModelConfigRecord() {
    }

    public ModelConfigRecord(String provider, String baseUrl, String model, String visionModel, Integer timeoutMs) {
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.model = model;
        this.visionModel = visionModel;
        this.timeoutMs = timeoutMs;
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

    public String getVisionModel() {
        return visionModel;
    }

    public void setVisionModel(String visionModel) {
        this.visionModel = visionModel;
    }

    public Integer getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Integer timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiKeyBinding() {
        return apiKeyBinding;
    }

    public void setApiKeyBinding(String apiKeyBinding) {
        this.apiKeyBinding = apiKeyBinding;
    }
}
