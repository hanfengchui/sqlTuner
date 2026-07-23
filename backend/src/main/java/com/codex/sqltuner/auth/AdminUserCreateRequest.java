package com.codex.sqltuner.auth;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

public class AdminUserCreateRequest {
    @NotBlank(message = "用户名不能为空")
    @Size(max = 64, message = "用户名不能超过 64 位")
    private String username;

    @Size(max = 128, message = "显示名称不能超过 128 位")
    private String displayName;

    @NotBlank(message = "密码不能为空")
    @Size(min = 12, message = "密码长度不能少于 12 位")
    private String password;

    @NotNull(message = "角色不能为空")
    private UserRole role;

    private Boolean enabled;

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

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
}
