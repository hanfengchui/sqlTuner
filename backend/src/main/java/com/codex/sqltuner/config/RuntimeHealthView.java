package com.codex.sqltuner.config;

public class RuntimeHealthView {
    private String provider;
    private String model;
    private String mockState;
    private boolean apiKeyConfigured;

    public RuntimeHealthView() {
    }

    public RuntimeHealthView(String provider, String model, String mockState, boolean apiKeyConfigured) {
        this.provider = provider;
        this.model = model;
        this.mockState = mockState;
        this.apiKeyConfigured = apiKeyConfigured;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getMockState() {
        return mockState;
    }

    public void setMockState(String mockState) {
        this.mockState = mockState;
    }

    public boolean isApiKeyConfigured() {
        return apiKeyConfigured;
    }

    public void setApiKeyConfigured(boolean apiKeyConfigured) {
        this.apiKeyConfigured = apiKeyConfigured;
    }
}
