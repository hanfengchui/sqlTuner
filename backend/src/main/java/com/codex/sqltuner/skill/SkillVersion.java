package com.codex.sqltuner.skill;

import java.time.LocalDateTime;

public class SkillVersion {
    private Long id;
    private String name;
    private Integer version;
    private String content;
    private boolean enabled;
    private LocalDateTime updatedAt;

    public SkillVersion() {
    }

    public SkillVersion(Long id, String name, Integer version, String content, boolean enabled, LocalDateTime updatedAt) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.content = content;
        this.enabled = enabled;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
