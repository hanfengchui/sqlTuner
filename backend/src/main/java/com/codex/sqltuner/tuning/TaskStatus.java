package com.codex.sqltuner.tuning;

public enum TaskStatus {
    RECEIVED,
    SANITIZED,
    RULE_CHECKED,
    LLM_ANALYZING,
    REVIEWING,
    DONE,
    FAILED
}
