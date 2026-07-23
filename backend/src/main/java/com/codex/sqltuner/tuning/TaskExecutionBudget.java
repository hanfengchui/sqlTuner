package com.codex.sqltuner.tuning;

final class TaskExecutionBudget {
    private final long deadlineMs;

    TaskExecutionBudget(int budgetMs) {
        this.deadlineMs = System.currentTimeMillis() + Math.max(1, budgetMs);
    }

    int remainingMs() {
        long remaining = deadlineMs - System.currentTimeMillis();
        if (remaining <= 0L) {
            return 0;
        }
        return remaining > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remaining;
    }
}
