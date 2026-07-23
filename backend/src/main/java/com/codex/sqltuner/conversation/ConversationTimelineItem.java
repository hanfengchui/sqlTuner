package com.codex.sqltuner.conversation;

import com.codex.sqltuner.tuning.SqlTuningTask;

public class ConversationTimelineItem {
    private final Message message;
    private final SqlTuningTask task;

    public ConversationTimelineItem(Message message, SqlTuningTask task) {
        this.message = message;
        this.task = task;
    }

    public Message getMessage() {
        return message;
    }

    public SqlTuningTask getTask() {
        return task;
    }
}
