package com.codex.sqltuner.integration;

import com.codex.sqltuner.config.QueueProperties;
import com.codex.sqltuner.conversation.Conversation;
import com.codex.sqltuner.conversation.ConversationRepository;
import com.codex.sqltuner.conversation.MessageRole;
import com.codex.sqltuner.storage.JdbcJsonSupport;
import com.codex.sqltuner.tuning.HarnessArtifact;
import com.codex.sqltuner.tuning.SqlTuningTask;
import com.codex.sqltuner.tuning.TaskStageResult;
import com.codex.sqltuner.tuning.TaskStatus;
import com.codex.sqltuner.tuning.TuningTaskRepository;
import com.codex.sqltuner.tuning.inputimage.InputImageRepository;
import com.codex.sqltuner.tuning.inputimage.TaskInputImage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mysql-integration")
class MySqlPersistenceIT {
    private static MySQLContainer<?> mysql;

    private final JdbcJsonSupport jsonSupport = new JdbcJsonSupport(objectMapper());

    @BeforeAll
    static void startMySql() {
        try {
            if (!DockerClientFactory.instance().isDockerAvailable()) {
                dockerUnavailable(null);
            }
            mysql = new MySQLContainer<>("mysql:8.0.36")
                    .withDatabaseName("sqltuner")
                    .withUsername("sqltuner")
                    .withPassword("sqltuner");
            mysql.start();
        } catch (TestAbortedException e) {
            throw e;
        } catch (RuntimeException e) {
            dockerUnavailable(e);
        }
    }

    @AfterAll
    static void stopMySql() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    void flywayMigrationCreatesExpectedMySqlSchema() {
        Database database = migratedDatabase();

        Integer baselineMigration = database.jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND version = '8'", Integer.class);
        Integer migrations = database.jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class);
        String taskJsonType = columnType(database.jdbc, "tuning_tasks", "task_json");
        String artifactJsonType = columnType(database.jdbc, "task_artifacts", "payload_json");
        Integer queueLock = database.jdbc.queryForObject(
                "SELECT COUNT(*) FROM queue_admission_lock WHERE id = 1", Integer.class);

        assertThat(baselineMigration).isEqualTo(1);
        assertThat(migrations).isGreaterThanOrEqualTo(8);
        assertThat(taskJsonType).isEqualTo("json");
        assertThat(artifactJsonType).isEqualTo("json");
        assertThat(queueLock).isEqualTo(1);
    }

    @Test
    void skipLockedLetsSecondWorkerClaimUnlockedQueuedTask() {
        Database database = migratedDatabase();
        TuningTaskRepository tasks = taskRepository(database.jdbc);
        createQueuedTask(database.jdbc, tasks, "first", "claim-a");
        createQueuedTask(database.jdbc, tasks, "second", "claim-b");
        TransactionTemplate lockTransaction = transaction(database.dataSource, TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        Long lockedId = lockTransaction.execute(status -> {
            Long id = database.jdbc.queryForObject(
                    "SELECT id FROM tuning_tasks WHERE status = 'QUEUED' ORDER BY queued_at ASC LIMIT 1 FOR UPDATE",
                    Long.class);
            SqlTuningTask claimedByWorker = transaction(database.dataSource, TransactionDefinition.PROPAGATION_REQUIRES_NEW)
                    .execute(inner -> tasks.claimNext("worker-b"));
            assertThat(claimedByWorker).isNotNull();
            assertThat(claimedByWorker.getId()).isNotEqualTo(id);
            assertThat(claimedByWorker.getStatus()).isEqualTo(TaskStatus.RECEIVED);
            assertThat(claimedByWorker.getLeaseOwner()).isEqualTo("worker-b");
            return id;
        });

        assertThat(tasks.get(lockedId).getStatus()).isEqualTo(TaskStatus.QUEUED);
    }

    @Test
    void taskJsonArtifactsImagesAndStageResultsRoundTripInMySql() {
        Database database = migratedDatabase();
        TuningTaskRepository tasks = taskRepository(database.jdbc);
        InputImageRepository images = new InputImageRepository(database.jdbc);
        SqlTuningTask task = createQueuedTask(database.jdbc, tasks, "json-persistence", "idem-json");
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("sqlHash", "abc123");
        payload.put("warnings", Collections.singletonList("missing index"));

        task.setStatus(TaskStatus.DONE);
        task.setStatusMessage("done");
        task.setSanitizedSql("select * from orders where tenant_id = ?");
        task.getArtifacts().add(new HarnessArtifact("analysis", "分析完成", payload, LocalDateTime.now()));
        tasks.update(task);
        tasks.saveCompletedStageResult(task.getId(), "analysis", repeat('c', 64), "mock", "mock", "{\"ok\":true}", 15L, true);
        images.saveAll(task.getId(), Collections.singletonList(new TaskInputImage(
                task.getId(), 0, "plan.png", "image/png", new byte[]{1, 2, 3}, repeat('a', 64))));

        SqlTuningTask loaded = tasks.get(task.getId());
        TaskStageResult stage = tasks.findCompletedStageResult(task.getId(), "analysis", repeat('c', 64)).get();
        TaskInputImage image = images.findByTaskId(task.getId()).get(0);

        assertThat(loaded.getSanitizedSql()).contains("tenant_id");
        assertThat(loaded.getArtifacts()).hasSize(1);
        assertThat(loaded.getArtifacts().get(0).getPayload()).isInstanceOf(Map.class);
        assertThat(stage.getContent()).isEqualTo("{\"ok\":true}");
        assertThat(image.getImageData()).containsExactly(1, 2, 3);
    }

    @Test
    void leaseConditionsAndIdempotencyHoldOnMySql() {
        Database database = migratedDatabase();
        TuningTaskRepository tasks = taskRepository(database.jdbc);
        SqlTuningTask first = createQueuedTask(database.jdbc, tasks, "idempotent", "stable-key");
        SqlTuningTask duplicate = createQueuedTask(database.jdbc, tasks, "duplicate", "stable-key");

        assertThat(duplicate.getId()).isEqualTo(first.getId());
        SqlTuningTask claimed = tasks.claimNext("worker-a");
        claimed.setStatus(TaskStatus.LLM_ANALYZING);
        claimed.setStatusMessage("wrong owner");

        assertThatThrownBy(() -> tasks.updateForLease(claimed, "worker-b", claimed.getAttemptCount()))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(tasks.get(claimed.getId()).getLeaseOwner()).isEqualTo("worker-a");
    }

    @Test
    void retentionDeleteCascadesAllConversationOwnedRowsOnMySql() {
        Database database = migratedDatabase();
        ConversationRepository conversations = new ConversationRepository(database.jdbc);
        TuningTaskRepository tasks = taskRepository(database.jdbc);
        InputImageRepository images = new InputImageRepository(database.jdbc);
        LocalDateTime old = LocalDateTime.now().minusDays(8);
        Conversation conversation = conversations.create(1L, "old terminal");
        SqlTuningTask task = createQueuedTask(database.jdbc, tasks, conversation, "retention", null);
        conversations.addMessage(conversation.getId(), MessageRole.USER, "select 1", task.getId());
        task.setStatus(TaskStatus.DONE);
        task.setStatusMessage("done");
        task.getArtifacts().add(new HarnessArtifact("cleanup", "done", Collections.singletonMap("ok", true), old));
        tasks.update(task);
        tasks.saveCompletedStageResult(task.getId(), "cleanup", repeat('d', 64), "mock", "mock", "{}", 1L, true);
        images.saveAll(task.getId(), Collections.singletonList(new TaskInputImage(
                task.getId(), 0, "plan.png", "image/png", new byte[]{9}, repeat('b', 64))));
        database.jdbc.update("UPDATE conversations SET updated_at = ?, created_at = ? WHERE id = ?",
                Timestamp.valueOf(old), Timestamp.valueOf(old), conversation.getId());
        database.jdbc.update("UPDATE tuning_tasks SET updated_at = ?, created_at = ? WHERE id = ?",
                Timestamp.valueOf(old), Timestamp.valueOf(old), task.getId());

        int deleted = conversations.deleteExpiredTerminalConversations(LocalDateTime.now().minusDays(7), 10);

        assertThat(deleted).isEqualTo(1);
        assertCount(database.jdbc, "conversations", 0);
        assertCount(database.jdbc, "messages", 0);
        assertCount(database.jdbc, "tuning_tasks", 0);
        assertCount(database.jdbc, "task_artifacts", 0);
        assertCount(database.jdbc, "task_stage_results", 0);
        assertCount(database.jdbc, "task_input_images", 0);
    }

    private Database migratedDatabase() {
        String databaseName = "sqltuner_it_" + UUID.randomUUID().toString().replace("-", "");
        String serverUrl = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306)
                + "/?useSSL=false&allowPublicKeyRetrieval=true";
        DriverManagerDataSource serverDataSource = new DriverManagerDataSource();
        serverDataSource.setDriverClassName(mysql.getDriverClassName());
        serverDataSource.setUrl(serverUrl);
        serverDataSource.setUsername("root");
        serverDataSource.setPassword(mysql.getPassword());
        new JdbcTemplate(serverDataSource).execute("CREATE DATABASE `" + databaseName + "`");

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(mysql.getDriverClassName());
        dataSource.setUrl("jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/"
                + databaseName + "?useSSL=false&allowPublicKeyRetrieval=true");
        dataSource.setUsername(mysql.getUsername());
        dataSource.setPassword(mysql.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        seedUsers(jdbc);
        seedModel(jdbc);
        seedSkill(jdbc);
        return new Database(dataSource, jdbc);
    }

    private static void dockerUnavailable(RuntimeException cause) {
        String required = System.getProperty("sqltuner.integration.requireDocker");
        if (required == null || required.trim().isEmpty()) {
            required = System.getenv("SQLTUNER_REQUIRE_DOCKER");
        }
        String message = "Docker is not available for MySQL integration tests";
        if ("true".equalsIgnoreCase(required)) {
            throw cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
        }
        throw cause == null ? new TestAbortedException(message) : new TestAbortedException(message, cause);
    }

    private TuningTaskRepository taskRepository(JdbcTemplate jdbc) {
        return new TuningTaskRepository(jdbc, jsonSupport, new QueueProperties());
    }

    private SqlTuningTask createQueuedTask(JdbcTemplate jdbc,
                                           TuningTaskRepository tasks,
                                           String title,
                                           String idempotencyKey) {
        Conversation conversation = new ConversationRepository(jdbc).create(1L, title);
        return createQueuedTask(jdbc, tasks, conversation, title, idempotencyKey);
    }

    private SqlTuningTask createQueuedTask(JdbcTemplate jdbc,
                                           TuningTaskRepository tasks,
                                           Conversation conversation,
                                           String sqlSuffix,
                                           String idempotencyKey) {
        SqlTuningTask task = new SqlTuningTask();
        task.setUserId(1L);
        task.setConversationId(conversation.getId());
        task.setDbDialect("OceanBase/MySQL");
        task.setInputType("sql");
        task.setOriginalSql("select * from orders where name = '" + sqlSuffix + "'");
        return tasks.create(task, idempotencyKey);
    }

    private TransactionTemplate transaction(DataSource dataSource, int propagationBehavior) {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setPropagationBehavior(propagationBehavior);
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource), definition);
    }

    private String columnType(JdbcTemplate jdbc, String tableName, String columnName) {
        return jdbc.queryForObject(
                "SELECT DATA_TYPE FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                String.class, tableName, columnName);
    }

    private void seedUsers(JdbcTemplate jdbc) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO users(id, username, display_name, password_hash, role, enabled, created_at, updated_at) "
                        + "VALUES (1, 'admin', '管理员', 'hash', 'ADMIN', TRUE, ?, ?)",
                Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    private void seedModel(JdbcTemplate jdbc) {
        jdbc.update(
                "INSERT INTO model_config(id, provider, base_url, model, vision_model, encrypted_api_key, timeout_ms, updated_at) "
                        + "VALUES (1, 'mock', '', 'mock', 'mock', NULL, 30000, ?)",
                Timestamp.valueOf(LocalDateTime.now()));
    }

    private void seedSkill(JdbcTemplate jdbc) {
        jdbc.update(
                "INSERT INTO skill_versions(name, version, content, enabled, updated_at) VALUES ('oceanbase-sql-tuning', 1, 'test skill', TRUE, ?)",
                Timestamp.valueOf(LocalDateTime.now()));
    }

    private void assertCount(JdbcTemplate jdbc, String table, int expected) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        assertThat(count).isEqualTo(expected);
    }

    private ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return objectMapper;
    }

    private String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private static final class Database {
        private final DriverManagerDataSource dataSource;
        private final JdbcTemplate jdbc;

        private Database(DriverManagerDataSource dataSource, JdbcTemplate jdbc) {
            this.dataSource = dataSource;
            this.jdbc = jdbc;
        }
    }
}
