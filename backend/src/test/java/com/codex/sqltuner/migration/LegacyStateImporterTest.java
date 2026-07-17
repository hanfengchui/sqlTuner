package com.codex.sqltuner.migration;

import com.codex.sqltuner.conversation.Conversation;
import com.codex.sqltuner.conversation.Message;
import com.codex.sqltuner.conversation.MessageRole;
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
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyStateImporterTest {
    @TempDir
    Path tempDir;

    @Test
    void dryRunDoesNotWrite() throws Exception {
        Fixture fixture = fixture();
        Path source = writeState(fixture.mapper, sampleState());

        LegacyImportResult result = fixture.importer.importFile(source, true,
                "admin-password-123", "user-password-123");

        assertTrue(result.isDryRun());
        assertEquals(1L, result.getCounts().get("conversations"));
        assertEquals(0, fixture.count("users"));
        assertEquals(0, fixture.count("migration_records"));
    }

    @Test
    void firstImportPersistsStateAndEncryptedModelKey() throws Exception {
        Fixture fixture = fixture();
        Path source = writeState(fixture.mapper, sampleState());

        LegacyImportResult result = fixture.importer.importFile(source, false,
                "admin-password-123", "user-password-123");

        assertFalse(result.isNoOp());
        assertEquals(2, fixture.count("users"));
        assertEquals(1, fixture.count("conversations"));
        assertEquals(2, fixture.count("messages"));
        assertEquals(1, fixture.count("tuning_tasks"));
        assertEquals(1, fixture.count("task_artifacts"));
        assertEquals(1, fixture.count("skill_versions"));
        assertEquals(1, fixture.count("migration_records"));
        String encrypted = fixture.jdbc.queryForObject("SELECT encrypted_api_key FROM model_config WHERE id = 1", String.class);
        assertTrue(fixture.crypto.isEncrypted(encrypted));
        assertEquals("dashscope-secret", fixture.crypto.decrypt(encrypted));
    }

    @Test
    void sameFileImportIsNoOp() throws Exception {
        Fixture fixture = fixture();
        Path source = writeState(fixture.mapper, sampleState());

        fixture.importer.importFile(source, false, "admin-password-123", "user-password-123");
        LegacyImportResult second = fixture.importer.importFile(source, false,
                "admin-password-123", "user-password-123");

        assertTrue(second.isNoOp());
        assertEquals(1, fixture.count("migration_records"));
        assertEquals(1, fixture.count("tuning_tasks"));
    }

    @Test
    void differentSourceAfterImportFailsSafely() throws Exception {
        Fixture fixture = fixture();
        Path first = writeState(fixture.mapper, sampleState());
        PersistentAppState secondState = sampleState();
        secondState.getConversations().get(100L).setTitle("other source");
        Path second = writeState(fixture.mapper, secondState);

        fixture.importer.importFile(first, false, "admin-password-123", "user-password-123");

        assertThrows(IllegalStateException.class, () -> fixture.importer.importFile(second, false,
                "admin-password-123", "user-password-123"));
        assertEquals(1, fixture.count("migration_records"));
        assertEquals("历史会话", fixture.jdbc.queryForObject("SELECT title FROM conversations WHERE id = 100", String.class));
    }

    @Test
    void invalidStateRollsBackTransaction() throws Exception {
        Fixture fixture = fixture();
        PersistentAppState state = sampleState();
        state.getConversations().clear();
        Path source = writeState(fixture.mapper, state);

        assertThrows(RuntimeException.class, () -> fixture.importer.importFile(source, false,
                "admin-password-123", "user-password-123"));

        assertEquals(0, fixture.count("users"));
        assertEquals(0, fixture.count("tuning_tasks"));
        assertEquals(0, fixture.count("migration_records"));
    }

    @Test
    void oversizedLegacySkillIsRejectedBeforeImport() throws Exception {
        Fixture fixture = fixture();
        PersistentAppState state = sampleState();
        state.getSkills().values().iterator().next().setContent(repeat('x', SkillPromptPolicy.MAX_CONTENT_CHARS + 1));
        Path source = writeState(fixture.mapper, state);

        assertThrows(IllegalArgumentException.class, () -> fixture.importer.importFile(source, false,
                "admin-password-123", "user-password-123"));
        assertEquals(0, fixture.count("migration_records"));
    }

    private PersistentAppState sampleState() {
        LocalDateTime created = LocalDateTime.of(2026, 7, 1, 10, 0);
        PersistentAppState state = new PersistentAppState();
        state.getConversations().put(100L, new Conversation(100L, 2L, "历史会话", created, created.plusMinutes(5)));
        state.getMessages().put(100L, Arrays.asList(
                new Message(1000L, 100L, MessageRole.USER, "select * from t", null, created.plusMinutes(1)),
                new Message(1001L, 100L, MessageRole.ASSISTANT, "done", 1000L, created.plusMinutes(2))));

        SqlTuningTask task = new SqlTuningTask();
        task.setId(1000L);
        task.setUserId(2L);
        task.setConversationId(100L);
        task.setDbDialect("OceanBase/MySQL");
        task.setOriginalSql("select * from t where id = 1");
        task.setStatus(TaskStatus.DONE);
        task.setCreatedAt(created.plusMinutes(2));
        task.setUpdatedAt(created.plusMinutes(3));
        task.setArtifacts(Arrays.asList(new HarnessArtifact("rules", "规则扫描", new LinkedHashMap<String, Object>(), created.plusMinutes(2))));
        state.getTasks().put(1000L, task);

        state.getSkills().put("oceanbase-sql-tuning", new SkillVersion(7L,
                "oceanbase-sql-tuning", 3, "skill content", true, created));
        ModelConfigRecord model = new ModelConfigRecord("dashscope",
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3.7-max", 30000);
        model.setApiKey("dashscope-secret");
        state.setModelConfig(model);
        return state;
    }

    private String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private Path writeState(ObjectMapper mapper, PersistentAppState state) throws Exception {
        Path file = Files.createTempFile(tempDir, "sql-tuner-state-", ".json");
        mapper.writeValue(file.toFile(), state);
        return file;
    }

    private Fixture fixture() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:legacy_import_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        CryptoSupport crypto = new CryptoSupport();
        LegacyStateImporter importer = new LegacyStateImporter(
                jdbc,
                new DataSourceTransactionManager(dataSource),
                mapper,
                new JdbcJsonSupport(mapper),
                crypto,
                new BCryptPasswordEncoder(4));
        return new Fixture(jdbc, mapper, crypto, importer);
    }

    private static class Fixture {
        private final JdbcTemplate jdbc;
        private final ObjectMapper mapper;
        private final CryptoSupport crypto;
        private final LegacyStateImporter importer;

        private Fixture(JdbcTemplate jdbc, ObjectMapper mapper, CryptoSupport crypto, LegacyStateImporter importer) {
            this.jdbc = jdbc;
            this.mapper = mapper;
            this.crypto = crypto;
            this.importer = importer;
        }

        private int count(String table) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        }
    }
}
