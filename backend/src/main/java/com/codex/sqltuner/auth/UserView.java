package com.codex.sqltuner.auth;

public class UserView {
    private Long id;
    private String username;
    private String displayName;
    private UserRole role;

    public UserView() {
    }

    public UserView(Long id, String username, String displayName, UserRole role) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
        this.role = role;
    }

    public static UserView from(UserAccount account) {
        return new UserView(account.getId(), account.getUsername(), account.getDisplayName(), account.getRole());
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
}
