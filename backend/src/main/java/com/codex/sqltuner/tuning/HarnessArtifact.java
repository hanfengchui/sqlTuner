package com.codex.sqltuner.tuning;

import java.time.LocalDateTime;

public class HarnessArtifact {
    private String nodeName;
    private String summary;
    private Object payload;
    private LocalDateTime createdAt;

    public HarnessArtifact() {
    }

    public HarnessArtifact(String nodeName, String summary, Object payload, LocalDateTime createdAt) {
        this.nodeName = nodeName;
        this.summary = summary;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Object getPayload() {
        return payload;
    }

    public void setPayload(Object payload) {
        this.payload = payload;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
