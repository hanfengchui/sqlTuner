package com.codex.sqltuner.tuning;

import com.codex.sqltuner.conversation.ConversationRepository;
import com.codex.sqltuner.llm.ConfigurableLlmClient;
import com.codex.sqltuner.llm.LlmProperties;
import com.codex.sqltuner.rule.RuleEngine;
import com.codex.sqltuner.rule.SqlSanitizer;
import com.codex.sqltuner.skill.SkillRepository;
import com.codex.sqltuner.storage.PersistentStateStore;
import com.codex.sqltuner.storage.StorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TuningHarnessServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void runCompletesTaskWithMockModel() {
        ObjectMapper objectMapper = objectMapper();
        PersistentStateStore stateStore = stateStore(objectMapper);
        TuningTaskRepository taskRepository = new TuningTaskRepository(stateStore);
        ConversationRepository conversationRepository = new ConversationRepository(stateStore);
        LlmProperties properties = new LlmProperties();
        properties.setProvider("mock");
        TuningHarnessService service = new TuningHarnessService(
                taskRepository,
                conversationRepository,
                new SqlSanitizer(),
                new RuleEngine(),
                new SkillRepository(stateStore),
                new ConfigurableLlmClient(properties, objectMapper),
                objectMapper
        );

        CreateTuningTaskRequest request = new CreateTuningTaskRequest();
        request.setDbDialect("OceanBase/MySQL");
        request.setSqlText("select * from orders where name like '%abc'");
        request.setExplainText("type: ALL");
        request.setDeepAnalysis(Boolean.TRUE);

        SqlTuningTask task = service.createTask(1L, request);
        service.run(task);

        SqlTuningTask saved = taskRepository.getForUser(task.getId(), 1L);
        assertThat(saved.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(saved.getResult()).isNotNull();
        assertThat(saved.getArtifacts()).extracting("nodeName").contains("sanitize", "ruleCheck", "llmAnalyze", "resultAssemble");

        PersistentStateStore restartedStore = stateStore(objectMapper);
        SqlTuningTask restartedTask = new TuningTaskRepository(restartedStore).getForUser(task.getId(), 1L);
        assertThat(restartedTask.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(restartedTask.getResult().getSummary()).contains("离线规则分析");
    }

    @Test
    void runPreservesOceanBaseOracleDialect() {
        ObjectMapper objectMapper = objectMapper();
        PersistentStateStore stateStore = stateStore(objectMapper);
        TuningTaskRepository taskRepository = new TuningTaskRepository(stateStore);
        ConversationRepository conversationRepository = new ConversationRepository(stateStore);
        LlmProperties properties = new LlmProperties();
        properties.setProvider("mock");
        TuningHarnessService service = new TuningHarnessService(
                taskRepository,
                conversationRepository,
                new SqlSanitizer(),
                new RuleEngine(),
                new SkillRepository(stateStore),
                new ConfigurableLlmClient(properties, objectMapper),
                objectMapper
        );

        CreateTuningTaskRequest request = new CreateTuningTaskRequest();
        request.setDbDialect("OB Oracle");
        request.setSqlText("select * from orders where to_char(created_at, 'yyyy-mm-dd') = '2026-06-26' order by created_at");
        request.setExplainText("TABLE ACCESS FULL");
        request.setDeepAnalysis(Boolean.FALSE);

        SqlTuningTask task = service.createTask(1L, request);
        service.run(task);

        SqlTuningTask saved = taskRepository.getForUser(task.getId(), 1L);
        assertThat(saved.getDbDialect()).isEqualTo("OceanBase Oracle");
        assertThat(saved.getRuleFindings()).extracting("code").contains("FUNCTION_ON_COLUMN", "NO_PAGINATION");
        assertThat(saved.getRuleFindings()).extracting("code").doesNotContain("NO_LIMIT");
    }

    private PersistentStateStore stateStore(ObjectMapper objectMapper) {
        StorageProperties storageProperties = new StorageProperties();
        storageProperties.setDataDir(tempDir.toString());
        PersistentStateStore stateStore = new PersistentStateStore(storageProperties, new LlmProperties(), objectMapper, new com.codex.sqltuner.storage.CryptoSupport());
        stateStore.init();
        return stateStore;
    }

    private ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return objectMapper;
    }
}
