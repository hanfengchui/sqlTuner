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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertCount(jdbc, "task_stage_results", 0);
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
    void manualDeleteRejectsConversationWithActiveTask() {
        JdbcTemplate jdbc = JdbcTestSupport.jdbcTemplate();
        ConversationRepository repository = new ConversationRepository(jdbc);
        seedConversation(jdbc, 251L, LocalDateTime.now(), "RECEIVED");

        assertThatThrownBy(() -> repository.deleteForUser(251L, 1L))
                .isInstanceOf(com.codex.sqltuner.common.ApiException.class)
                .hasMessageContaining("任务结束后才能删除");
        assertCount(jdbc, "conversations", 1);
        assertCount(jdbc, "tuning_tasks", 1);
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

    @Test
    void pagesConversationSearchByUpdatedAtCursorWithoutSkippingOlderMatches() {
        JdbcTemplate jdbc = JdbcTestSupport.jdbcTemplate();
        ConversationRepository repository = new ConversationRepository(jdbc);
        LocalDateTime base = LocalDateTime.now().minusHours(4);
        seedConversation(jdbc, 401L, base.plusMinutes(5), "DONE", "订单-最早");
        seedConversation(jdbc, 402L, base.plusMinutes(30), "DONE", "库存-不匹配");
        seedConversation(jdbc, 403L, base.plusHours(2), "DONE", "订单-最新");
        seedConversation(jdbc, 404L, base.plusHours(1), "DONE", "订单-中间");

        ConversationPage first = repository.pageByUser(1L, "订单", null, 2);
        assertThat(first.getItems()).extracting("id").containsExactly(403L, 404L);
        assertThat(first.isHasMore()).isTrue();
        assertThat(first.getNextBefore()).isEqualTo(404L);

        ConversationPage second = repository.pageByUser(1L, "订单", first.getNextBefore(), 2);
        assertThat(second.getItems()).extracting("id").containsExactly(401L);
        assertThat(second.isHasMore()).isFalse();
        assertThat(second.getNextBefore()).isNull();
    }

    private void seedConversation(JdbcTemplate jdbc, Long id, LocalDateTime updatedAt, String status) {
        seedConversation(jdbc, id, updatedAt, status, "retention-" + id);
    }

    private void seedConversation(JdbcTemplate jdbc, Long id, LocalDateTime updatedAt, String status, String title) {
        Timestamp timestamp = Timestamp.valueOf(updatedAt);
        jdbc.update(
                "INSERT INTO conversations(id, user_id, title, created_at, updated_at) VALUES (?, 1, ?, ?, ?)",
                id, title, timestamp, timestamp);
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
                "INSERT INTO task_stage_results(task_id, stage_name, input_sha256, provider, model, content, elapsed_ms, mock, created_at) "
                        + "VALUES (?, 'analysis', ?, 'mock', 'mock', '{}', 1, FALSE, ?)",
                id, repeat('b', 64), timestamp);
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
