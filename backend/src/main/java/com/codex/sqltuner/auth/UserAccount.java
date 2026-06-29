package com.codex.sqltuner.auth;

public class UserAccount {
    private Long id;
    private String username;
    private String displayName;
    private String password;
    private UserRole role;

    public UserAccount() {
    }

    public UserAccount(Long id, String username, String displayName, String password, UserRole role) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
        this.password = password;
        this.role = role;
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

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }
}
