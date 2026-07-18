package com.codex.sqltuner.llm;

@FunctionalInterface
public interface LlmStreamListener {
    void onContent(String accumulatedContent, int receivedChars);
}
