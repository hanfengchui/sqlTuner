package com.codex.sqltuner.llm;

public interface LlmClient {
    LlmResponse analyze(LlmRequest request);
}
