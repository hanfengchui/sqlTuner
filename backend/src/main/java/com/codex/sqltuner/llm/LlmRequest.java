package com.codex.sqltuner.llm;

import java.util.ArrayList;
import java.util.List;

public class LlmRequest {
    private String systemPrompt;
    private String userPrompt;
    private boolean deepAnalysis;
    private boolean jsonOutput = true;
    private String modelOverride;
    private Integer maxTokens;
    private Integer callTimeoutMs;
    private List<LlmRequestImage> images = new ArrayList<LlmRequestImage>();

    public LlmRequest() {
    }

    public LlmRequest(String systemPrompt, String userPrompt, boolean deepAnalysis) {
        this.systemPrompt = systemPrompt;
        this.userPrompt = userPrompt;
        this.deepAnalysis = deepAnalysis;
    }

    public LlmRequest(String systemPrompt, String userPrompt, boolean deepAnalysis, String modelOverride, List<LlmRequestImage> images) {
        this.systemPrompt = systemPrompt;
        this.userPrompt = userPrompt;
        this.deepAnalysis = deepAnalysis;
        this.modelOverride = modelOverride;
        this.images = images == null ? new ArrayList<LlmRequestImage>() : images;
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

    public boolean isJsonOutput() {
        return jsonOutput;
    }

    public void setJsonOutput(boolean jsonOutput) {
        this.jsonOutput = jsonOutput;
    }

    public String getModelOverride() {
        return modelOverride;
    }

    public void setModelOverride(String modelOverride) {
        this.modelOverride = modelOverride;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Integer getCallTimeoutMs() {
        return callTimeoutMs;
    }

    public void setCallTimeoutMs(Integer callTimeoutMs) {
        this.callTimeoutMs = callTimeoutMs;
    }

    public List<LlmRequestImage> getImages() {
        return images;
    }

    public void setImages(List<LlmRequestImage> images) {
        this.images = images == null ? new ArrayList<LlmRequestImage>() : images;
    }

    public boolean hasImages() {
        return images != null && !images.isEmpty();
    }
}
