package com.codex.sqltuner.conversation;

import com.codex.sqltuner.storage.JdbcTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationRetentionTest {
    @Test
    void deletesExpiredTerminalConversationAndAllOwnedData() {
        JdbcTemplate jdbc = JdbcTestSupport.jdbcTemplate();
        ConversationRepository repository = new ConversationRepository(jdbc);
        LocalDateTime old = LocalDateTime.now().minusDays(8);
        seedConversation(jdbc, 101L, old, "DONE");

        int deleted = repository.deleteExpiredTerminalConversations(LocalDateTime.now().minusDays(7), 100);

        assertThat(deleted).isEqualTo(1);
        assertCount(jdbc, "conversations", 0);
        assertCount(jdbc, "messages", 0);
        assertCount(jdbc, "tuning_tasks", 0);
        assertCount(jdbc, "task_artifacts", 0);
        assertCount(jdbc, "task_input_images", 0);
    }

    @Test
    void keepsRecentConversationsAndConversationsWithActiveTasks() {
        JdbcTemplate jdbc = JdbcTestSupport.jdbcTemplate();
        ConversationRepository repository = new ConversationRepository(jdbc);
        seedConversation(jdbc, 201L, LocalDateTime.now().minusDays(8), "QUEUED");
        seedConversation(jdbc, 202L, LocalDateTime.now().minusDays(6), "DONE");

        int deleted = repository.deleteExpiredTerminalConversations(LocalDateTime.now().minusDays(7), 100);

        assertThat(deleted).isZero();
        assertCount(jdbc, "conversations", 2);
        assertCount(jdbc, "tuning_tasks", 2);
    }

    @Test
    void lockedConversationCannotBeDeletedWhileUserContinuesIt() throws Exception {
        final JdbcTemplate jdbc = JdbcTestSupport.jdbcTemplate();
        final ConversationRepository repository = new ConversationRepository(jdbc);
        final TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getDataSource()));
        seedConversation(jdbc, 301L, LocalDateTime.now().minusDays(8), "DONE");

        final CountDownLatch conversationLocked = new CountDownLatch(1);
        final CountDownLatch releaseWriter = new CountDownLatch(1);
        final CountDownLatch cleanupStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = executor.submit(new Runnable() {
                @Override
                public void run() {
                    transaction.execute(status -> {
                        repository.getForUserForUpdate(301L, 1L);
                        conversationLocked.countDown();
                        await(releaseWriter);
                        jdbc.update("UPDATE conversations SET updated_at = ? WHERE id = ?",
                                Timestamp.valueOf(LocalDateTime.now()), 301L);
                        return null;
                    });
                }
            });
            assertThat(conversationLocked.await(2, TimeUnit.SECONDS)).isTrue();

            Future<Integer> cleanup = executor.submit(() -> {
                cleanupStarted.countDown();
                return transaction.execute(status -> repository.deleteExpiredTerminalConversations(
                        LocalDateTime.now().minusDays(7), 100));
            });
            assertThat(cleanupStarted.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(150L);
            assertThat(cleanup.isDone()).isFalse();

            releaseWriter.countDown();
            writer.get(2, TimeUnit.SECONDS);
            assertThat(cleanup.get(2, TimeUnit.SECONDS)).isZero();
            assertCount(jdbc, "conversations", 1);
            assertCount(jdbc, "tuning_tasks", 1);
        } finally {
            releaseWriter.countDown();
            executor.shutdownNow();
        }
    }

    private void seedConversation(JdbcTemplate jdbc, Long id, LocalDateTime updatedAt, String status) {
        Timestamp timestamp = Timestamp.valueOf(updatedAt);
        jdbc.update(
                "INSERT INTO conversations(id, user_id, title, created_at, updated_at) VALUES (?, 1, ?, ?, ?)",
                id, "retention-" + id, timestamp, timestamp);
        jdbc.update(
                "INSERT INTO tuning_tasks(id, user_id, conversation_id, status, status_message, task_json, "
                        + "attempt_count, version, created_at, updated_at) VALUES (?, 1, ?, ?, '', '{}', 0, 0, ?, ?)",
                id, id, status, timestamp, timestamp);
        jdbc.update(
                "INSERT INTO messages(conversation_id, role, content, task_id, created_at) VALUES (?, 'USER', 'test', ?, ?)",
                id, id, timestamp);
        jdbc.update(
                "INSERT INTO task_artifacts(task_id, node_name, summary, payload_json, created_at) "
                        + "VALUES (?, 'test', 'test', '{}', ?)",
                id, timestamp);
        jdbc.update(
                "INSERT INTO task_input_images(task_id, image_order, file_name, media_type, byte_size, sha256, image_data, created_at) "
                        + "VALUES (?, 0, 'plan.png', 'image/png', 1, ?, ?, ?)",
                id, repeat('a', 64), new byte[]{1}, timestamp);
    }

    private void assertCount(JdbcTemplate jdbc, String table, int expected) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        assertThat(count).isEqualTo(expected);
    }

    private String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待并发测试信号时被中断", e);
        }
    }
}
