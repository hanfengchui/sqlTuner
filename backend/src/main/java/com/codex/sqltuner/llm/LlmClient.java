package com.codex.sqltuner.llm;

public interface LlmClient {
    LlmResponse analyze(LlmRequest request);

    default LlmResponse analyze(LlmRequest request, LlmStreamListener listener) {
        return analyze(request);
    }
}
