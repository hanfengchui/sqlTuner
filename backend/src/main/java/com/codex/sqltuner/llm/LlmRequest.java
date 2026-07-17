package com.codex.sqltuner.llm;

import java.util.ArrayList;
import java.util.List;

public class LlmRequest {
    private String systemPrompt;
    private String userPrompt;
    private boolean deepAnalysis;
    private String modelOverride;
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

    public String getModelOverride() {
        return modelOverride;
    }

    public void setModelOverride(String modelOverride) {
        this.modelOverride = modelOverride;
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
