package com.codex.sqltuner.tuning;

import com.codex.sqltuner.config.QueueProperties;
import com.codex.sqltuner.conversation.Conversation;
import com.codex.sqltuner.conversation.ConversationRepository;
import com.codex.sqltuner.storage.JdbcJsonSupport;
import com.codex.sqltuner.storage.JdbcTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TuningTaskLeaseAndVersionTest {
    @Test
    void expiredLeaseReturnsTaskToQueue() {
        JdbcTemplate jdbcTemplate = JdbcTestSupport.jdbcTemplate();
        TuningTaskRepository repository = repository(jdbcTemplate);
        SqlTuningTask queued = createQueuedTask(jdbcTemplate, repository);
        SqlTuningTask claimed = repository.claimNext("worker-a");
        jdbcTemplate.update("UPDATE tuning_tasks SET lease_until = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusSeconds(10)), claimed.getId());

        repository.requeueExpiredLeases();

        SqlTuningTask requeued = repository.get(queued.getId());
        assertThat(requeued.getStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThat(requeued.getLeaseOwner()).isNull();
        assertThat(requeued.getLeaseUntil()).isNull();
        assertThat(requeued.getNextAttemptAt()).isNotNull();
    }

    @Test
    void updateIncrementsTaskVersionForEveryPersistedStatusChange() {
        JdbcTemplate jdbcTemplate = JdbcTestSupport.jdbcTemplate();
        TuningTaskRepository repository = repository(jdbcTemplate);
        SqlTuningTask task = createQueuedTask(jdbcTemplate, repository);
        long createdVersion = repository.get(task.getId()).getVersion();

        task.setStatus(TaskStatus.SANITIZED);
        task.setStatusMessage("sanitized");
        repository.update(task);
        long sanitizedVersion = repository.get(task.getId()).getVersion();

        task.setStatus(TaskStatus.DONE);
        task.setStatusMessage("done");
        repository.update(task);
        long doneVersion = repository.get(task.getId()).getVersion();

        assertThat(sanitizedVersion).isGreaterThan(createdVersion);
        assertThat(doneVersion).isGreaterThan(sanitizedVersion);
    }

    @Test
    void staleVersionUpdateCannotOverwriteNewerTaskState() {
        JdbcTemplate jdbcTemplate = JdbcTestSupport.jdbcTemplate();
        TuningTaskRepository repository = repository(jdbcTemplate);
        SqlTuningTask task = createQueuedTask(jdbcTemplate, repository);
        SqlTuningTask firstCopy = repository.get(task.getId());
        SqlTuningTask staleCopy = repository.get(task.getId());

        firstCopy.setStatus(TaskStatus.DONE);
        firstCopy.setStatusMessage("done");
        repository.update(firstCopy);

        staleCopy.setStatus(TaskStatus.FAILED);
        staleCopy.setStatusMessage("stale failure");
        assertThatThrownBy(() -> repository.update(staleCopy))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(repository.get(task.getId()).getStatus()).isEqualTo(TaskStatus.DONE);
    }

    private SqlTuningTask createQueuedTask(JdbcTemplate jdbcTemplate, TuningTaskRepository repository) {
        ConversationRepository conversations = new ConversationRepository(jdbcTemplate);
        Conversation conversation = conversations.create(1L, "lease");
        SqlTuningTask task = new SqlTuningTask();
        task.setUserId(1L);
        task.setConversationId(conversation.getId());
        task.setDbDialect("OceanBase/MySQL");
        task.setInputType("sql");
        task.setOriginalSql("select 1");
        return repository.create(task);
    }

    private TuningTaskRepository repository(JdbcTemplate jdbcTemplate) {
        return new TuningTaskRepository(jdbcTemplate, new JdbcJsonSupport(objectMapper()), new QueueProperties());
    }

    private ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return objectMapper;
    }
}
