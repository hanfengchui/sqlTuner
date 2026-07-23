package com.codex.sqltuner.conversation;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;

public class Conversation {
    private Long id;
    private Long userId;
    private String title;
    private String contextSnapshot;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Conversation() {
    }

    public Conversation(Long id, Long userId, String title, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, userId, title, null, createdAt, updatedAt);
    }

    public Conversation(Long id, Long userId, String title, String contextSnapshot, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.userId = userId;
        this.title = title;
        this.contextSnapshot = contextSnapshot;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    @JsonIgnore
    public String getContextSnapshot() {
        return contextSnapshot;
    }

    public void setContextSnapshot(String contextSnapshot) {
        this.contextSnapshot = contextSnapshot;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
