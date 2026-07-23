package com.codex.sqltuner.llm;

@FunctionalInterface
public interface LlmStreamListener {
    void onContent(String deltaContent, int receivedChars);
}
