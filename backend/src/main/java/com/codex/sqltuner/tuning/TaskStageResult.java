package com.codex.sqltuner.tuning;

import java.time.LocalDateTime;

public class TaskStageResult {
    private Long id;
    private Long taskId;
    private String stageName;
    private String inputSha256;
    private String provider;
    private String model;
    private String content;
    private long elapsedMs;
    private boolean mock;
    private LocalDateTime createdAt;

    public TaskStageResult() {
    }

    public TaskStageResult(Long id,
                           Long taskId,
                           String stageName,
                           String inputSha256,
                           String provider,
                           String model,
                           String content,
                           long elapsedMs,
                           boolean mock,
                           LocalDateTime createdAt) {
        this.id = id;
        this.taskId = taskId;
        this.stageName = stageName;
        this.inputSha256 = inputSha256;
        this.provider = provider;
        this.model = model;
        this.content = content;
        this.elapsedMs = elapsedMs;
        this.mock = mock;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getStageName() {
        return stageName;
    }

    public void setStageName(String stageName) {
        this.stageName = stageName;
    }

    public String getInputSha256() {
        return inputSha256;
    }

    public void setInputSha256(String inputSha256) {
        this.inputSha256 = inputSha256;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
