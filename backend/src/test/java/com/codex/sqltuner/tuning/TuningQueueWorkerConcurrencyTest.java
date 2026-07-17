package com.codex.sqltuner.tuning;

import com.codex.sqltuner.config.QueueProperties;
import com.codex.sqltuner.conversation.Conversation;
import com.codex.sqltuner.conversation.ConversationRepository;
import com.codex.sqltuner.storage.JdbcJsonSupport;
import com.codex.sqltuner.storage.JdbcTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class TuningQueueWorkerConcurrencyTest {
    @Test
    void dispatchRunsFourTasksAndLeavesFifthQueued() throws Exception {
        JdbcTemplate jdbcTemplate = JdbcTestSupport.jdbcTemplate();
        QueueProperties queueProperties = new QueueProperties();
        queueProperties.setWorkerCount(4);
        queueProperties.setMaxRunning(4);
        TuningTaskRepository repository = repository(jdbcTemplate, queueProperties);
        List<SqlTuningTask> tasks = createQueuedTasks(jdbcTemplate, repository, 5);

        CountDownLatch fourStarted = new CountDownLatch(4);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        CountDownLatch fourCompleted = new CountDownLatch(4);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        TuningHarnessService harnessService = mock(TuningHarnessService.class);
        doAnswer(invocation -> {
            try {
                int current = running.incrementAndGet();
                maxConcurrent.updateAndGet(previous -> Math.max(previous, current));
                fourStarted.countDown();
                assertThat(releaseWorkers.await(10, TimeUnit.SECONDS)).isTrue();
                running.decrementAndGet();
            } finally {
                fourCompleted.countDown();
            }
            return null;
        }).when(harnessService).run(any(SqlTuningTask.class));

        TuningQueueWorker worker = new TuningQueueWorker(repository, harnessService, new TaskEventBroker(), queueProperties);
        try {
            worker.dispatch();
            assertThat(fourStarted.await(5, TimeUnit.SECONDS)).isTrue();

            Integer received = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tuning_tasks WHERE status = 'RECEIVED'", Integer.class);
            Integer queued = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tuning_tasks WHERE status = 'QUEUED'", Integer.class);
            assertThat(received).isEqualTo(4);
            assertThat(queued).isEqualTo(1);
            assertThat(repository.get(tasks.get(4).getId()).getStatus()).isEqualTo(TaskStatus.QUEUED);
            assertThat(maxConcurrent.get()).isEqualTo(4);
        } finally {
            releaseWorkers.countDown();
            assertThat(fourCompleted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(waitUntilWorkersReleasedLeases(jdbcTemplate)).isTrue();
            worker.shutdown();
        }
    }

    @Test
    void failureWithOversizedExceptionStillReachesTerminalState() throws Exception {
        JdbcTemplate jdbcTemplate = JdbcTestSupport.jdbcTemplate();
        QueueProperties queueProperties = new QueueProperties();
        queueProperties.setWorkerCount(1);
        queueProperties.setMaxRunning(1);
        TuningTaskRepository repository = repository(jdbcTemplate, queueProperties);
        SqlTuningTask task = createQueuedTasks(jdbcTemplate, repository, 1).get(0);
        TuningHarnessService harnessService = mock(TuningHarnessService.class);
        doThrow(new IllegalStateException(repeat("model-output-fragment ", 100)))
                .when(harnessService).run(any(SqlTuningTask.class));

        TuningQueueWorker worker = new TuningQueueWorker(repository, harnessService, new TaskEventBroker(), queueProperties);
        try {
            worker.dispatch();
            SqlTuningTask failed = waitForTerminal(repository, task.getId());

            assertThat(failed.getStatus()).isEqualTo(TaskStatus.FAILED);
            assertThat(failed.getStatusMessage()).hasSizeLessThanOrEqualTo(512);
            assertThat(failed.getLeaseOwner()).isNull();
            assertThat(failed.getLeaseUntil()).isNull();
            assertThat(failed.getLastErrorCode()).isEqualTo("TASK_FAILED");
        } finally {
            worker.shutdown();
        }
    }

    @Test
    void jsonParseFailureContainingNetworkTextIsNotRetried() throws Exception {
        JdbcTemplate jdbcTemplate = JdbcTestSupport.jdbcTemplate();
        QueueProperties queueProperties = new QueueProperties();
        queueProperties.setWorkerCount(1);
        queueProperties.setMaxRunning(1);
        TuningTaskRepository repository = repository(jdbcTemplate, queueProperties);
        SqlTuningTask task = createQueuedTasks(jdbcTemplate, repository, 1).get(0);
        TuningHarnessService harnessService = mock(TuningHarnessService.class);
        com.fasterxml.jackson.core.JsonParseException parseFailure =
                new com.fasterxml.jackson.core.JsonParseException(null, "network 字样来自模型 JSON，不是网络异常");
        doThrow(new IllegalStateException("模型修复输出不是合法 JSON", parseFailure))
                .when(harnessService).run(any(SqlTuningTask.class));

        TuningQueueWorker worker = new TuningQueueWorker(repository, harnessService, new TaskEventBroker(), queueProperties);
        try {
            worker.dispatch();
            SqlTuningTask failed = waitForTerminal(repository, task.getId());

            assertThat(failed.getStatus()).isEqualTo(TaskStatus.FAILED);
            assertThat(failed.getAttemptCount()).isEqualTo(1);
            assertThat(failed.getLastErrorCode()).isEqualTo("TASK_FAILED");
        } finally {
            worker.shutdown();
        }
    }

    private boolean waitUntilWorkersReleasedLeases(JdbcTemplate jdbcTemplate) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            Integer released = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tuning_tasks WHERE status = 'RECEIVED' AND lease_owner IS NULL",
                    Integer.class);
            if (released != null && released == 4) {
                return true;
            }
            Thread.sleep(25L);
        }
        return false;
    }

    private SqlTuningTask waitForTerminal(TuningTaskRepository repository, Long taskId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            SqlTuningTask task = repository.get(taskId);
            if (task.getStatus() == TaskStatus.DONE || task.getStatus() == TaskStatus.FAILED) {
                return task;
            }
            Thread.sleep(25L);
        }
        return repository.get(taskId);
    }

    private String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private List<SqlTuningTask> createQueuedTasks(JdbcTemplate jdbcTemplate,
                                                  TuningTaskRepository repository,
                                                  int count) {
        ConversationRepository conversations = new ConversationRepository(jdbcTemplate);
        Conversation conversation = conversations.create(1L, "queue-concurrency");
        List<SqlTuningTask> tasks = new ArrayList<SqlTuningTask>();
        for (int i = 0; i < count; i++) {
            SqlTuningTask task = new SqlTuningTask();
            task.setUserId(1L);
            task.setConversationId(conversation.getId());
            task.setDbDialect("OceanBase/MySQL");
            task.setInputType("sql");
            task.setOriginalSql("select " + i);
            tasks.add(repository.create(task, "worker-" + i));
        }
        return tasks;
    }

    private TuningTaskRepository repository(JdbcTemplate jdbcTemplate, QueueProperties queueProperties) {
        return new TuningTaskRepository(jdbcTemplate, new JdbcJsonSupport(objectMapper()), queueProperties);
    }

    private ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return objectMapper;
    }
}
