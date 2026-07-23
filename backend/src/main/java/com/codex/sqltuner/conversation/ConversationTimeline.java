package com.codex.sqltuner.conversation;

import java.util.List;

public class ConversationTimeline {
    private final List<ConversationTimelineItem> items;
    private final Long nextBefore;
    private final boolean hasMore;

    public ConversationTimeline(List<ConversationTimelineItem> items, Long nextBefore, boolean hasMore) {
        this.items = items;
        this.nextBefore = nextBefore;
        this.hasMore = hasMore;
    }

    public List<ConversationTimelineItem> getItems() {
        return items;
    }

    public Long getNextBefore() {
        return nextBefore;
    }

    public boolean isHasMore() {
        return hasMore;
    }
}
