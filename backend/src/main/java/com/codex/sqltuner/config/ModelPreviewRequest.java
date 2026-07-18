package com.codex.sqltuner.config;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class ModelPreviewRequest {
    @Size(max = 8000, message = "系统提示不能超过 8 KiB")
    private String systemPrompt;

    @NotBlank(message = "试跑内容不能为空")
    @Size(max = 32768, message = "试跑内容不能超过 32 KiB")
    private String userPrompt;

    private boolean deepAnalysis;

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getUserPrompt() {
        return userPrompt;
    }

    public void setUserPrompt(String userPrompt) {
        this.userPrompt = userPrompt;
    }

    public boolean isDeepAnalysis() {
        return deepAnalysis;
    }

    public void setDeepAnalysis(boolean deepAnalysis) {
        this.deepAnalysis = deepAnalysis;
    }
}
