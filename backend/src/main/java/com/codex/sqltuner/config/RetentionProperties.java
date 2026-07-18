package com.codex.sqltuner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.retention")
public class RetentionProperties {
    private boolean enabled;
    private int conversationDays = 7;
    private int batchSize = 100;
    private int maxBatches = 10;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getConversationDays() {
        return conversationDays;
    }

    public void setConversationDays(int conversationDays) {
        this.conversationDays = conversationDays;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxBatches() {
        return maxBatches;
    }

    public void setMaxBatches(int maxBatches) {
        this.maxBatches = maxBatches;
    }
}
