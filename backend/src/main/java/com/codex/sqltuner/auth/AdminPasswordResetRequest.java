package com.codex.sqltuner.auth;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class AdminPasswordResetRequest {
    @NotBlank(message = "密码不能为空")
    @Size(min = 12, message = "密码长度不能少于 12 位")
    private String password;

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
