package com.codex.sqltuner.tuning;

public enum TaskStatus {
    QUEUED,
    RECEIVED,
    SANITIZED,
    CONTEXT_CHECKED,
    RULE_CHECKED,
    LLM_ANALYZING,
    VERIFYING,
    REVIEWING,
    DONE,
    FAILED
}
