package com.codex.sqltuner.tuning;

import com.codex.sqltuner.common.NotFoundException;
import com.codex.sqltuner.config.QueueProperties;
import com.codex.sqltuner.storage.JdbcJsonSupport;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class TuningTaskRepository {
    private final JdbcTemplate jdbcTemplate;
    private final JdbcJsonSupport jsonSupport;
    private final QueueProperties queueProperties;

    public TuningTaskRepository(JdbcTemplate jdbcTemplate, JdbcJsonSupport jsonSupport, QueueProperties queueProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonSupport = jsonSupport;
        this.queueProperties = queueProperties;
    }

    public SqlTuningTask create(SqlTuningTask task) {
        return create(task, null);
    }

    @Transactional
    public SqlTuningTask create(final SqlTuningTask task, final String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.trim().isEmpty()) {
            SqlTuningTask existing = findByIdempotencyKey(task.getUserId(), idempotencyKey.trim());
            if (existing != null) {
                return existing;
            }
        }
        LocalDateTime now = LocalDateTime.now();
        task.setStatus(TaskStatus.QUEUED);
        task.setStatusMessage("已进入调优队列");
        task.setQueuedAt(now);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        task.setVersion(0L);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(functionConnection(task, idempotencyKey, now), keyHolder);
        } catch (DuplicateKeyException e) {
            SqlTuningTask existing = findByIdempotencyKey(task.getUserId(), idempotencyKey);
            if (existing != null) {
                return existing;
            }
            throw e;
        }
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("创建任务失败: 未返回任务 ID");
        }
        task.setId(key.longValue());
        update(task);
        return task;
    }

    private org.springframework.jdbc.core.PreparedStatementCreator functionConnection(final SqlTuningTask task, final String idempotencyKey, final LocalDateTime now) {
        return new org.springframework.jdbc.core.PreparedStatementCreator() {
            @Override
            public PreparedStatement createPreparedStatement(Connection con) throws java.sql.SQLException {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO tuning_tasks(user_id, conversation_id, idempotency_key, status, status_message, task_json, queued_at, next_attempt_at, version, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, task.getUserId());
                ps.setLong(2, task.getConversationId());
                ps.setString(3, idempotencyKey == null || idempotencyKey.trim().isEmpty() ? null : idempotencyKey.trim());
                ps.setString(4, task.getStatus().name());
                ps.setString(5, task.getStatusMessage());
                ps.setString(6, jsonSupport.write(task));
                ps.setTimestamp(7, toTimestamp(now));
                ps.setTimestamp(8, toTimestamp(now));
                ps.setTimestamp(9, toTimestamp(now));
                ps.setTimestamp(10, toTimestamp(now));
                return ps;
            }
        };
    }

    public SqlTuningTask getForUser(final Long id, final Long userId) {
        List<SqlTuningTask> tasks = jdbcTemplate.query(
                "SELECT * FROM tuning_tasks WHERE id = ? AND user_id = ?",
                mapper(), id, userId);
        if (tasks.isEmpty()) {
            throw new NotFoundException("调优任务不存在");
        }
        return tasks.get(0);
    }

    public SqlTuningTask get(Long id) {
        List<SqlTuningTask> tasks = jdbcTemplate.query("SELECT * FROM tuning_tasks WHERE id = ?", mapper(), id);
        if (tasks.isEmpty()) {
            throw new NotFoundException("调优任务不存在");
        }
        return tasks.get(0);
    }

    public SqlTuningTask findByIdempotencyKey(Long userId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            return null;
        }
        List<SqlTuningTask> tasks = jdbcTemplate.query(
                "SELECT * FROM tuning_tasks WHERE user_id = ? AND idempotency_key = ?",
                mapper(), userId, idempotencyKey.trim());
        return tasks.isEmpty() ? null : tasks.get(0);
    }

    public void lockQueueAdmission() {
        jdbcTemplate.queryForObject(
                "SELECT id FROM queue_admission_lock WHERE id = 1 FOR UPDATE", Integer.class);
    }

    @Transactional
    public void update(final SqlTuningTask task) {
        LocalDateTime now = LocalDateTime.now();
        task.setUpdatedAt(now);
        long expectedVersion = task.getVersion();
        long nextVersion = expectedVersion + 1L;
        task.setVersion(nextVersion);
        int updated = jdbcTemplate.update(
                "UPDATE tuning_tasks SET status = ?, status_message = ?, task_json = ?, lease_owner = ?, lease_until = ?, "
                        + "attempt_count = ?, next_attempt_at = ?, last_error_code = ?, version = ?, updated_at = ? "
                        + "WHERE id = ? AND version = ?",
                task.getStatus().name(),
                task.getStatusMessage(),
                jsonSupport.write(task),
                task.getLeaseOwner(),
                toTimestamp(task.getLeaseUntil()),
                task.getAttemptCount(),
                toTimestamp(task.getNextAttemptAt()),
                task.getLastErrorCode(),
                task.getVersion(),
                toTimestamp(now),
                task.getId(),
                expectedVersion);
        if (updated != 1) {
            task.setVersion(expectedVersion);
            throw new OptimisticLockingFailureException("任务状态已被其他 worker 更新, taskId: " + task.getId());
        }
        syncArtifacts(task);
    }

    public int queuedCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tuning_tasks WHERE status = 'QUEUED'", Integer.class);
        return count == null ? 0 : count;
    }

    public int queuedCountForUser(Long userId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tuning_tasks WHERE status = 'QUEUED' AND user_id = ?", Integer.class, userId);
        return count == null ? 0 : count;
    }

    public int runningCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tuning_tasks WHERE status NOT IN ('QUEUED','DONE','FAILED')",
                Integer.class);
        return count == null ? 0 : count;
    }

    public int queuePosition(SqlTuningTask task) {
        if (task.getQueuedAt() == null || task.getStatus() != TaskStatus.QUEUED) {
            return 0;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tuning_tasks WHERE status = 'QUEUED' AND queued_at <= ?",
                Integer.class, toTimestamp(task.getQueuedAt()));
        return count == null ? 0 : count;
    }

    @Transactional
    public SqlTuningTask claimNext(String leaseOwner) {
        if (runningCount() >= queueProperties.getMaxRunning()) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        List<Long> ids;
        try {
            ids = jdbcTemplate.queryForList(
                    "SELECT id FROM tuning_tasks WHERE status = 'QUEUED' AND (next_attempt_at IS NULL OR next_attempt_at <= ?) "
                            + "ORDER BY queued_at ASC LIMIT 1 FOR UPDATE SKIP LOCKED",
                    Long.class, toTimestamp(now));
        } catch (BadSqlGrammarException e) {
            ids = jdbcTemplate.queryForList(
                    "SELECT id FROM tuning_tasks WHERE status = 'QUEUED' AND (next_attempt_at IS NULL OR next_attempt_at <= ?) "
                            + "ORDER BY queued_at ASC LIMIT 1 FOR UPDATE",
                    Long.class, toTimestamp(now));
        }
        if (ids.isEmpty()) {
            return null;
        }
        SqlTuningTask task = get(ids.get(0));
        task.setLeaseOwner(leaseOwner);
        task.setLeaseUntil(now.plusSeconds(queueProperties.getLeaseSeconds()));
        task.setAttemptCount(task.getAttemptCount() + 1);
        task.setStatus(TaskStatus.RECEIVED);
        task.setStatusMessage("worker 已领取任务");
        update(task);
        return task;
    }

    public void requeueExpiredLeases() {
        LocalDateTime now = LocalDateTime.now();
        List<SqlTuningTask> expired = jdbcTemplate.query(
                "SELECT * FROM tuning_tasks WHERE status NOT IN ('QUEUED','DONE','FAILED') AND lease_until < ?",
                mapper(), toTimestamp(now));
        for (SqlTuningTask task : expired) {
            task.setStatus(TaskStatus.QUEUED);
            task.setStatusMessage("租约过期，已重新排队");
            task.setLeaseOwner(null);
            task.setLeaseUntil(null);
            task.setNextAttemptAt(now);
            update(task);
        }
    }

    public void heartbeat(Long taskId, String leaseOwner) {
        LocalDateTime leaseUntil = LocalDateTime.now().plusSeconds(queueProperties.getLeaseSeconds());
        jdbcTemplate.update(
                "UPDATE tuning_tasks SET lease_until = ?, updated_at = ? WHERE id = ? AND lease_owner = ? AND status NOT IN ('DONE','FAILED')",
                toTimestamp(leaseUntil), toTimestamp(LocalDateTime.now()), taskId, leaseOwner);
    }

    public int renewLeases(String leaseOwner) {
        LocalDateTime now = LocalDateTime.now();
        return jdbcTemplate.update(
                "UPDATE tuning_tasks SET lease_until = ?, updated_at = ? "
                        + "WHERE lease_owner = ? AND status NOT IN ('DONE','FAILED')",
                toTimestamp(now.plusSeconds(queueProperties.getLeaseSeconds())), toTimestamp(now), leaseOwner);
    }

    public void releaseLease(SqlTuningTask task) {
        task.setLeaseOwner(null);
        task.setLeaseUntil(null);
        update(task);
    }

    /**
     * 失败路径不再重写完整 task_json，也不依赖调用方持有的旧 version。
     * 这样即使主流程在序列化、产物同步或超长异常文本上失败，任务也能可靠进入重试或终态。
     */
    public SqlTuningTask markAfterFailure(Long taskId,
                                          TaskStatus status,
                                          String statusMessage,
                                          String errorCode,
                                          LocalDateTime nextAttemptAt) {
        if (status != TaskStatus.QUEUED && status != TaskStatus.FAILED) {
            throw new IllegalArgumentException("失败恢复状态只允许 QUEUED 或 FAILED");
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = jdbcTemplate.update(
                "UPDATE tuning_tasks SET status = ?, status_message = ?, lease_owner = NULL, lease_until = NULL, "
                        + "next_attempt_at = ?, last_error_code = ?, version = version + 1, updated_at = ? WHERE id = ?",
                status.name(), statusMessage, toTimestamp(nextAttemptAt), errorCode, toTimestamp(now), taskId);
        if (updated != 1) {
            throw new IllegalStateException("任务失败状态写回失败, taskId: " + taskId);
        }
        return get(taskId);
    }

    private void syncArtifacts(SqlTuningTask task) {
        if (task.getArtifacts() == null) {
            return;
        }
        Integer persistedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_artifacts WHERE task_id = ?", Integer.class, task.getId());
        int start = persistedCount == null ? 0 : persistedCount;
        for (int i = start; i < task.getArtifacts().size(); i++) {
            HarnessArtifact artifact = task.getArtifacts().get(i);
            jdbcTemplate.update(
                    "INSERT INTO task_artifacts(task_id, node_name, summary, payload_json, created_at) VALUES (?, ?, ?, ?, ?)",
                    task.getId(),
                    artifact.getNodeName(),
                    artifact.getSummary(),
                    artifact.getPayload() == null ? null : jsonSupport.write(artifact.getPayload()),
                    toTimestamp(artifact.getCreatedAt() == null ? task.getUpdatedAt() : artifact.getCreatedAt()));
        }
    }

    private RowMapper<SqlTuningTask> mapper() {
        return new RowMapper<SqlTuningTask>() {
            @Override
            public SqlTuningTask mapRow(ResultSet rs, int rowNum) throws java.sql.SQLException {
                SqlTuningTask task = jsonSupport.read(rs.getString("task_json"), SqlTuningTask.class);
                task.setId(rs.getLong("id"));
                task.setUserId(rs.getLong("user_id"));
                task.setConversationId(rs.getLong("conversation_id"));
                task.setStatus(TaskStatus.valueOf(rs.getString("status")));
                task.setStatusMessage(rs.getString("status_message"));
                task.setQueuedAt(toLocalDateTime(rs.getTimestamp("queued_at")));
                task.setLeaseOwner(rs.getString("lease_owner"));
                task.setLeaseUntil(toLocalDateTime(rs.getTimestamp("lease_until")));
                task.setAttemptCount(rs.getInt("attempt_count"));
                task.setNextAttemptAt(toLocalDateTime(rs.getTimestamp("next_attempt_at")));
                task.setLastErrorCode(rs.getString("last_error_code"));
                task.setVersion(rs.getLong("version"));
                task.setCreatedAt(toLocalDateTime(rs.getTimestamp("created_at")));
                task.setUpdatedAt(toLocalDateTime(rs.getTimestamp("updated_at")));
                return task;
            }
        };
    }

    private static Timestamp toTimestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static LocalDateTime toLocalDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
