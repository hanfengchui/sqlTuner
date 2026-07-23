package com.codex.sqltuner.conversation;

import java.util.List;

public class ConversationPage {
    private final List<Conversation> items;
    private final Long nextBefore;
    private final boolean hasMore;

    public ConversationPage(List<Conversation> items, Long nextBefore, boolean hasMore) {
        this.items = items;
        this.nextBefore = nextBefore;
        this.hasMore = hasMore;
    }

    public List<Conversation> getItems() {
        return items;
    }

    public Long getNextBefore() {
        return nextBefore;
    }

    public boolean isHasMore() {
        return hasMore;
    }
}
