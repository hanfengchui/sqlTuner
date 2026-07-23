package com.codex.sqltuner.auth;

import javax.validation.constraints.NotNull;

public class AdminUserEnabledRequest {
    @NotNull(message = "启用状态不能为空")
    private Boolean enabled;

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
}
