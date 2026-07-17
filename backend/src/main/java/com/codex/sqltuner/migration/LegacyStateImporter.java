package com.codex.sqltuner.migration;

import com.codex.sqltuner.conversation.Conversation;
import com.codex.sqltuner.conversation.Message;
import com.codex.sqltuner.skill.SkillVersion;
import com.codex.sqltuner.skill.SkillPromptPolicy;
import com.codex.sqltuner.storage.CryptoSupport;
import com.codex.sqltuner.storage.JdbcJsonSupport;
import com.codex.sqltuner.storage.ModelConfigRecord;
import com.codex.sqltuner.storage.PersistentAppState;
import com.codex.sqltuner.tuning.HarnessArtifact;
import com.codex.sqltuner.tuning.SqlTuningTask;
import com.codex.sqltuner.tuning.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class LegacyStateImporter {
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final JdbcJsonSupport jsonSupport;
    private final CryptoSupport cryptoSupport;
    private final PasswordEncoder passwordEncoder;

    public LegacyStateImporter(JdbcTemplate jdbcTemplate,
                               PlatformTransactionManager transactionManager,
                               ObjectMapper objectMapper,
                               JdbcJsonSupport jsonSupport,
                               CryptoSupport cryptoSupport,
                               PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.jsonSupport = jsonSupport;
        this.cryptoSupport = cryptoSupport;
        this.passwordEncoder = passwordEncoder;
    }

    public LegacyImportResult importFile(Path source,
                                         boolean dryRun,
                                         String adminPassword,
                                         String userPassword) {
        byte[] bytes = readBytes(source);
        String sha256 = sha256(bytes);
        PersistentAppState state = readState(bytes);
        normalize(state);
        validatePasswords(adminPassword, userPassword);
        ImportPlan plan = buildPlan(state, sha256, source);
        if (dryRun) {
            return new LegacyImportResult(sha256, true, false, plan.counts);
        }
        return transactionTemplate.execute(status -> importInTransaction(plan, adminPassword, userPassword));
    }

    private LegacyImportResult importInTransaction(ImportPlan plan, String adminPassword, String userPassword) {
        Integer sameSource = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM migration_records WHERE source_sha256 = ?",
                Integer.class,
                plan.sha256);
        if (sameSource != null && sameSource > 0) {
            return new LegacyImportResult(plan.sha256, false, true, plan.counts);
        }
        Integer previousMigrations = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM migration_records", Integer.class);
        if (previousMigrations != null && previousMigrations > 0) {
            throw new IllegalStateException("检测到不同来源的迁移记录，拒绝自动覆盖");
        }

        require(count("conversations") == 0L && count("messages") == 0L
                        && count("tuning_tasks") == 0L && count("task_artifacts") == 0L,
                "目标数据库已存在业务数据，拒绝导入覆盖");

        upsertUser(1L, "admin", "管理员", "ADMIN", adminPassword);
        upsertUser(2L, "user", "业务用户", "USER", userPassword);
        insertConversations(plan.state);
        insertMessages(plan.state);
        insertTasksAndArtifacts(plan.state);
        jdbcTemplate.update("DELETE FROM skill_versions");
        insertSkills(plan.state);
        insertModelConfig(plan.state);
        verifyImportedCounts(plan);
        jdbcTemplate.update(
                "INSERT INTO migration_records(source_sha256, source_path, dry_run, imported_counts_json, created_at) VALUES (?, ?, FALSE, ?, ?)",
                plan.sha256,
                plan.source.toAbsolutePath().toString(),
                jsonSupport.write(plan.counts),
                Timestamp.valueOf(LocalDateTime.now()));
        return new LegacyImportResult(plan.sha256, false, false, plan.counts);
    }

    private void upsertUser(Long id, String username, String displayName, String role, String password) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, id);
        String hash = passwordEncoder.encode(password);
        LocalDateTime now = LocalDateTime.now();
        if (count != null && count > 0) {
            jdbcTemplate.update(
                    "UPDATE users SET username = ?, display_name = ?, password_hash = ?, role = ?, enabled = TRUE, updated_at = ? WHERE id = ?",
                    username, displayName, hash, role, Timestamp.valueOf(now), id);
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO users(id, username, display_name, password_hash, role, enabled, created_at, updated_at) VALUES (?, ?, ?, ?, ?, TRUE, ?, ?)",
                id, username, displayName, hash, role, Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    private void insertConversations(PersistentAppState state) {
        for (Conversation conversation : state.getConversations().values()) {
            require(conversation.getId() != null, "会话 ID 不能为空");
            Long userId = conversation.getUserId() == null ? 2L : conversation.getUserId();
            jdbcTemplate.update(
                    "INSERT INTO conversations(id, user_id, title, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                    conversation.getId(),
                    userId,
                    defaultText(conversation.getTitle(), "历史调优会话"),
                    ts(defaultTime(conversation.getCreatedAt())),
                    ts(defaultTime(conversation.getUpdatedAt())));
        }
    }

    private void insertMessages(PersistentAppState state) {
        for (Map.Entry<Long, List<Message>> entry : state.getMessages().entrySet()) {
            List<Message> messages = entry.getValue() == null ? new ArrayList<Message>() : entry.getValue();
            for (Message message : messages) {
                require(message.getId() != null, "消息 ID 不能为空");
                require(message.getConversationId() != null, "消息会话 ID 不能为空");
                require(message.getRole() != null, "消息角色不能为空");
                jdbcTemplate.update(
                        "INSERT INTO messages(id, conversation_id, role, content, task_id, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                        message.getId(),
                        message.getConversationId(),
                        message.getRole().name(),
                        defaultText(message.getContent(), ""),
                        message.getTaskId(),
                        ts(defaultTime(message.getCreatedAt())));
            }
        }
    }

    private void insertTasksAndArtifacts(PersistentAppState state) {
        for (SqlTuningTask task : state.getTasks().values()) {
            require(task.getId() != null, "任务 ID 不能为空");
            require(task.getConversationId() != null, "任务会话 ID 不能为空");
            if (task.getStatus() == null) {
                task.setStatus(TaskStatus.DONE);
            }
            LocalDateTime createdAt = defaultTime(task.getCreatedAt());
            LocalDateTime updatedAt = defaultTime(task.getUpdatedAt());
            task.setCreatedAt(createdAt);
            task.setUpdatedAt(updatedAt);
            jdbcTemplate.update(
                    "INSERT INTO tuning_tasks(id, user_id, conversation_id, idempotency_key, status, status_message, task_json, queued_at, lease_owner, lease_until, attempt_count, next_attempt_at, last_error_code, version, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    task.getId(),
                    task.getUserId() == null ? 2L : task.getUserId(),
                    task.getConversationId(),
                    null,
                    task.getStatus().name(),
                    task.getStatusMessage(),
                    jsonSupport.write(task),
                    ts(task.getQueuedAt()),
                    task.getLeaseOwner(),
                    ts(task.getLeaseUntil()),
                    task.getAttemptCount(),
                    ts(task.getNextAttemptAt()),
                    task.getLastErrorCode(),
                    task.getVersion(),
                    ts(createdAt),
                    ts(updatedAt));
            insertArtifacts(task);
        }
    }

    private void insertArtifacts(SqlTuningTask task) {
        List<HarnessArtifact> artifacts = task.getArtifacts() == null ? new ArrayList<HarnessArtifact>() : task.getArtifacts();
        for (HarnessArtifact artifact : artifacts) {
            jdbcTemplate.update(
                    "INSERT INTO task_artifacts(task_id, node_name, summary, payload_json, created_at) VALUES (?, ?, ?, ?, ?)",
                    task.getId(),
                    defaultText(artifact.getNodeName(), "legacy"),
                    defaultText(artifact.getSummary(), "legacy artifact"),
                    jsonSupport.write(artifact),
                    ts(defaultTime(artifact.getCreatedAt())));
        }
    }

    private void insertSkills(PersistentAppState state) {
        for (SkillVersion skill : state.getSkills().values()) {
            require(skill.getName() != null, "技能名称不能为空");
            require(skill.getVersion() != null, "技能版本不能为空");
            jdbcTemplate.update(
                    "INSERT INTO skill_versions(id, name, version, content, enabled, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                    skill.getId(),
                    skill.getName(),
                    skill.getVersion(),
                    defaultText(skill.getContent(), ""),
                    skill.isEnabled(),
                    ts(defaultTime(skill.getUpdatedAt())));
        }
    }

    private void insertModelConfig(PersistentAppState state) {
        ModelConfigRecord model = state.getModelConfig();
        if (model == null) {
            return;
        }
        String storedApiKey = model.getApiKey();
        String plainApiKey = cryptoSupport.decrypt(storedApiKey);
        require(!cryptoSupport.isEncrypted(storedApiKey) || plainApiKey != null,
                "SQL_TUNER_DATA_KEY 无法解密 legacy 模型 API Key");
        String encryptedApiKey = plainApiKey == null || plainApiKey.isEmpty() ? null : cryptoSupport.encrypt(plainApiKey);
        jdbcTemplate.update("DELETE FROM model_config WHERE id = 1");
        jdbcTemplate.update(
                "INSERT INTO model_config(id, provider, base_url, model, encrypted_api_key, timeout_ms, updated_at) VALUES (1, ?, ?, ?, ?, ?, ?)",
                defaultText(model.getProvider(), "mock"),
                defaultText(model.getBaseUrl(), ""),
                defaultText(model.getModel(), "mock"),
                encryptedApiKey,
                model.getTimeoutMs() == null ? 30000 : model.getTimeoutMs(),
                Timestamp.valueOf(LocalDateTime.now()));
    }

    private void verifyImportedCounts(ImportPlan plan) {
        assertCount("conversations", plan.counts.get("conversations"));
        assertCount("messages", plan.counts.get("messages"));
        assertCount("tuning_tasks", plan.counts.get("tasks"));
        assertCount("task_artifacts", plan.counts.get("artifacts"));
        assertCount("skill_versions", plan.counts.get("skills"));
        assertMaxId("conversations", maxConversationId(plan.state));
        assertMaxId("messages", maxMessageId(plan.state));
        assertMaxId("tuning_tasks", maxTaskId(plan.state));
        assertMaxId("skill_versions", maxSkillId(plan.state));
        Integer modelCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM model_config WHERE id = 1", Integer.class);
        require(modelCount != null && modelCount == 1, "模型配置导入校验失败");
    }

    private void assertCount(String table, Long expected) {
        Long actual = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        require(actual != null && actual.longValue() == expected.longValue(), table + " 数量校验失败");
    }

    private void assertMaxId(String table, long expected) {
        Long actual = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM " + table, Long.class);
        require(actual != null && actual.longValue() == expected, table + " 最大 ID 校验失败");
    }

    private ImportPlan buildPlan(PersistentAppState state, String sha256, Path source) {
        validateState(state);
        Map<String, Long> counts = new LinkedHashMap<String, Long>();
        counts.put("conversations", (long) state.getConversations().size());
        long messageCount = 0L;
        for (List<Message> messages : state.getMessages().values()) {
            messageCount += messages == null ? 0 : messages.size();
        }
        counts.put("messages", messageCount);
        counts.put("tasks", (long) state.getTasks().size());
        long artifactCount = 0L;
        for (SqlTuningTask task : state.getTasks().values()) {
            artifactCount += task.getArtifacts() == null ? 0 : task.getArtifacts().size();
        }
        counts.put("artifacts", artifactCount);
        counts.put("skills", (long) state.getSkills().size());
        counts.put("modelConfig", state.getModelConfig() == null ? 0L : 1L);
        return new ImportPlan(state, sha256, source, counts);
    }

    private void validateState(PersistentAppState state) {
        for (Conversation conversation : state.getConversations().values()) {
            require(conversation.getId() != null, "会话 ID 不能为空");
            require(conversation.getCreatedAt() != null && conversation.getUpdatedAt() != null,
                    "会话时间戳不能为空, id=" + conversation.getId());
        }
        for (List<Message> messages : state.getMessages().values()) {
            if (messages == null) {
                continue;
            }
            for (Message message : messages) {
                require(message.getId() != null && message.getConversationId() != null,
                        "消息 ID/会话 ID 不能为空");
                require(state.getConversations().containsKey(message.getConversationId()),
                        "消息引用不存在的会话, messageId=" + message.getId());
                require(message.getCreatedAt() != null, "消息时间戳不能为空, id=" + message.getId());
            }
        }
        for (SqlTuningTask task : state.getTasks().values()) {
            require(task.getId() != null && task.getConversationId() != null, "任务 ID/会话 ID 不能为空");
            require(state.getConversations().containsKey(task.getConversationId()),
                    "任务引用不存在的会话, taskId=" + task.getId());
            require(task.getCreatedAt() != null && task.getUpdatedAt() != null,
                    "任务时间戳不能为空, id=" + task.getId());
        }
        for (SkillVersion skill : state.getSkills().values()) {
            SkillPromptPolicy.requireValid(skill.getName(), skill.getContent());
            require(skill.getId() != null && skill.getVersion() != null && skill.getUpdatedAt() != null,
                    "技能 ID/版本/时间戳不能为空, name=" + skill.getName());
        }
        ModelConfigRecord model = state.getModelConfig();
        if (model != null && cryptoSupport.isEncrypted(model.getApiKey())) {
            require(cryptoSupport.decrypt(model.getApiKey()) != null,
                    "SQL_TUNER_DATA_KEY 无法解密 legacy 模型 API Key");
        }
    }

    private long count(String table) {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0L : value.longValue();
    }

    private void normalize(PersistentAppState state) {
        require(state != null, "状态 JSON 不能为空");
        if (state.getConversations() == null) {
            state.setConversations(new LinkedHashMap<Long, Conversation>());
        }
        if (state.getMessages() == null) {
            state.setMessages(new LinkedHashMap<Long, List<Message>>());
        }
        if (state.getTasks() == null) {
            state.setTasks(new LinkedHashMap<Long, SqlTuningTask>());
        }
        if (state.getSkills() == null) {
            state.setSkills(new LinkedHashMap<String, SkillVersion>());
        }
    }

    private PersistentAppState readState(byte[] bytes) {
        try {
            return objectMapper.readValue(bytes, PersistentAppState.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("legacy JSON 解析失败", e);
        }
    }

    private byte[] readBytes(Path source) {
        try {
            return Files.readAllBytes(source);
        } catch (IOException e) {
            throw new IllegalArgumentException("legacy JSON 读取失败: " + source, e);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(bytes);
            StringBuilder builder = new StringBuilder();
            for (byte b : hashed) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }

    private void validatePasswords(String adminPassword, String userPassword) {
        require(adminPassword != null && adminPassword.length() >= 12, "管理员密码长度必须至少 12 位");
        require(userPassword != null && userPassword.length() >= 12, "普通用户密码长度必须至少 12 位");
    }

    private String defaultText(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private LocalDateTime defaultTime(LocalDateTime value) {
        return value == null ? LocalDateTime.now() : value;
    }

    private Timestamp ts(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private long maxConversationId(PersistentAppState state) {
        long max = 0L;
        for (Conversation conversation : state.getConversations().values()) {
            max = Math.max(max, conversation.getId() == null ? 0L : conversation.getId());
        }
        return max;
    }

    private long maxMessageId(PersistentAppState state) {
        long max = 0L;
        for (List<Message> messages : state.getMessages().values()) {
            if (messages == null) {
                continue;
            }
            for (Message message : messages) {
                max = Math.max(max, message.getId() == null ? 0L : message.getId());
            }
        }
        return max;
    }

    private long maxTaskId(PersistentAppState state) {
        long max = 0L;
        for (SqlTuningTask task : state.getTasks().values()) {
            max = Math.max(max, task.getId() == null ? 0L : task.getId());
        }
        return max;
    }

    private long maxSkillId(PersistentAppState state) {
        long max = 0L;
        for (SkillVersion skill : state.getSkills().values()) {
            max = Math.max(max, skill.getId() == null ? 0L : skill.getId());
        }
        return max;
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static class ImportPlan {
        private final PersistentAppState state;
        private final String sha256;
        private final Path source;
        private final Map<String, Long> counts;

        private ImportPlan(PersistentAppState state, String sha256, Path source, Map<String, Long> counts) {
            this.state = state;
            this.sha256 = sha256;
            this.source = source;
            this.counts = counts;
        }
    }
}
