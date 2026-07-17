package com.codex.sqltuner.llm;

public class LlmRequestImage {
    private String dataUrl;

    public LlmRequestImage() {
    }

    public LlmRequestImage(String dataUrl) {
        this.dataUrl = dataUrl;
    }

    public String getDataUrl() {
        return dataUrl;
    }

    public void setDataUrl(String dataUrl) {
        this.dataUrl = dataUrl;
    }
}
