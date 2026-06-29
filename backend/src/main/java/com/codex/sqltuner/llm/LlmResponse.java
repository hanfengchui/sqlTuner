package com.codex.sqltuner.llm;

public class LlmResponse {
    private String provider;
    private String model;
    private String content;
    private long elapsedMs;
    private boolean mock;

    public LlmResponse() {
    }

    public LlmResponse(String provider, String model, String content, long elapsedMs, boolean mock) {
        this.provider = provider;
        this.model = model;
        this.content = content;
        this.elapsedMs = elapsedMs;
        this.mock = mock;
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

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    public boolean isMock() {
        return mock;
    }

    public void setMock(boolean mock) {
        this.mock = mock;
    }
}
