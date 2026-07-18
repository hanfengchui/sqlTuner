package com.codex.sqltuner.conversation;

import com.codex.sqltuner.config.RetentionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(name = "app.retention.enabled", havingValue = "true")
public class ConversationRetentionJob {
    private static final Logger log = LoggerFactory.getLogger(ConversationRetentionJob.class);
    private final ConversationRepository conversationRepository;
    private final RetentionProperties properties;

    public ConversationRetentionJob(ConversationRepository conversationRepository,
                                    RetentionProperties properties) {
        this.conversationRepository = conversationRepository;
        this.properties = properties;
    }

    @Scheduled(
            initialDelayString = "${app.retention.initial-delay-ms:60000}",
            fixedDelayString = "${app.retention.interval-ms:86400000}")
    public void deleteExpiredConversations() {
        int retentionDays = properties.getConversationDays();
        int batchSize = properties.getBatchSize();
        int maxBatches = properties.getMaxBatches();
        if (retentionDays < 1 || batchSize < 1 || maxBatches < 1) {
            log.warn("deleteExpiredConversations result 结果: 配置无效, retentionDays: {}, batchSize: {}, maxBatches: {}",
                    retentionDays, batchSize, maxBatches);
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int totalDeleted = 0;
        int batches = 0;
        int deleted;
        do {
            deleted = conversationRepository.deleteExpiredTerminalConversations(cutoff, batchSize);
            totalDeleted += deleted;
            batches++;
        } while (deleted == batchSize && batches < maxBatches);

        log.info("deleteExpiredConversations result 结果: retentionDays: {}, batches: {}, deleted: {}",
                retentionDays, batches, totalDeleted);
    }
}
