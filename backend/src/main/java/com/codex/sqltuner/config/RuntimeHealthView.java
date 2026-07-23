package com.codex.sqltuner.config;

public class RuntimeHealthView {
    private String provider;
    private String model;
    private String mockState;
    private boolean apiKeyConfigured;
    private String mysql;
    private String scheduler;
    private int queued;
    private int running;
    private boolean retentionEnabled;
    private int retentionDays;

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

    public String getMysql() {
        return mysql;
    }

    public void setMysql(String mysql) {
        this.mysql = mysql;
    }

    public String getScheduler() {
        return scheduler;
    }

    public void setScheduler(String scheduler) {
        this.scheduler = scheduler;
    }

    public int getQueued() {
        return queued;
    }

    public void setQueued(int queued) {
        this.queued = queued;
    }

    public int getRunning() {
        return running;
    }

    public void setRunning(int running) {
        this.running = running;
    }

    public boolean isRetentionEnabled() {
        return retentionEnabled;
    }

    public void setRetentionEnabled(boolean retentionEnabled) {
        this.retentionEnabled = retentionEnabled;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }
}
