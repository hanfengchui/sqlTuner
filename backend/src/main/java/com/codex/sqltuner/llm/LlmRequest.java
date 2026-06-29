package com.codex.sqltuner.llm;

public class LlmRequest {
    private String systemPrompt;
    private String userPrompt;
    private boolean deepAnalysis;

    public LlmRequest() {
    }

    public LlmRequest(String systemPrompt, String userPrompt, boolean deepAnalysis) {
        this.systemPrompt = systemPrompt;
        this.userPrompt = userPrompt;
        this.deepAnalysis = deepAnalysis;
    }

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
