package com.codex.sqltuner.auth;

public class AdminUserView {
    private Long id;
    private String username;
    private String displayName;
    private UserRole role;
    private boolean enabled;

    public AdminUserView() {
    }

    public AdminUserView(Long id, String username, String displayName, UserRole role, boolean enabled) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
        this.role = role;
        this.enabled = enabled;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
