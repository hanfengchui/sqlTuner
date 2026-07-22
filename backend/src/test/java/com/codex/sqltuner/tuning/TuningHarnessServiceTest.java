package com.codex.sqltuner.tuning;

import com.codex.sqltuner.config.QueueProperties;
import com.codex.sqltuner.conversation.ConversationRepository;
import com.codex.sqltuner.llm.ConfigurableLlmClient;
import com.codex.sqltuner.llm.LlmCallException;
import com.codex.sqltuner.llm.LlmClient;
import com.codex.sqltuner.llm.LlmProperties;
import com.codex.sqltuner.llm.LlmRequest;
import com.codex.sqltuner.llm.LlmResponse;
import com.codex.sqltuner.llm.LlmStreamListener;
import com.codex.sqltuner.rule.RuleEngine;
import com.codex.sqltuner.rule.SqlSanitizer;
import com.codex.sqltuner.skill.SkillRepository;
import com.codex.sqltuner.storage.JdbcJsonSupport;
import com.codex.sqltuner.tuning.inputimage.InputImageRepository;
import com.codex.sqltuner.tuning.inputimage.InputImageValidator;
import com.codex.sqltuner.tuning.inputimage.PlanImageRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StreamUtils;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TuningHarnessServiceTest {
    private ObjectMapper objectMapper;
    private JdbcTemplate jdbcTemplate;
    private TuningTaskRepository taskRepository;
    private ConversationRepository conversationRepository;
    private SkillRepository skillRepository;
    private InputImageRepository inputImageRepository;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = objectMapper();
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:tuning_" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_UPPER=false;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        runMigration("db/migration/V1__mysql_state_queue_security.sql");
        runMigration("db/migration/V2__queue_admission_lock.sql");
        runMigration("db/migration/V3__task_input_images.sql");
        jdbcTemplate.update("INSERT INTO users(id, username, display_name, password_hash, role, enabled) VALUES (1, 'tester', 'Tester', 'x', 'ADMIN', TRUE)");
        jdbcTemplate.update("INSERT INTO skill_versions(name, version, content, enabled, updated_at) VALUES (?, 1, ?, TRUE, CURRENT_TIMESTAMP)",
                SkillRepository.DEFAULT_SKILL_NAME, "优先基于证据输出可验证建议。");
        JdbcJsonSupport jsonSupport = new JdbcJsonSupport(objectMapper);
        taskRepository = new TuningTaskRepository(jdbcTemplate, jsonSupport, new QueueProperties());
        conversationRepository = new ConversationRepository(jdbcTemplate);
        skillRepository = new SkillRepository(jdbcTemplate);
        inputImageRepository = new InputImageRepository(jdbcTemplate);
    }

    @Test
    void runCompletesTaskWithMockModelAndSqlOnlyNeedsInput() {
        TuningHarnessService service = service();

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
        assertThat(saved.getResult().getOutcome()).isEqualTo("NEEDS_INPUT");
        assertThat(saved.getResult().getRewriteCandidates()).isEmpty();
        assertThat(saved.getResult().getIndexCandidates()).isEmpty();
        assertThat(saved.getArtifacts()).extracting("nodeName").contains("sanitize", "ruleCheck", "contextAssess", "llmAnalyze", "llmReview", "resultAssemble");
        assertThat(saved.getResult().getSummary()).contains("离线规则分析");
    }

    @Test
    void runPreservesOceanBaseOracleDialect() {
        TuningHarnessService service = service();

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

    @Test
    void runWithCompleteContextKeepsMockAdviceEvidenceSafeAndArtifactsConsistent() {
        TuningHarnessService service = service();

        CreateTuningTaskRequest request = new CreateTuningTaskRequest();
        request.setDbDialect("OceanBase/MySQL");
        request.setSqlText("select id, status from orders where status = 'PAID' order by id desc limit 20");
        request.setSchemaText("create table orders (id bigint primary key, status varchar(32))");
        request.setIndexText("primary key (id)");
        request.setExplainText("type=ALL rows=100000 Extra=Using where; Using filesort");
        request.setObVersion("4.3.3");
        request.setTableStatsText("orders rows=100000");
        request.setDeepAnalysis(Boolean.FALSE);

        SqlTuningTask task = service.createTask(1L, request);
        service.run(task);

        SqlTuningTask saved = taskRepository.getForUser(task.getId(), 1L);
        Integer artifactCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_artifacts WHERE task_id = ?", Integer.class, task.getId());
        assertThat(saved.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(saved.getResult().getOutcome()).isEqualTo("ADVICE");
        assertThat(saved.getResult().getRewriteCandidates()).isEmpty();
        assertThat(saved.getResult().getIndexCandidates()).isEmpty();
        assertThat(artifactCount).isEqualTo(saved.getArtifacts().size());
    }

    @Test
    void extendedResultPopulatesOneReleaseOfLegacyFields() throws Exception {
        String sql = "select id, status from orders where status = 1 order by id desc limit 20";
        RecordingLlmClient client = new RecordingLlmClient(structuredContentWithRewriteAndIndex(sql));
        TuningHarnessService service = service(client);
        CreateTuningTaskRequest request = new CreateTuningTaskRequest();
        request.setDbDialect("OceanBase MySQL");
        request.setSqlText(sql);
        request.setSchemaText("create table orders (id bigint primary key, status int)");
        request.setIndexText("primary key (id)");
        request.setExplainText("type=ALL rows=100000 Extra=Using where; Using filesort");
        request.setObVersion("4.3.3");
        request.setTableStatsText("orders rows=100000");
        request.setRuntimeMetricsText("平均耗时: 2008ms");
        request.setBusinessInvariants("结果集和排序必须保持一致");
        request.setAllowedActions(Arrays.asList("diagnosis", "rewrite", "index", "validation"));
        request.setDeepAnalysis(Boolean.FALSE);

        SqlTuningTask task = service.createTask(1L, request);
        service.run(task);

        TuningResult result = task.getResult();
        assertThat(result.getDiagnoses()).hasSize(1);
        assertThat(result.getFindings()).isNotEmpty();
        assertThat(result.getRewriteCandidates()).hasSize(1);
        assertThat(result.getRewriteSql()).isEqualTo(sql);
        assertThat(result.getIndexCandidates()).hasSize(1);
        assertThat(result.getIndexSuggestions()).hasSize(1);
        assertThat(result.getIndexSuggestions().get(0).getColumns()).containsExactly("status", "id");
        assertThat(result.getValidationSteps()).isNotEmpty();
        assertThat(result.getRiskWarnings()).contains("先在测试环境验证");
        assertThat(task.getObVersion()).isEqualTo("4.3.3");
        assertThat(task.getRuntimeMetricsText()).contains("2008ms");
        assertThat(task.getBusinessInvariants()).contains("排序必须保持一致");
        assertThat(task.getAllowedActions()).containsExactly("diagnosis", "rewrite", "index", "validation");
    }

    @Test
    void rejectsDdlAndMultipleStatementsBeforePersistingAnything() {
        TuningHarnessService service = service();
        CreateTuningTaskRequest ddl = new CreateTuningTaskRequest();
        ddl.setDbDialect("OceanBase/MySQL");
        ddl.setSqlText("drop table orders");
        ddl.setDeepAnalysis(Boolean.FALSE);

        assertThatThrownBy(() -> service.createTask(1L, ddl))
                .isInstanceOf(IllegalArgumentException.class);

        CreateTuningTaskRequest multi = new CreateTuningTaskRequest();
        multi.setDbDialect("OceanBase/MySQL");
        multi.setSqlText("select 1; select 2");
        multi.setDeepAnalysis(Boolean.FALSE);
        assertThatThrownBy(() -> service.createTask(1L, multi))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM conversations", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tuning_tasks", Integer.class)).isZero();
    }

    @Test
    void acceptsSingleMarkdownJsonFenceWithoutRepairCall() {
        String valid = mockContent();
        RecordingLlmClient client = new RecordingLlmClient("```json\n" + valid + "\n```");
        TuningHarnessService service = service(client);

        SqlTuningTask task = service.createTask(1L, sqlOnlyRequest());
        service.run(task);

        assertThat(client.callCount()).isEqualTo(1);
        assertThat(taskRepository.getForUser(task.getId(), 1L).getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(task.getArtifacts()).extracting("nodeName").doesNotContain("llmRepair");
    }

    @Test
    void malformedJsonGetsExactlyOneRepairCall() {
        RecordingLlmClient client = new RecordingLlmClient("not-json", mockContent());
        TuningHarnessService service = service(client);

        SqlTuningTask task = service.createTask(1L, sqlOnlyRequest());
        service.run(task);

        assertThat(client.callCount()).isEqualTo(2);
        assertThat(taskRepository.getForUser(task.getId(), 1L).getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(task.getArtifacts()).extracting("nodeName").contains("resultValidateFailed", "llmRepair");
    }

    @Test
    void unsupportedPerformancePromiseGetsExactlyOneRepairCall() throws Exception {
        RecordingLlmClient client = new RecordingLlmClient(
                contentWithNarrativeBody("创建索引后预计平均耗时可降至 10ms，CPU 可降低 90%。"),
                mockContent());
        TuningHarnessService service = service(client);

        SqlTuningTask task = service.createTask(1L, sqlOnlyRequest());
        service.run(task);

        assertThat(client.callCount()).isEqualTo(2);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(task.getArtifacts()).extracting("nodeName").contains("resultValidateFailed", "llmRepair");
    }

    @Test
    void estimatedRowsMisreportedAsScanCountGetsExactlyOneRepairCall() throws Exception {
        RecordingLlmClient client = new RecordingLlmClient(
                contentWithNarrativeBody("当前 SQL 执行全表扫描，扫描行数达 100000 行，资源消耗较大。"),
                mockContent());
        TuningHarnessService service = service(client);
        CreateTuningTaskRequest request = sqlOnlyRequest();
        request.setExplainText("TABLE FULL SCAN orders; estimated rows=100000");

        SqlTuningTask task = service.createTask(1L, request);
        service.run(task);

        assertThat(client.callCount()).isEqualTo(2);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(task.getArtifacts()).extracting("nodeName").contains("resultValidateFailed", "llmRepair");
    }

    @Test
    void duplicateIndexCandidateGetsExactlyOneRepairCall() throws Exception {
        String sql = "select id, tenant_id from orders where tenant_id = 1 order by created_at desc limit 10";
        RecordingLlmClient client = new RecordingLlmClient(duplicateIndexContent(), mockContent());
        TuningHarnessService service = service(client);
        CreateTuningTaskRequest request = new CreateTuningTaskRequest();
        request.setDbDialect("OceanBase MySQL");
        request.setSqlText(sql);
        request.setSchemaText("create table orders (id bigint primary key, tenant_id bigint, created_at timestamp)");
        request.setIndexText("CREATE INDEX idx_orders_existing ON orders(tenant_id, created_at DESC)");
        request.setExplainText("TABLE SCAN orders rows=100000");
        request.setObVersion("4.3.3");
        request.setTableStatsText("orders rows=100000");
        request.setDeepAnalysis(Boolean.FALSE);

        SqlTuningTask task = service.createTask(1L, request);
        service.run(task);

        assertThat(client.callCount()).isEqualTo(2);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(task.getResult().getIndexCandidates()).isEmpty();
        assertThat(task.getArtifacts()).extracting("nodeName").contains("resultValidateFailed", "llmRepair");
    }

    @Test
    void legacyModelFieldsGetRejectedAndRepairedOnce() throws Exception {
        RecordingLlmClient client = new RecordingLlmClient(contentWithLegacyRiskWarning(), mockContent());
        TuningHarnessService service = service(client);

        SqlTuningTask task = service.createTask(1L, sqlOnlyRequest());
        service.run(task);

        assertThat(client.callCount()).isEqualTo(2);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(task.getArtifacts()).extracting("nodeName").contains("resultValidateFailed", "llmRepair");
    }

    @Test
    void keepsLastSafeDraftWhenLaterNarrativeTriggersSqlGate() {
        String safeAccumulated = "{\"analysisNarrative\":{\"conclusion\":\"安全结论\"";
        String gatedAccumulated = "{\"analysisNarrative\":{\"conclusion\":\"安全结论\","
                + "\"sections\":[{\"title\":\"主建议\",\"body\":\"CREATE INDEX idx_x ON t(c)\"}]}}";
        StreamingRecordingLlmClient client = new StreamingRecordingLlmClient(
                safeAccumulated,
                gatedAccumulated,
                mockContent());
        RecordingTaskEventBroker broker = new RecordingTaskEventBroker();
        TuningHarnessService service = service(client, broker);

        SqlTuningTask task = service.createTask(1L, sqlOnlyRequest());
        service.run(task);

        assertThat(broker.streams()).anyMatch(chunk -> "安全结论".equals(chunk.getDraftText()));
        int firstSafe = -1;
        for (int i = 0; i < broker.streams().size(); i++) {
            if ("安全结论".equals(broker.streams().get(i).getDraftText())) {
                firstSafe = i;
                break;
            }
        }
        assertThat(firstSafe).isGreaterThanOrEqualTo(0);
        assertThat(broker.streams().subList(firstSafe, broker.streams().size()))
                .allMatch(chunk -> chunk.getDraftText() != null && !chunk.getDraftText().isEmpty());
    }

    @Test
    void nullStructuredFieldsEachTriggerExactlyOneRepairCall() throws Exception {
        java.util.List<String> invalidContents = Arrays.asList(
                withNullTopLevelField("diagnoses"),
                withNullTopLevelField("validationPlan"),
                withNullDiagnosisEvidenceRefs()
        );

        for (String invalidContent : invalidContents) {
            RecordingLlmClient client = new RecordingLlmClient(invalidContent, mockContent());
            TuningHarnessService service = service(client);
            SqlTuningTask task = service.createTask(1L, sqlOnlyRequest());

            service.run(task);

            assertThat(client.callCount()).isEqualTo(2);
            assertThat(task.getArtifacts()).extracting("nodeName").contains("resultValidateFailed", "llmRepair");
            assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE);
        }
    }

    @Test
    void failsSafelyWhenSingleRepairIsStillMalformed() {
        RecordingLlmClient client = new RecordingLlmClient("not-json", "still-not-json");
        TuningHarnessService service = service(client);

        SqlTuningTask task = service.createTask(1L, sqlOnlyRequest());

        assertThatThrownBy(() -> service.run(task))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("模型修复输出不是合法 JSON");
        assertThat(client.callCount()).isEqualTo(2);
        assertThat(task.getResult()).isNull();
    }

    @Test
    void malformedDeepReviewGetsExactlyOneReviewRepairCall() {
        String reviewed = mockContent().replace("\"verdict\":\"NOT_REQUESTED\"", "\"verdict\":\"PASS\"");
        RecordingLlmClient client = new RecordingLlmClient(mockContent(), "not-json", reviewed);
        TuningHarnessService service = service(client);
        CreateTuningTaskRequest request = sqlOnlyRequest();
        request.setDeepAnalysis(Boolean.TRUE);

        SqlTuningTask task = service.createTask(1L, request);
        service.run(task);

        assertThat(client.callCount()).isEqualTo(3);
        assertThat(task.getResult().getReview().getVerdict()).isEqualTo("PASS");
        assertThat(task.getArtifacts()).extracting("nodeName")
                .contains("reviewValidateFailed", "llmReviewRepair");
    }

    @Test
    void deepReviewPassUsesCompactEnvelopeWithoutRepeatingFullResult() {
        String passEnvelope = "{\"verdict\":\"PASS\",\"notes\":\"证据引用和建议边界均通过\",\"revisions\":[]}";
        RecordingLlmClient client = new RecordingLlmClient(mockContent(), passEnvelope);
        TuningHarnessService service = service(client);
        CreateTuningTaskRequest request = sqlOnlyRequest();
        request.setDeepAnalysis(Boolean.TRUE);

        SqlTuningTask task = service.createTask(1L, request);
        service.run(task);

        assertThat(client.callCount()).isEqualTo(2);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(task.getResult().getReview().getVerdict()).isEqualTo("PASS");
        assertThat(task.getResult().getReview().getNotes()).contains("证据引用");
        assertThat(task.getResult().getReview().getRevisions()).isEmpty();
    }

    @Test
    void deepReviewReviseRequiresAndUsesCompleteRevisedResult() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode revised =
                (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(mockContent());
        revised.put("summary", "独立审查器给出的完整修正版结果");
        com.fasterxml.jackson.databind.node.ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("verdict", "REVISE");
        envelope.put("notes", "收紧结论措辞");
        envelope.putArray("revisions").add("降低未经验证结论的确定性");
        envelope.set("revisedResult", revised);
        RecordingLlmClient client = new RecordingLlmClient(mockContent(), objectMapper.writeValueAsString(envelope));
        TuningHarnessService service = service(client);
        CreateTuningTaskRequest request = sqlOnlyRequest();
        request.setDeepAnalysis(Boolean.TRUE);

        SqlTuningTask task = service.createTask(1L, request);
        service.run(task);

        assertThat(client.callCount()).isEqualTo(2);
        assertThat(task.getResult().getSummary()).contains("完整修正版结果");
        assertThat(task.getResult().getReview().getVerdict()).isEqualTo("REVISE");
        assertThat(task.getResult().getReview().getRevisions()).containsExactly("降低未经验证结论的确定性");
    }

    @Test
    void failedDeepReviewKeepsReviewAndRepairArtifactsDurable() {
        RecordingLlmClient client = new RecordingLlmClient(mockContent(), "not-json", "still-not-json");
        TuningHarnessService service = service(client);
        CreateTuningTaskRequest request = sqlOnlyRequest();
        request.setDeepAnalysis(Boolean.TRUE);

        SqlTuningTask task = service.createTask(1L, request);

        assertThatThrownBy(() -> service.run(task))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("深度复核修复输出不是合法 JSON");
        SqlTuningTask saved = taskRepository.getForUser(task.getId(), 1L);
        assertThat(saved.getArtifacts()).extracting("nodeName")
                .contains("llmReview", "reviewValidateFailed", "llmReviewRepair");
    }

    @Test
    void createPersistsPlanImagesOutsideTaskJson() {
        TuningHarnessService service = service();
        CreateTuningTaskRequest request = sqlOnlyRequest();
        request.setPlanImages(Arrays.asList(planImage("plan.png", pngDataUrl())));

        SqlTuningTask task = service.createTask(1L, request);

        assertThat(task.getInputImageCount()).isEqualTo(1);
        assertThat(inputImageRepository.findByTaskId(task.getId())).hasSize(1);
        assertThat(inputImageRepository.findByTaskId(task.getId()).get(0).getSha256()).hasSize(64);
        String taskJson = jdbcTemplate.queryForObject(
                "SELECT task_json FROM tuning_tasks WHERE id = ?", String.class, task.getId());
        assertThat(taskJson).contains("inputImageCount").contains("1");
        assertThat(taskJson).doesNotContain("data:image/png");
        assertThat(taskJson).doesNotContain("iVBOR");
    }

    @Test
    void followUpPlanImageInheritsLatestConversationSqlAndContext() {
        TuningHarnessService service = service();
        CreateTuningTaskRequest initial = new CreateTuningTaskRequest();
        initial.setDbDialect("OceanBase Oracle");
        initial.setSqlText("select id from orders where tenant_id = ? and rownum <= 10");
        initial.setSchemaText("create table orders (id number primary key, tenant_id number)");
        initial.setIndexText("create index idx_orders_tenant on orders(tenant_id)");
        initial.setRuntimeMetricsText("平均耗时: 2008ms");
        initial.setBusinessInvariants("结果集必须保持一致");
        initial.setAllowedActions(Arrays.asList("diagnosis", "validation"));
        initial.setDeepAnalysis(Boolean.FALSE);
        SqlTuningTask first = service.createTask(1L, initial);

        CreateTuningTaskRequest followUp = new CreateTuningTaskRequest();
        followUp.setConversationId(first.getConversationId());
        followUp.setDbDialect("OceanBase MySQL");
        followUp.setSqlText("执行计划图片如下");
        followUp.setInputType("natural_language");
        followUp.setPlanImages(Arrays.asList(planImage("plan.png", pngDataUrl())));
        followUp.setDeepAnalysis(Boolean.FALSE);

        SqlTuningTask second = service.createTask(1L, followUp);

        assertThat(second.getConversationId()).isEqualTo(first.getConversationId());
        assertThat(second.getOriginalSql()).isEqualTo(first.getOriginalSql());
        assertThat(second.getDbDialect()).isEqualTo("OceanBase Oracle");
        assertThat(second.getInputType()).isEqualTo("natural_language");
        assertThat(second.getSchemaText()).isEqualTo(first.getSchemaText());
        assertThat(second.getIndexText()).isEqualTo(first.getIndexText());
        assertThat(second.getRuntimeMetricsText()).isEqualTo(first.getRuntimeMetricsText());
        assertThat(second.getBusinessInvariants()).isEqualTo(first.getBusinessInvariants());
        assertThat(second.getAllowedActions()).containsExactly("diagnosis", "validation");
        assertThat(second.getBusinessContext()).contains("本轮用户补充", "执行计划图片如下");
        assertThat(second.getInputImageCount()).isEqualTo(1);
        assertThat(conversationRepository.listMessages(first.getConversationId()))
                .extracting("content")
                .containsExactly(initial.getSqlText(), "执行计划图片如下");
    }

    @Test
    void followUpMayReferenceSqlIdWithoutRepeatingSqlBody() {
        TuningHarnessService service = service();
        SqlTuningTask first = service.createTask(1L, sqlOnlyRequest());
        CreateTuningTaskRequest followUp = new CreateTuningTaskRequest();
        followUp.setConversationId(first.getConversationId());
        followUp.setDbDialect("OceanBase MySQL");
        followUp.setSqlText("SQL ID: B05FC9141039983E7E33ECD3A563E37D\n执行计划截图如下");
        followUp.setInputType("report_text");
        followUp.setPlanImages(Arrays.asList(planImage("plan.png", pngDataUrl())));
        followUp.setDeepAnalysis(Boolean.FALSE);

        SqlTuningTask second = service.createTask(1L, followUp);

        assertThat(second.getOriginalSql()).isEqualTo(first.getOriginalSql());
        assertThat(second.getBusinessContext()).contains("SQL ID: B05FC9141039983E7E33ECD3A563E37D");
    }

    @Test
    void followUpTextEvidenceIsParsedAndAccumulatedAcrossConversationTurns() {
        TuningHarnessService service = service();
        CreateTuningTaskRequest initial = new CreateTuningTaskRequest();
        initial.setDbDialect("OceanBase MySQL");
        initial.setSqlText("SELECT o.id, o.created_at FROM orders o "
                + "WHERE DATE(o.created_at) = ? AND o.tenant_id = ? "
                + "ORDER BY o.created_at DESC LIMIT 50");
        initial.setDeepAnalysis(Boolean.FALSE);
        SqlTuningTask first = service.createTask(1L, initial);

        String metricsText = "SQL ID: SYNTH-1\n"
                + "执行次数: 850\n平均耗时: 1860ms\n平均返回行数: 50\n"
                + "逻辑读: 2400000\n物理读: 18300\n"
                + "表统计:\norders: 12000000 行";
        CreateTuningTaskRequest metrics = followUp(first, metricsText);
        SqlTuningTask second = service.createTask(1L, metrics);

        assertThat(second.getOriginalSql()).isEqualTo(first.getOriginalSql());
        assertThat(second.getRuntimeMetricsText())
                .contains("平均耗时: 1860ms", "平均返回行数: 50 行", "逻辑读: 2400000", "物理读: 18300");
        assertThat(second.getTableStatsText()).contains("orders: 12000000 行");

        String planText = "EXPLAIN:\n"
                + "| id | select_type | table | type | possible_keys | key | rows | Extra |\n"
                + "| 1 | SIMPLE | orders | ALL | idx_orders_tenant | NULL | 12000000 | Using where; Using filesort |";
        CreateTuningTaskRequest plan = followUp(second, planText);
        SqlTuningTask third = service.createTask(1L, plan);

        assertThat(third.getRuntimeMetricsText()).contains("平均耗时: 1860ms", "逻辑读: 2400000");
        assertThat(third.getTableStatsText()).contains("12000000");
        assertThat(third.getExplainText()).contains("select_type", "ALL", "Using filesort");

        String metadataText = "OB Version: 4.3.5.1\n"
                + "表结构:\nCREATE TABLE orders ("
                + "id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, created_at DATETIME NOT NULL);\n"
                + "现有索引:\nCREATE INDEX idx_orders_tenant ON orders(tenant_id);";
        CreateTuningTaskRequest metadata = followUp(third, metadataText);
        SqlTuningTask fourth = service.createTask(1L, metadata);

        assertThat(fourth.getRuntimeMetricsText()).contains("平均耗时: 1860ms", "逻辑读: 2400000");
        assertThat(fourth.getTableStatsText()).contains("12000000");
        assertThat(fourth.getExplainText()).contains("select_type", "ALL", "Using filesort");
        assertThat(fourth.getSchemaText()).contains("CREATE TABLE orders");
        assertThat(fourth.getIndexText()).contains("idx_orders_tenant");
        assertThat(fourth.getObVersion()).isEqualTo("4.3.5.1");
        assertThat(conversationRepository.listMessages(first.getConversationId()))
                .extracting("content")
                .containsExactly(initial.getSqlText(), metricsText, planText, metadataText);
    }

    @Test
    void nonSqlMessageWithoutConversationSqlIsStillRejected() {
        TuningHarnessService service = service();
        CreateTuningTaskRequest request = new CreateTuningTaskRequest();
        request.setDbDialect("OceanBase Oracle");
        request.setSqlText("执行计划图片如下");
        request.setInputType("natural_language");
        request.setPlanImages(Arrays.asList(planImage("plan.png", pngDataUrl())));
        request.setDeepAnalysis(Boolean.FALSE);

        assertThatThrownBy(() -> service.createTask(1L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("先提供").hasMessageContaining("SQL");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM conversations", Integer.class)).isZero();
    }

    @Test
    void imagePersistenceIsIdempotentForSameHashAndRejectsDifferentContent() {
        TuningHarnessService service = service();
        CreateTuningTaskRequest request = sqlOnlyRequest();
        request.setPlanImages(Arrays.asList(planImage("plan.png", pngDataUrl())));

        SqlTuningTask task = service.createTask(1L, request);
        List<com.codex.sqltuner.tuning.inputimage.TaskInputImage> existing = inputImageRepository.findByTaskId(task.getId());
        inputImageRepository.saveAll(task.getId(), existing);
        assertThat(inputImageRepository.findByTaskId(task.getId())).hasSize(1);

        List<com.codex.sqltuner.tuning.inputimage.TaskInputImage> different = new InputImageValidator()
                .validate(Arrays.asList(planImage("other.jpg", "data:image/jpeg;base64,/9j/4AAQSkZJRg==")));
        assertThatThrownBy(() -> inputImageRepository.saveAll(task.getId(), different))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝覆盖");
    }

    @Test
    void planImageVisionFactsAreLowTrustAndDoNotRaiseContextGate() {
        RecordingLlmClient client = new RecordingLlmClient(visionContent(), mockContent());
        TuningHarnessService service = service(client);
        CreateTuningTaskRequest request = sqlOnlyRequest();
        request.setPlanImages(Arrays.asList(planImage("explain.png", pngDataUrl())));

        SqlTuningTask task = service.createTask(1L, request);
        service.run(task);

        SqlTuningTask saved = taskRepository.getForUser(task.getId(), 1L);
        assertThat(client.callCount()).isEqualTo(2);
        assertThat(client.requests().get(0).getModelOverride()).isNull();
        assertThat(client.requests().get(0).getImages()).hasSize(1);
        assertThat(client.requests().get(1).hasImages()).isFalse();
        assertThat(saved.getPlanImageFacts()).contains("TABLE ACCESS FULL");
        assertThat(saved.getArtifacts()).extracting("nodeName").contains("planImageVision");
        assertThat(saved.getResult().getEvidenceCatalog()).extracting("id").contains("E_PLAN_IMAGE");
        assertThat(saved.getResult().getContextAssessment().getMaxConfidence()).isEqualTo("LOW");
        assertThat(saved.getResult().getRewriteCandidates()).isEmpty();
        assertThat(saved.getResult().getIndexCandidates()).isEmpty();
    }

    @Test
    void pastedReportAndReadablePlanImageProduceMediumConfidenceDirectionWithoutDdl() throws Exception {
        RecordingLlmClient client = new RecordingLlmClient(
                productionPlanVisionContent(),
                directionalPlanAdviceContent());
        TuningHarnessService service = service(client);
        CreateTuningTaskRequest request = new CreateTuningTaskRequest();
        request.setDbDialect("OceanBase Oracle");
        request.setSqlText("SQL ID: B05FC9141039983E7E33ECD3A563E37D\n"
                + "SQL: SELECT * FROM (SELECT a.CUST_ID, a.CREATE_TIME, b.ADDRESS "
                + "FROM GRP_CUSTOMER a LEFT JOIN GRP_CUSTOMER_EX b "
                + "ON a.BE_ID = b.BE_ID AND a.CUST_ID = b.CUST_ID "
                + "WHERE a.EC_CODE = ? AND a.BE_ID = ? "
                + "ORDER BY CREATE_TIME DESC, CUST_ID DESC) WHERE ROWNUM <= ?\n"
                + "执行次数: 21.88\nCPU占比: 41.7%\n平均耗时: 2008ms\n"
                + "根因: 驱动表疑似扫描放大。优化建议: 请创建复合索引。\n"
                + "执行计划\nSELECT * FROM GRP_CUSTOMER; 2294867\n"
                + "SELECT * FROM GRP_CUSTOMER_EX; 2294758");
        request.setPlanImages(Arrays.asList(planImage("plan.png", pngDataUrl())));
        request.setDeepAnalysis(Boolean.FALSE);

        SqlTuningTask task = service.createTask(1L, request);
        service.run(task);

        SqlTuningTask saved = taskRepository.getForUser(task.getId(), 1L);
        assertThat(client.callCount()).isEqualTo(2);
        assertThat(client.requests().get(1).getUserPrompt())
                .contains("contextCompleteness: SQL_PLAN_EVIDENCE")
                .contains("allowIndexDirection=true")
                .contains("allowIndexDdl=false");
        assertThat(saved.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(saved.getResult().getOutcome()).isEqualTo("ADVICE");
        assertThat(saved.getResult().getContextAssessment().getMaxConfidence()).isEqualTo("MEDIUM");
        assertThat(saved.getResult().getContextAssessment().getAvailableEvidence())
                .contains("E_SQL", "E_PLAN_IMAGE", "E_STATS", "E_RUNTIME");
        assertThat(saved.getResult().getIndexCandidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.getTableName()).isEqualTo("GRP_CUSTOMER");
            assertThat(candidate.getColumnOrder())
                    .containsExactly("EC_CODE", "BE_ID", "CREATE_TIME DESC", "CUST_ID DESC");
            assertThat(candidate.getDdl()).isEmpty();
            assertThat(candidate.getConfidence()).isEqualTo("MEDIUM");
        });
        assertThat(saved.getResult().getAnalysisNarrative().getConclusion())
                .contains("主要瓶颈在 GRP_CUSTOMER 的大范围扫描")
                .contains("先核对并在测试环境验证")
                .contains("暂不调整 GRP_CUSTOMER_EX");
        assertThat(saved.getResult().getAnalysisNarrative().getSections())
                .extracting("title")
                .containsExactly("问题在哪", "现在怎么做", "怎么确认有效", "暂时不要做");
        assertThat(saved.getResult().getAnalysisNarrative().getSections().get(0).getBody())
                .containsOnlyOnce("以下计划事实来自截图")
                .doesNotContain("截图显示，待文本 EXPLAIN 确认")
                .contains("访问范围约 846730 而输出估计值仅 1")
                .contains("不是当前主要瓶颈");
        assertThat(saved.getResult().getAnalysisNarrative().getSections().get(1).getBody())
                .contains("1. 先检查")
                .contains("2. 若无等价覆盖")
                .contains("3. 收集统计信息");
        assertThat(saved.getResult().getAnalysisNarrative().getSections().get(2).getBody())
                .contains("扫描行数相对截图基线明显下降")
                .contains("结果集、排序和 ROWNUM 截断语义保持一致");
    }

    @Test
    void unavailableVisionModelDegradesImageEvidenceWithoutFailingSqlAnalysis() {
        VisionFailingLlmClient client = new VisionFailingLlmClient(mockContent());
        TuningHarnessService service = service(client);
        CreateTuningTaskRequest request = sqlOnlyRequest();
        request.setPlanImages(Arrays.asList(planImage("plan.png", pngDataUrl())));

        SqlTuningTask task = service.createTask(1L, request);
        service.run(task);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(task.getPlanImageFacts()).startsWith("readable=false");
        assertThat(task.getPlanImageFacts()).contains("视觉模型调用失败", "文本 EXPLAIN");
        assertThat(task.getArtifacts()).extracting("nodeName").contains("planImageVisionUnavailable");
        assertThat(task.getResult().getContextAssessment().getAvailableEvidence()).doesNotContain("E_PLAN_IMAGE");
        assertThat(client.callCount()).isEqualTo(2);
    }

    @Test
    void visionFactsAcceptStructuredRowsAndExtraFieldsWithoutRepair() {
        String structuredVision = "{"
                + "\"readable\":true,"
                + "\"operators\":[{\"name\":\"TABLE ACCESS FULL\",\"cost\":\"42\"}],"
                + "\"tables\":[{\"name\":\"ORDERS\",\"alias\":\"O\"}],"
                + "\"rowEstimates\":[{\"operator\":\"TABLE ACCESS FULL\",\"rows\":100000}],"
                + "\"warnings\":[],"
                + "\"rawTextSummary\":\"ORDERS 全表扫描，估算 100000 行\","
                + "\"ocrConfidence\":0.91"
                + "}";
        RecordingLlmClient client = new RecordingLlmClient(structuredVision, mockContent());
        TuningHarnessService service = service(client);
        CreateTuningTaskRequest request = sqlOnlyRequest();
        request.setPlanImages(Arrays.asList(planImage("plan.png", pngDataUrl())));

        SqlTuningTask task = service.createTask(1L, request);
        service.run(task);

        assertThat(client.callCount()).isEqualTo(2);
        assertThat(task.getPlanImageFacts()).contains("TABLE ACCESS FULL", "100000", "ORDERS");
        assertThat(task.getArtifacts()).extracting("nodeName").doesNotContain("planImageVisionRepair");
    }

    @Test
    void malformedVisionJsonGetsOneRepairBeforeMainAnalysis() {
        RecordingLlmClient client = new RecordingLlmClient("not-json", visionContent(), mockContent());
        TuningHarnessService service = service(client);
        CreateTuningTaskRequest request = sqlOnlyRequest();
        request.setPlanImages(Arrays.asList(planImage("explain.png", pngDataUrl())));

        SqlTuningTask task = service.createTask(1L, request);
        service.run(task);

        assertThat(client.callCount()).isEqualTo(3);
        assertThat(client.requests().get(1).getModelOverride()).isNull();
        assertThat(client.requests().get(1).getImages()).hasSize(1);
        assertThat(task.getArtifacts()).extracting("nodeName")
                .contains("planImageVisionValidateFailed", "planImageVisionRepair", "planImageVision");
        assertThat(task.getResult()).isNotNull();
    }

    @Test
    void malformedVisionJsonDegradesAfterExactlyOneRepairAndStillFinishes() {
        RecordingLlmClient client = new RecordingLlmClient("not-json", "still-not-json", mockContent());
        TuningHarnessService service = service(client);
        CreateTuningTaskRequest request = sqlOnlyRequest();
        request.setPlanImages(Arrays.asList(planImage("explain.png", pngDataUrl())));

        SqlTuningTask task = service.createTask(1L, request);
        service.run(task);

        assertThat(client.callCount()).isEqualTo(3);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(task.getPlanImageFacts()).startsWith("readable=false");
        assertThat(task.getResult().getEvidenceCatalog()).extracting("id").doesNotContain("E_PLAN_IMAGE");
        assertThat(task.getArtifacts()).extracting("nodeName")
                .contains("planImageVisionRepair", "planImageVisionRepairFailed", "planImageVision");
    }

    @Test
    void unreadablePlanImageIsNotAddedToEvidenceCatalog() {
        String unreadable = "{"
                + "\"readable\":false,"
                + "\"operators\":[],"
                + "\"tables\":[],"
                + "\"rowEstimates\":[],"
                + "\"warnings\":[\"图片模糊，无法可靠识别\"],"
                + "\"rawTextSummary\":\"未能识别出可核验的执行计划文字\""
                + "}";
        RecordingLlmClient client = new RecordingLlmClient(unreadable, mockContent());
        TuningHarnessService service = service(client);
        CreateTuningTaskRequest request = sqlOnlyRequest();
        request.setPlanImages(Arrays.asList(planImage("blurred.png", pngDataUrl())));

        SqlTuningTask task = service.createTask(1L, request);
        service.run(task);

        assertThat(task.getPlanImageFacts()).startsWith("readable=false");
        assertThat(task.getResult().getEvidenceCatalog()).extracting("id").doesNotContain("E_PLAN_IMAGE");
        assertThat(task.getResult().getContextAssessment().getPolicyNotes())
                .anyMatch(note -> note.contains("无法可靠识别"));
        assertThat(task.getResult().getOutcome()).isEqualTo("NEEDS_INPUT");
    }

    @Test
    void rejectsInvalidPlanImageMagicBytes() {
        TuningHarnessService service = service();
        CreateTuningTaskRequest request = sqlOnlyRequest();
        request.setPlanImages(Arrays.asList(planImage("fake.png", "data:image/png;base64,/9j/4AAQSkZJRg==")));

        assertThatThrownBy(() -> service.createTask(1L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("magic bytes");
    }

    @Test
    void pastedReportTextIsSplitIntoSqlMetricsAndUntrustedPriorAnalysis() {
        TuningHarnessService service = service();
        CreateTuningTaskRequest request = new CreateTuningTaskRequest();
        request.setDbDialect("OceanBase MySQL");
        String submittedReport = "SQL ID: SAMPLE-1 SQL: select * from orders where status = ? order by created_at desc fetch first 20 rows only\n"
                + "执行次数: 21 CPU占比: 41.7% 平均耗时: 2008ms "
                + "根因: 全表扫描，平均返回行数仅1行。逻辑读: 123456，物理读: 789。 "
                + "限流值: 0 优化建议: 直接创建索引。\n"
                + "执行计划\nSELECT COUNT(*) FROM orders; 2294867";
        request.setSqlText(submittedReport);
        request.setDeepAnalysis(Boolean.FALSE);

        SqlTuningTask task = service.createTask(1L, request);

        assertThat(task.getDbDialect()).isEqualTo("OceanBase Oracle");
        assertThat(task.getOriginalSql()).startsWith("select * from orders").doesNotContain("执行次数");
        assertThat(task.getRuntimeMetricsText()).contains(
                "SQL ID: SAMPLE-1",
                "平均耗时: 2008ms",
                "平均返回行数（报告结论内识别，待核验）: 1 行",
                "逻辑读（报告结论内识别，待核验）: 123456",
                "物理读（报告结论内识别，待核验）: 789");
        assertThat(task.getTableStatsText()).contains("orders: 2294867 行");
        assertThat(task.getExplainText()).isEmpty();
        assertThat(task.getBusinessContext()).contains("待核验，不作为事实证据", "补充文本 EXPLAIN");
        assertThat(conversationRepository.listMessages(task.getConversationId()))
                .singleElement()
                .satisfies(message -> assertThat(message.getContent()).isEqualTo(submittedReport));
    }

    @Test
    void pastedCompleteReportAutoPopulatesEvidenceWithoutExtraFormFields() {
        TuningHarnessService service = service();
        CreateTuningTaskRequest request = new CreateTuningTaskRequest();
        request.setDbDialect("OceanBase Oracle");
        request.setSqlText("SQL: SELECT id FROM orders WHERE tenant_id = ? ORDER BY created_at DESC\n"
                + "OB Version: 4.3.2.1\n"
                + "表结构:\n"
                + "CREATE TABLE orders (id BIGINT PRIMARY KEY, tenant_id BIGINT, created_at TIMESTAMP);\n"
                + "索引信息:\n"
                + "CREATE INDEX idx_orders_tenant ON orders(tenant_id);\n"
                + "EXPLAIN:\n"
                + "| ID | OPERATOR | NAME | EST. ROWS |\n"
                + "| 0 | TABLE FULL SCAN | orders | 2300000 |\n"
                + "表统计:\n"
                + "orders: 2300000 行\n"
                + "业务语义约束:\n"
                + "- 结果必须保持 created_at 倒序，不能跨租户读取。\n"
                + "允许建议类型: 诊断、索引、验证\n");
        request.setDeepAnalysis(Boolean.FALSE);

        SqlTuningTask task = service.createTask(1L, request);

        assertThat(task.getOriginalSql()).startsWith("SELECT id FROM orders");
        assertThat(task.getDbDialect()).isEqualTo("OceanBase Oracle");
        assertThat(task.getSchemaText()).contains("CREATE TABLE orders");
        assertThat(task.getIndexText()).contains("idx_orders_tenant");
        assertThat(task.getExplainText()).contains("TABLE FULL SCAN");
        assertThat(task.getTableStatsText()).contains("2300000");
        assertThat(task.getObVersion()).isEqualTo("4.3.2.1");
        assertThat(task.getBusinessInvariants()).contains("created_at 倒序", "不能跨租户读取");
        assertThat(task.getAllowedActions()).containsExactly("diagnosis", "index", "validation");
    }

    private TuningHarnessService service() {
        LlmProperties properties = new LlmProperties();
        properties.setProvider("mock");
        return service(new ConfigurableLlmClient(properties, objectMapper));
    }

    private TuningHarnessService service(LlmClient llmClient) {
        return service(llmClient, new TaskEventBroker());
    }

    private TuningHarnessService service(LlmClient llmClient, TaskEventBroker eventBroker) {
        return new TuningHarnessService(
                taskRepository,
                conversationRepository,
                new SqlSanitizer(),
                new RuleEngine(),
                skillRepository,
                llmClient,
                objectMapper,
                new com.codex.sqltuner.tuning.accuracy.SqlStatementParser(),
                new com.codex.sqltuner.tuning.accuracy.ContextAssessor(),
                new com.codex.sqltuner.tuning.accuracy.PromptCompiler(),
                new com.codex.sqltuner.tuning.accuracy.StrictResultValidator(new com.codex.sqltuner.tuning.accuracy.SqlStatementParser()),
                eventBroker,
                new InputImageValidator(),
                inputImageRepository
        );
    }

    private CreateTuningTaskRequest sqlOnlyRequest() {
        CreateTuningTaskRequest request = new CreateTuningTaskRequest();
        request.setDbDialect("OceanBase/MySQL");
        request.setSqlText("select id from orders where status = 'PAID'");
        request.setDeepAnalysis(Boolean.FALSE);
        return request;
    }

    private CreateTuningTaskRequest followUp(SqlTuningTask previous, String text) {
        CreateTuningTaskRequest request = new CreateTuningTaskRequest();
        request.setConversationId(previous.getConversationId());
        request.setDbDialect("OceanBase MySQL");
        request.setSqlText(text);
        request.setInputType("natural_language");
        request.setDeepAnalysis(Boolean.FALSE);
        return request;
    }

    private String mockContent() {
        LlmProperties properties = new LlmProperties();
        properties.setProvider("mock");
        return new ConfigurableLlmClient(properties, objectMapper)
                .analyze(new LlmRequest("", "contextCompleteness: SQL_ONLY", false))
                .getContent();
    }

    private String visionContent() {
        return "```json\n{"
                + "\"readable\":true,"
                + "\"operators\":[\"TABLE ACCESS FULL\"],"
                + "\"tables\":[\"ORDERS\"],"
                + "\"rowEstimates\":[\"rows=100000\"],"
                + "\"warnings\":[\"截图事实仅低可信\"],"
                + "\"rawTextSummary\":\"截图显示 TABLE ACCESS FULL ORDERS rows=100000\""
                + "}\n```";
    }

    private String productionPlanVisionContent() {
        return "{"
                + "\"readable\":true,"
                + "\"operators\":[\"PHY_SORT\",\"PHY_NESTED_LOOP_JOIN\",\"PHY_TABLE_SCAN\"],"
                + "\"tables\":[\"A\",\"B(PKX_GRP_CUSTOMER_EX_ID)\"],"
                + "\"rowEstimates\":[\"A physical_range_rows=846730 cost=332640\",\"B physical_range_rows=1 cost=27\"],"
                + "\"warnings\":[\"截图事实需由文本 EXPLAIN 复核\"],"
                + "\"rawTextSummary\":\"截图显示 A 侧范围扫描成本占主导，B 侧通过命名索引单行探测\""
                + "}";
    }

    private String directionalPlanAdviceContent() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode root =
                (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(mockContent());
        root.put("outcome", "ADVICE");
        root.put("summary", "主要瓶颈位于 GRP_CUSTOMER 的大范围扫描，应先核对并验证过滤加排序复合索引。");

        com.fasterxml.jackson.databind.node.ObjectNode narrative = root.with("analysisNarrative");
        narrative.put("conclusion", "最终结论：主要瓶颈在 GRP_CUSTOMER 的大范围扫描；先核对并在测试环境验证过滤加排序复合索引，暂不调整 GRP_CUSTOMER_EX 索引和 ROWNUM 位置。");
        com.fasterxml.jackson.databind.node.ArrayNode sections = narrative.putArray("sections");
        com.fasterxml.jackson.databind.node.ObjectNode evidence = sections.addObject();
        evidence.put("kind", "EVIDENCE");
        evidence.put("title", "问题在哪");
        evidence.put("body", "以下计划事实来自截图，精确算子与数值需由文本 EXPLAIN 复核。\n"
                + "- 截图中的计划估计值显示，GRP_CUSTOMER 访问范围约 846730 而输出估计值仅 1，两者差距很大（PHY_TABLE_SCAN）\n"
                + "- GRP_CUSTOMER_EX 已通过 PKX_GRP_CUSTOMER_EX_ID 单行探测，不是当前主要瓶颈\n"
                + "- 过滤后仍需执行 PHY_SORT，排序路径还有额外成本");
        evidence.putArray("evidenceRefs").add("E_SQL").add("E_PLAN_IMAGE").add("E_STATS").add("E_RUNTIME");
        com.fasterxml.jackson.databind.node.ObjectNode action = sections.addObject();
        action.put("kind", "ACTION");
        action.put("title", "现在怎么做");
        action.put("body", "1. 先检查 GRP_CUSTOMER 的现有索引，排除等价或更优前缀\n"
                + "2. 若无等价覆盖，在测试环境验证 EC_CODE、BE_ID 加 CREATE_TIME、CUST_ID 的过滤排序索引；等值列顺序需按租户键和列基数确认\n"
                + "3. 收集统计信息，并使用相同绑定值复测执行计划和耗时");
        action.putArray("evidenceRefs").add("E_SQL").add("E_PLAN_IMAGE");
        com.fasterxml.jackson.databind.node.ObjectNode validation = sections.addObject();
        validation.put("kind", "VALIDATION");
        validation.put("title", "怎么确认有效");
        validation.put("body", "- GRP_CUSTOMER 由大范围扫描转为索引范围访问，扫描行数相对截图基线明显下降\n"
                + "- PHY_SORT 消失或缩小为低成本 Top-N 排序\n"
                + "- GRP_CUSTOMER_EX 继续保持命名索引单行探测\n"
                + "- 结果集、排序和 ROWNUM 截断语义保持一致");
        validation.putArray("evidenceRefs").add("E_SQL").add("E_PLAN_IMAGE");
        com.fasterxml.jackson.databind.node.ObjectNode caution = sections.addObject();
        caution.put("kind", "CAUTION");
        caution.put("title", "暂时不要做");
        caution.put("body", "- 不要为已单行探测的 GRP_CUSTOMER_EX 创建同类重复索引\n"
                + "- 不要把 ROWNUM 移到内层 ORDER BY 之前，以免改变 Top-N 语义");
        caution.putArray("evidenceRefs").add("E_SQL").add("E_PLAN_IMAGE");

        com.fasterxml.jackson.databind.node.ArrayNode catalog = root.putArray("evidenceCatalog");
        addEvidence(catalog, "E_SQL", "USER_SQL", "用户提供的 SQL", "HIGH");
        addEvidence(catalog, "E_PLAN_IMAGE", "VISION_PLAN_IMAGE", "截图提取的执行计划事实", "LOW");
        addEvidence(catalog, "E_STATS", "USER_STATS", "用户提供两张表的行数", "MEDIUM");
        addEvidence(catalog, "E_RUNTIME", "USER_RUNTIME", "用户提供平均耗时与 CPU 占比", "MEDIUM");

        com.fasterxml.jackson.databind.node.ArrayNode diagnoses = root.putArray("diagnoses");
        com.fasterxml.jackson.databind.node.ObjectNode diagnosis = diagnoses.addObject();
        diagnosis.put("severity", "HIGH");
        diagnosis.put("title", "A 侧过滤与排序访问成本占主导");
        diagnosis.put("impact", "截图中的 A 侧范围访问明显重于 B 侧命名索引探测，是当前首要核验方向。");
        diagnosis.put("confidence", "MEDIUM");
        diagnosis.put("precondition", "截图数值需由文本 EXPLAIN 交叉确认");
        diagnosis.putArray("evidenceRefs").add("E_SQL").add("E_PLAN_IMAGE").add("E_STATS").add("E_RUNTIME");

        root.putArray("rewriteCandidates");
        com.fasterxml.jackson.databind.node.ArrayNode indexes = root.putArray("indexCandidates");
        com.fasterxml.jackson.databind.node.ObjectNode index = indexes.addObject();
        index.put("tableName", "GRP_CUSTOMER");
        index.putArray("columnOrder").add("EC_CODE").add("BE_ID").add("CREATE_TIME DESC").add("CUST_ID DESC");
        index.put("ddl", "");
        index.put("benefit", "同时服务等值过滤和 Top-N 排序，目标是缩小 A 侧访问范围并避免额外排序。");
        index.put("writeCost", "会增加索引存储与写入维护成本，需结合写入量评估。");
        index.put("risk", "尚未取得表结构、分区方式和现有索引，当前仅为条件式方向，不能直接生成 DDL。");
        index.put("validation", "核对现有索引后比较计划中的 A 侧访问方式、排序算子和同口径运行指标。");
        index.put("confidence", "MEDIUM");
        index.putArray("evidenceRefs").add("E_SQL").add("E_PLAN_IMAGE").add("E_STATS").add("E_RUNTIME");

        com.fasterxml.jackson.databind.node.ArrayNode validationPlan = root.putArray("validationPlan");
        com.fasterxml.jackson.databind.node.ObjectNode step = validationPlan.addObject();
        step.put("action", "核对现有索引并在测试环境比较候选方向前后的执行计划");
        step.put("expectedSignal", "A 侧访问范围与排序成本下降，B 侧仍保持命名索引单行探测");
        step.putArray("evidenceRefs").add("E_SQL").add("E_PLAN_IMAGE");
        root.putArray("missingInformation")
                .add("两表现有索引完整定义")
                .add("表结构、分区方式与 OceanBase 版本");
        root.putArray("safetyWarnings").add("最终建索引前必须排除重复索引并评估写入成本");
        return objectMapper.writeValueAsString(root);
    }

    private void addEvidence(com.fasterxml.jackson.databind.node.ArrayNode catalog,
                             String id,
                             String source,
                             String summary,
                             String trustLevel) {
        com.fasterxml.jackson.databind.node.ObjectNode evidence = catalog.addObject();
        evidence.put("id", id);
        evidence.put("source", source);
        evidence.put("summary", summary);
        evidence.put("trustLevel", trustLevel);
    }

    private String withNullTopLevelField(String field) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode root =
                (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(mockContent());
        root.putNull(field);
        return objectMapper.writeValueAsString(root);
    }

    private String withNullDiagnosisEvidenceRefs() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode root =
                (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(mockContent());
        com.fasterxml.jackson.databind.node.ObjectNode diagnosis =
                (com.fasterxml.jackson.databind.node.ObjectNode) root.withArray("diagnoses").get(0);
        diagnosis.putNull("evidenceRefs");
        return objectMapper.writeValueAsString(root);
    }

    private String contentWithNarrativeBody(String body) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode root =
                (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(mockContent());
        com.fasterxml.jackson.databind.node.ObjectNode section =
                (com.fasterxml.jackson.databind.node.ObjectNode) root.with("analysisNarrative").withArray("sections").get(0);
        section.put("body", body);
        return objectMapper.writeValueAsString(root);
    }

    private String duplicateIndexContent() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode root =
                (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(mockContent());
        root.put("outcome", "ADVICE");
        com.fasterxml.jackson.databind.node.ArrayNode indexes = root.putArray("indexCandidates");
        com.fasterxml.jackson.databind.node.ObjectNode index = indexes.addObject();
        index.put("tableName", "orders");
        index.putArray("columnOrder").add("tenant_id");
        index.put("ddl", "CREATE INDEX idx_orders_duplicate ON orders(tenant_id)");
        index.put("benefit", "减少过滤扫描");
        index.put("writeCost", "增加写放大");
        index.put("risk", "需要检查重复索引");
        index.put("validation", "比较执行计划");
        index.put("confidence", "MEDIUM");
        index.putArray("evidenceRefs").add("E_SCHEMA").add("E_INDEX").add("E_EXPLAIN");
        return objectMapper.writeValueAsString(root);
    }

    private String contentWithLegacyRiskWarning() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode root =
                (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(mockContent());
        root.putArray("riskWarnings").add("预计优化后降至 10ms");
        return objectMapper.writeValueAsString(root);
    }

    private String structuredContentWithRewriteAndIndex(String sql) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode root =
                (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(mockContent());
        root.remove(Arrays.asList("findings", "rewriteSql", "indexSuggestions", "validationSteps", "riskWarnings", "needMoreInfo"));
        root.put("outcome", "ADVICE");
        com.fasterxml.jackson.databind.node.ArrayNode rewrites = root.putArray("rewriteCandidates");
        com.fasterxml.jackson.databind.node.ObjectNode rewrite = rewrites.addObject();
        rewrite.put("sql", sql);
        rewrite.put("change", "保留语义的候选写法");
        rewrite.put("semanticCheck", "语句类型、表集合和条件保持一致");
        rewrite.put("risk", "需验证执行计划");
        rewrite.put("validation", "对比结果集和 EXPLAIN");
        rewrite.putArray("evidenceRefs").add("E_SQL").add("E_SCHEMA");

        com.fasterxml.jackson.databind.node.ArrayNode indexes = root.putArray("indexCandidates");
        com.fasterxml.jackson.databind.node.ObjectNode index = indexes.addObject();
        index.put("tableName", "orders");
        index.putArray("columnOrder").add("status").add("id");
        index.put("ddl", "CREATE INDEX idx_orders_status_id ON orders(status, id)");
        index.put("benefit", "减少过滤和排序扫描");
        index.put("writeCost", "增加写放大和存储占用");
        index.put("risk", "需检查重复索引");
        index.put("validation", "测试库对比 EXPLAIN 与耗时");
        index.put("confidence", "MEDIUM");
        index.putArray("evidenceRefs").add("E_SCHEMA").add("E_INDEX").add("E_EXPLAIN");
        root.putArray("safetyWarnings").add("先在测试环境验证");
        return objectMapper.writeValueAsString(root);
    }

    private PlanImageRequest planImage(String name, String dataUrl) {
        PlanImageRequest image = new PlanImageRequest();
        image.setName(name);
        image.setDataUrl(dataUrl);
        return image;
    }

    private String pngDataUrl() {
        return "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
    }

    private void runMigration(String path) throws Exception {
        InputStream migrationStream = getClass().getClassLoader().getResourceAsStream(path);
        String migration = StreamUtils.copyToString(migrationStream, StandardCharsets.UTF_8);
        for (String statement : migration.split(";")) {
            if (!statement.trim().isEmpty()) {
                jdbcTemplate.execute(statement);
            }
        }
    }

    private static final class RecordingLlmClient implements LlmClient {
        private final Queue<String> responses;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<LlmRequest> requests = new java.util.ArrayList<LlmRequest>();

        private RecordingLlmClient(String... responses) {
            this.responses = new ArrayDeque<String>(Arrays.asList(responses));
        }

        @Override
        public LlmResponse analyze(LlmRequest request) {
            calls.incrementAndGet();
            requests.add(request);
            if (responses.isEmpty()) {
                throw new AssertionError("Unexpected extra model call");
            }
            return new LlmResponse("test", "test", responses.remove(), 1L, false);
        }

        private int callCount() {
            return calls.get();
        }

        private List<LlmRequest> requests() {
            return requests;
        }
    }

    private static final class VisionFailingLlmClient implements LlmClient {
        private final String textResponse;
        private final AtomicInteger calls = new AtomicInteger();

        private VisionFailingLlmClient(String textResponse) {
            this.textResponse = textResponse;
        }

        @Override
        public LlmResponse analyze(LlmRequest request) {
            calls.incrementAndGet();
            if (request.hasImages()) {
                throw new LlmCallException("模型调用失败: 400 vision model unsupported", null);
            }
            return new LlmResponse("test", "test", textResponse, 1L, false);
        }

        private int callCount() {
            return calls.get();
        }
    }

    private static final class StreamingRecordingLlmClient implements LlmClient {
        private final String safeAccumulated;
        private final String gatedAccumulated;
        private final String finalContent;

        private StreamingRecordingLlmClient(String safeAccumulated,
                                            String gatedAccumulated,
                                            String finalContent) {
            this.safeAccumulated = safeAccumulated;
            this.gatedAccumulated = gatedAccumulated;
            this.finalContent = finalContent;
        }

        @Override
        public LlmResponse analyze(LlmRequest request) {
            return new LlmResponse("test", "test", finalContent, 1L, false);
        }

        @Override
        public LlmResponse analyze(LlmRequest request, LlmStreamListener listener) {
            listener.onContent(safeAccumulated, safeAccumulated.length());
            listener.onContent(gatedAccumulated, gatedAccumulated.length());
            return analyze(request);
        }
    }

    private static final class RecordingTaskEventBroker extends TaskEventBroker {
        private final List<TaskStreamChunk> streams = new java.util.ArrayList<TaskStreamChunk>();

        @Override
        public void publishModelStream(SqlTuningTask task, TaskStreamChunk chunk) {
            TaskStreamChunk copy = new TaskStreamChunk(
                    chunk.getPhase(),
                    chunk.getDraftText(),
                    chunk.getReceivedChars(),
                    chunk.getSequence());
            copy.setAttempt(chunk.getAttempt());
            streams.add(copy);
        }

        private List<TaskStreamChunk> streams() {
            return streams;
        }
    }

    private ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
