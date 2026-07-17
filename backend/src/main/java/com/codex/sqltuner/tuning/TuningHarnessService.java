package com.codex.sqltuner.tuning;

import com.codex.sqltuner.conversation.Conversation;
import com.codex.sqltuner.conversation.ConversationRepository;
import com.codex.sqltuner.conversation.Message;
import com.codex.sqltuner.conversation.MessageRole;
import com.codex.sqltuner.llm.LlmClient;
import com.codex.sqltuner.llm.LlmRequest;
import com.codex.sqltuner.llm.LlmRequestImage;
import com.codex.sqltuner.llm.LlmResponse;
import com.codex.sqltuner.rule.RuleEngine;
import com.codex.sqltuner.rule.RuleFinding;
import com.codex.sqltuner.rule.SanitizedSql;
import com.codex.sqltuner.rule.SqlDialect;
import com.codex.sqltuner.rule.SqlSanitizer;
import com.codex.sqltuner.skill.SkillRepository;
import com.codex.sqltuner.skill.SkillVersion;
import com.codex.sqltuner.tuning.accuracy.ContextAssessor;
import com.codex.sqltuner.tuning.accuracy.ContextPackage;
import com.codex.sqltuner.tuning.accuracy.PromptCompiler;
import com.codex.sqltuner.tuning.accuracy.SqlStatementParser;
import com.codex.sqltuner.tuning.accuracy.SqlStatementProfile;
import com.codex.sqltuner.tuning.accuracy.StrictResultValidator;
import com.codex.sqltuner.tuning.accuracy.ValidationOutcome;
import com.codex.sqltuner.tuning.inputimage.InputImageRepository;
import com.codex.sqltuner.tuning.inputimage.InputImageValidator;
import com.codex.sqltuner.tuning.inputimage.TaskInputImage;
import com.codex.sqltuner.tuning.inputimage.VisionExtractionResult;
import com.codex.sqltuner.tuning.input.ParsedReport;
import com.codex.sqltuner.tuning.input.ReportTextParser;
import com.codex.sqltuner.tuning.result.Diagnosis;
import com.codex.sqltuner.tuning.result.IndexCandidate;
import com.codex.sqltuner.tuning.result.ReviewResult;
import com.codex.sqltuner.tuning.result.RewriteCandidate;
import com.codex.sqltuner.tuning.result.ValidationStep;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class TuningHarnessService {
    private static final Logger log = LoggerFactory.getLogger(TuningHarnessService.class);
    private static final String VISION_SYSTEM_PROMPT =
            "你是截图 OCR/视觉事实抽取器，只抽取图片中可见文字和执行计划事实。";
    private static final String VISION_EXTRACTION_PROMPT =
            "请从用户上传的 OceanBase SQL 执行计划截图或诊断截图中抽取可见事实。"
                    + "只返回严格 JSON，不要解释。字段必须包含 readable, operators, tables, rowEstimates, warnings, rawTextSummary。"
                    + "如果图片不可读，readable=false，并在 warnings 说明。不得编造图片中看不到的表、行数、算子。";
    private final TuningTaskRepository taskRepository;
    private final ConversationRepository conversationRepository;
    private final SqlSanitizer sanitizer;
    private final RuleEngine ruleEngine;
    private final SkillRepository skillRepository;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final SqlStatementParser statementParser;
    private final ContextAssessor contextAssessor;
    private final PromptCompiler promptCompiler;
    private final StrictResultValidator resultValidator;
    private final TaskEventBroker eventBroker;
    private final InputImageValidator inputImageValidator;
    private final InputImageRepository inputImageRepository;
    private final ReportTextParser reportTextParser = new ReportTextParser();

    public TuningHarnessService(TuningTaskRepository taskRepository,
                                ConversationRepository conversationRepository,
                                SqlSanitizer sanitizer,
                                RuleEngine ruleEngine,
                                SkillRepository skillRepository,
                                LlmClient llmClient,
                                ObjectMapper objectMapper) {
        this(taskRepository, conversationRepository, sanitizer, ruleEngine, skillRepository, llmClient, objectMapper,
                new SqlStatementParser(), new ContextAssessor(), new PromptCompiler(), new StrictResultValidator(new SqlStatementParser()), new TaskEventBroker(), new InputImageValidator(), null);
    }

    @Autowired
    public TuningHarnessService(TuningTaskRepository taskRepository,
                                ConversationRepository conversationRepository,
                                SqlSanitizer sanitizer,
                                RuleEngine ruleEngine,
                                SkillRepository skillRepository,
                                LlmClient llmClient,
                                ObjectMapper objectMapper,
                                InputImageValidator inputImageValidator,
                                InputImageRepository inputImageRepository,
                                TaskEventBroker eventBroker) {
        this(taskRepository, conversationRepository, sanitizer, ruleEngine, skillRepository, llmClient, objectMapper,
                new SqlStatementParser(), new ContextAssessor(), new PromptCompiler(), new StrictResultValidator(new SqlStatementParser()), eventBroker, inputImageValidator, inputImageRepository);
    }

    public TuningHarnessService(TuningTaskRepository taskRepository,
                                ConversationRepository conversationRepository,
                                SqlSanitizer sanitizer,
                                RuleEngine ruleEngine,
                                SkillRepository skillRepository,
                                LlmClient llmClient,
                                ObjectMapper objectMapper,
                                SqlStatementParser statementParser,
                                ContextAssessor contextAssessor,
                                PromptCompiler promptCompiler,
                                StrictResultValidator resultValidator,
                                TaskEventBroker eventBroker) {
        this(taskRepository, conversationRepository, sanitizer, ruleEngine, skillRepository, llmClient, objectMapper,
                statementParser, contextAssessor, promptCompiler, resultValidator, eventBroker, new InputImageValidator(), null);
    }

    public TuningHarnessService(TuningTaskRepository taskRepository,
                                ConversationRepository conversationRepository,
                                SqlSanitizer sanitizer,
                                RuleEngine ruleEngine,
                                SkillRepository skillRepository,
                                LlmClient llmClient,
                                ObjectMapper objectMapper,
                                SqlStatementParser statementParser,
                                ContextAssessor contextAssessor,
                                PromptCompiler promptCompiler,
                                StrictResultValidator resultValidator,
                                TaskEventBroker eventBroker,
                                InputImageValidator inputImageValidator,
                                InputImageRepository inputImageRepository) {
        this.taskRepository = taskRepository;
        this.conversationRepository = conversationRepository;
        this.sanitizer = sanitizer;
        this.ruleEngine = ruleEngine;
        this.skillRepository = skillRepository;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.statementParser = statementParser;
        this.contextAssessor = contextAssessor;
        this.promptCompiler = promptCompiler;
        this.resultValidator = resultValidator;
        this.eventBroker = eventBroker;
        this.inputImageValidator = inputImageValidator;
        this.inputImageRepository = inputImageRepository;
    }

    public SqlTuningTask createTask(Long userId, CreateTuningTaskRequest request) {
        return createTask(userId, request, null);
    }

    @Transactional
    public SqlTuningTask createTask(Long userId, CreateTuningTaskRequest request, String idempotencyKey) {
        normalizePastedReport(request);
        SqlDialect dialect = SqlDialect.from(request.getDbDialect());
        validateRequest(request, dialect);
        List<TaskInputImage> inputImages = inputImageValidator.validate(request.getPlanImages());
        Long conversationId = request.getConversationId();
        if (conversationId == null) {
            Conversation conversation = conversationRepository.create(userId, "新建调优");
            conversationId = conversation.getId();
        } else {
            conversationRepository.getForUser(conversationId, userId);
        }

        SqlTuningTask task = new SqlTuningTask();
        task.setUserId(userId);
        task.setConversationId(conversationId);
        task.setDbDialect(dialect.getDisplayName());
        task.setInputType(resolveInputType(request.getInputType(), request.getSqlText()));
        task.setOriginalSql(request.getSqlText());
        task.setSchemaText(request.getSchemaText());
        task.setIndexText(request.getIndexText());
        task.setExplainText(request.getExplainText());
        task.setBusinessContext(request.getBusinessContext());
        task.setObVersion(request.getObVersion());
        task.setTableStatsText(request.getTableStatsText());
        task.setRuntimeMetricsText(request.getRuntimeMetricsText());
        task.setBusinessInvariants(request.getBusinessInvariants());
        task.setAllowedActions(request.getAllowedActions());
        task.setInputImageCount(inputImages.size());
        task.setDeepAnalysis(Boolean.TRUE.equals(request.getDeepAnalysis()));
        task.setStatus(TaskStatus.QUEUED);
        task.setStatusMessage("已收到调优请求");
        task.setQueuedAt(LocalDateTime.now());
        taskRepository.create(task, idempotencyKey);
        if (!inputImages.isEmpty()) {
            if (inputImageRepository == null) {
                throw new IllegalStateException("图片输入仓储未初始化");
            }
            inputImageRepository.saveAll(task.getId(), inputImages);
        }

        conversationRepository.addMessage(conversationId, MessageRole.USER, request.getSqlText(), task.getId());
        addArtifact(task, "received", "接收用户输入和上下文", safeContextSummary(task));
        taskRepository.update(task);
        eventBroker.publish(task);
        log.info("createTask result 结果: taskId: {}, userId: {}, conversationId: {}, dbDialect: {}, inputType: {}, inputLength: {}",
                task.getId(), userId, conversationId, task.getDbDialect(), task.getInputType(), request.getSqlText().length());
        return task;
    }

    private void normalizePastedReport(CreateTuningTaskRequest request) {
        ParsedReport parsed = reportTextParser.parse(request.getSqlText());
        boolean structuredReport = hasText(parsed.getRuntimeMetricsText())
                || hasText(parsed.getTableStatsText())
                || hasText(parsed.getPriorAnalysisText())
                || hasText(parsed.getExplainText());
        if (!structuredReport) {
            return;
        }
        request.setSqlText(parsed.getExtractedSql());
        request.setDbDialect(parsed.getInferredDialect());
        request.setInputType("sql");
        if (!hasText(request.getRuntimeMetricsText())) {
            request.setRuntimeMetricsText(parsed.getRuntimeMetricsText());
        }
        if (!hasText(request.getTableStatsText())) {
            request.setTableStatsText(parsed.getTableStatsText());
        }
        if (!hasText(request.getExplainText())) {
            request.setExplainText(parsed.getExplainText());
        }
        if (hasText(parsed.getPriorAnalysisText())) {
            String existing = request.getBusinessContext();
            request.setBusinessContext(hasText(existing)
                    ? existing.trim() + "\n\n" + parsed.getPriorAnalysisText()
                    : parsed.getPriorAnalysisText());
        }
        if (!parsed.getWarnings().isEmpty()) {
            String warningText = "文本报告解析提示: " + parsed.getWarnings();
            String existing = request.getBusinessContext();
            request.setBusinessContext(hasText(existing)
                    ? existing.trim() + "\n\n" + warningText
                    : warningText);
        }
    }

    private void validateRequest(CreateTuningTaskRequest request, SqlDialect dialect) {
        validateLength("SQL", request.getSqlText(), 32 * 1024);
        validateLength("schemaText", request.getSchemaText(), 128 * 1024);
        validateLength("indexText", request.getIndexText(), 128 * 1024);
        validateLength("explainText", request.getExplainText(), 128 * 1024);
        validateLength("businessContext", request.getBusinessContext(), 16 * 1024);
        validateLength("obVersion", request.getObVersion(), 16 * 1024);
        validateLength("tableStatsText", request.getTableStatsText(), 16 * 1024);
        validateLength("runtimeMetricsText", request.getRuntimeMetricsText(), 16 * 1024);
        validateLength("businessInvariants", request.getBusinessInvariants(), 16 * 1024);
        validateLength("allowedActions", request.getAllowedActions() == null ? null : request.getAllowedActions().toString(), 16 * 1024);
        if (request.getAllowedActions() != null) {
            java.util.Set<String> supported = new java.util.HashSet<String>(java.util.Arrays.asList(
                    "diagnosis", "rewrite", "index", "validation"));
            for (String action : request.getAllowedActions()) {
                if (action == null || !supported.contains(action.trim().toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("allowedActions 包含不支持的建议类型: " + action);
                }
            }
        }
        SqlStatementProfile profile = statementParser.parse(request.getSqlText(), dialect);
        if (!profile.isValid()) {
            throw new IllegalArgumentException(profile.getReason());
        }
    }

    public void run(SqlTuningTask task) {
        log.info("runHarness param 入参: taskId: {}, deepAnalysis: {}", task.getId(), task.isDeepAnalysis());
        validateInputLimits(task);
        SqlDialect dialect = SqlDialect.from(task.getDbDialect());
        SqlStatementProfile profile = statementParser.parse(task.getOriginalSql(), dialect);
        if (!profile.isValid()) {
            throw new IllegalArgumentException(profile.getReason());
        }

        SanitizedSql sanitized = sanitizer.sanitize(task.getOriginalSql(), dialect);
        task.setSanitizedSql(sanitized.getSanitizedSql());
        task.setSqlHash(sanitized.getSqlHash());
        transition(task, TaskStatus.SANITIZED, "SQL 已脱敏");
        addArtifact(task, "sanitize", "完成 SQL 脱敏", sanitized);

        List<RuleFinding> findings = ruleEngine.inspect(task.getSanitizedSql(), task.getIndexText(), task.getExplainText(), task.getDbDialect());
        task.setRuleFindings(findings);
        transition(task, TaskStatus.RULE_CHECKED, "规则扫描完成，命中 " + findings.size() + " 条");
        addArtifact(task, "ruleCheck", "确定性规则扫描完成", findings);

        maybeExtractPlanImages(task);

        ContextPackage context = contextAssessor.assess(task, profile, findings);
        transition(task, TaskStatus.CONTEXT_CHECKED, "上下文证据门禁完成: " + context.getAssessment().getCompleteness());
        addArtifact(task, "contextAssess", "上下文完整度和证据目录", context.getAssessment());

        SkillVersion skill = skillRepository.activeDefault();
        task.setSkillId(skill.getId());
        task.setSkillName(skill.getName());
        task.setSkillVersion(skill.getVersion());
        addArtifact(task, "skillSelect", "绑定技能版本，保证任务可追溯", skillSummary(skill));

        String systemPrompt = promptCompiler.systemPrompt(skill, dialect);
        String userPrompt = promptCompiler.analysisPrompt(task, dialect, profile, context, findings, recentConversationContext(task));
        transition(task, TaskStatus.LLM_ANALYZING, "正在调用模型分析");
        addArtifact(task, "promptBuild", "构建模型输入", promptSummary(userPrompt, skill));
        LlmResponse response = llmClient.analyze(new LlmRequest(systemPrompt, userPrompt, false));
        addArtifact(task, "llmAnalyze", "模型分析完成", response);

        transition(task, TaskStatus.VERIFYING, "正在校验模型结构化输出");
        TuningResult result = parseValidateAndRepairOnce(task, response, context, profile, dialect, systemPrompt);

        if (task.isDeepAnalysis()) {
            transition(task, TaskStatus.REVIEWING, "正在复核模型建议");
            LlmResponse reviewResponse = llmClient.analyze(new LlmRequest(systemPrompt, promptCompiler.reviewPrompt(result), true));
            addArtifact(task, "llmReview", "深度分析复核完成", reviewResponse);
            TuningResult reviewed = parseValidateReviewAndRepairOnce(
                    task, result, reviewResponse, context, profile, dialect, systemPrompt);
            String reviewVerdict = reviewed.getReview().getVerdict().trim().toUpperCase(Locale.ROOT);
            if ("REJECT".equals(reviewVerdict) && !"NEEDS_INPUT".equals(reviewed.getOutcome())) {
                throw new IllegalStateException("深度复核 REJECT 必须返回 outcome=NEEDS_INPUT");
            }
            reviewed.getReview().setVerdict(reviewVerdict);
            result = reviewed;
        }

        applyContextDefaults(result, context);
        applyAllowedActions(result, task);
        appendRuleFindings(result, findings);
        applyLegacyMapping(result);
        task.setResult(result);
        task.setStatus(TaskStatus.DONE);
        task.setStatusMessage("调优建议已生成");
        task.setLeaseOwner(null);
        task.setLeaseUntil(null);
        addArtifact(task, "resultAssemble", "结构化结果组装完成", result.getSummary());
        conversationRepository.addMessage(task.getConversationId(), MessageRole.ASSISTANT, result.getSummary(), task.getId());
        taskRepository.update(task);
        eventBroker.publish(task);
        log.info("runHarness result 结果: taskId: {}, status: {}, findings: {}, mockModel: {}",
                task.getId(), task.getStatus(), result.getFindings().size(), result.isMockModel());
    }

    private void transition(SqlTuningTask task, TaskStatus status, String message) {
        task.setStatus(status);
        task.setStatusMessage(message);
        taskRepository.update(task);
        eventBroker.publish(task);
        log.info("transitionTask result 结果: taskId: {}, status: {}, message: {}", task.getId(), status, message);
    }

    private void addArtifact(SqlTuningTask task, String nodeName, String summary, Object payload) {
        HarnessArtifact artifact = new HarnessArtifact(nodeName, summary, payload, LocalDateTime.now());
        task.getArtifacts().add(artifact);
        if (task.getId() != null) {
            taskRepository.appendArtifact(task.getId(), artifact);
            eventBroker.publishArtifact(task, artifact);
        }
    }

    private void maybeExtractPlanImages(SqlTuningTask task) {
        if (task.getInputImageCount() <= 0) {
            return;
        }
        if (inputImageRepository == null) {
            throw new IllegalStateException("图片输入仓储未初始化");
        }
        List<TaskInputImage> images = inputImageRepository.findByTaskId(task.getId());
        if (images.size() != task.getInputImageCount()) {
            throw new IllegalStateException("图片输入读取不完整");
        }
        List<LlmRequestImage> requestImages = new ArrayList<LlmRequestImage>();
        for (TaskInputImage image : images) {
            requestImages.add(new LlmRequestImage(image.toDataUrl()));
        }
        LlmResponse response = llmClient.analyze(new LlmRequest(
                VISION_SYSTEM_PROMPT,
                VISION_EXTRACTION_PROMPT,
                false,
                null,
                requestImages));
        VisionExtractionResult vision = parseVisionExtractionAndRepairOnce(task, response, requestImages);
        task.setPlanImageFacts(summarizeVision(vision));
        addArtifact(task, "planImageVision",
                vision.isReadable() ? "图片执行计划视觉抽取完成" : "图片不可读，保留提示但不计入证据目录",
                vision);
    }

    private VisionExtractionResult parseVisionExtractionAndRepairOnce(SqlTuningTask task,
                                                                       LlmResponse response,
                                                                       List<LlmRequestImage> requestImages) {
        try {
            return parseVisionExtraction(response);
        } catch (IllegalStateException firstFailure) {
            addArtifact(task, "planImageVisionValidateFailed", "图片视觉输出未通过严格校验，执行一次修复",
                    firstFailure.getMessage());
            String repairPrompt = "上一次图片事实抽取输出未通过严格校验。请重新查看随请求附带的原始图片，"
                    + "只返回完整严格 JSON，不要解释。字段必须包含 readable, operators, tables, rowEstimates, warnings, rawTextSummary。"
                    + "不可读时返回 readable=false，不得编造。校验错误: " + firstFailure.getMessage()
                    + "。上一次输出: " + abbreviate(response.getContent(), 4000);
            LlmResponse repaired = llmClient.analyze(new LlmRequest(
                    VISION_SYSTEM_PROMPT,
                    repairPrompt,
                    false,
                    null,
                    requestImages));
            addArtifact(task, "planImageVisionRepair", "图片视觉 JSON 修复调用完成", repaired);
            try {
                return parseVisionExtraction(repaired);
            } catch (IllegalStateException secondFailure) {
                VisionExtractionResult unreadable = new VisionExtractionResult();
                unreadable.setReadable(false);
                unreadable.getWarnings().add("视觉模型两次未返回可解析 JSON，图片未计入证据目录");
                unreadable.setRawTextSummary("未能从图片中提取可核验事实，请补充清晰截图或文本 EXPLAIN");
                addArtifact(task, "planImageVisionRepairFailed", "图片视觉修复仍不可解析，按不可读证据降级", secondFailure.getMessage());
                return unreadable;
            }
        }
    }

    private VisionExtractionResult parseVisionExtraction(LlmResponse response) {
        String json = stripSingleJsonFence(response.getContent());
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw new IllegalStateException("视觉模型输出根节点不是对象");
            }
            VisionExtractionResult vision = new VisionExtractionResult();
            JsonNode operators = firstNode(root, "operators", "operatorList", "executionPlanOperators");
            JsonNode tables = firstNode(root, "tables", "tableNames", "tableList");
            JsonNode rows = firstNode(root, "rowEstimates", "row_estimates", "estimatedRows", "rows");
            vision.setOperators(objectValues(operators));
            vision.setTables(objectValues(tables));
            vision.setRowEstimates(objectValues(rows));
            vision.setWarnings(stringValues(firstNode(root, "warnings", "warning", "notes")));
            String summary = firstText(root, "rawTextSummary", "raw_text_summary", "summary", "visibleText", "ocrText");
            if (!hasText(summary)) {
                summary = recognizedVisionSummary(vision);
            }
            boolean hasFacts = !vision.getOperators().isEmpty() || !vision.getTables().isEmpty()
                    || !vision.getRowEstimates().isEmpty() || hasText(summary);
            JsonNode readable = root.get("readable");
            vision.setReadable(readable != null && readable.isBoolean() ? readable.asBoolean() : hasFacts);
            if (readable == null && hasFacts) {
                vision.getWarnings().add("模型未显式返回 readable，后端依据已抽取事实按可读处理");
            }
            if (!hasText(summary)) {
                vision.setReadable(false);
                summary = "未能从图片中提取可核验事实";
            }
            vision.setRawTextSummary(summary);
            return vision;
        } catch (Exception e) {
            log.warn("parseVisionExtraction result 结果: 视觉输出不是严格 JSON, errorType: {}",
                    e.getClass().getSimpleName());
            throw new IllegalStateException("视觉模型输出不是合法 JSON 或缺少必要字段", e);
        }
    }

    private JsonNode firstNode(JsonNode root, String... names) {
        for (String name : names) {
            JsonNode value = root.get(name);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private List<Object> objectValues(JsonNode node) {
        List<Object> values = new ArrayList<Object>();
        if (node == null || node.isNull()) {
            return values;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                values.add(objectMapper.convertValue(item, Object.class));
            }
        } else {
            values.add(objectMapper.convertValue(node, Object.class));
        }
        return values;
    }

    private List<String> stringValues(JsonNode node) {
        List<String> values = new ArrayList<String>();
        if (node == null || node.isNull()) {
            return values;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                values.add(item.isTextual() ? item.asText() : abbreviate(item.toString(), 500));
            }
        } else {
            values.add(node.isTextual() ? node.asText() : abbreviate(node.toString(), 500));
        }
        return values;
    }

    private String recognizedVisionSummary(VisionExtractionResult vision) {
        if (vision.getOperators().isEmpty() && vision.getTables().isEmpty() && vision.getRowEstimates().isEmpty()) {
            return "";
        }
        return "operators=" + vision.getOperators()
                + "; tables=" + vision.getTables()
                + "; rowEstimates=" + vision.getRowEstimates();
    }

    private String summarizeVision(VisionExtractionResult vision) {
        StringBuilder builder = new StringBuilder();
        builder.append("readable=").append(vision.isReadable()).append("\n");
        builder.append("operators=").append(vision.getOperators()).append("\n");
        builder.append("tables=").append(vision.getTables()).append("\n");
        builder.append("rowEstimates=").append(vision.getRowEstimates()).append("\n");
        builder.append("warnings=").append(vision.getWarnings()).append("\n");
        builder.append("rawTextSummary=").append(abbreviate(vision.getRawTextSummary(), 2000));
        return builder.toString();
    }

    private void validateInputLimits(SqlTuningTask task) {
        validateLength("SQL", task.getOriginalSql(), 32 * 1024);
        validateLength("schemaText", task.getSchemaText(), 128 * 1024);
        validateLength("indexText", task.getIndexText(), 128 * 1024);
        validateLength("explainText", task.getExplainText(), 128 * 1024);
        validateLength("businessContext", task.getBusinessContext(), 16 * 1024);
        validateLength("obVersion", task.getObVersion(), 16 * 1024);
        validateLength("tableStatsText", task.getTableStatsText(), 16 * 1024);
        validateLength("runtimeMetricsText", task.getRuntimeMetricsText(), 16 * 1024);
        validateLength("businessInvariants", task.getBusinessInvariants(), 16 * 1024);
        validateLength("allowedActions", task.getAllowedActions() == null ? null : task.getAllowedActions().toString(), 16 * 1024);
    }

    private void validateLength(String field, String value, int max) {
        if (value != null && value.length() > max) {
            throw new IllegalArgumentException(field + " 超过限制 " + max + " 字符");
        }
    }

    private String recentConversationContext(SqlTuningTask task) {
        List<Message> messages = conversationRepository.listMessages(task.getConversationId());
        if (messages.isEmpty()) {
            return "未提供";
        }
        StringBuilder builder = new StringBuilder();
        int start = Math.max(0, messages.size() - 8);
        for (int i = start; i < messages.size(); i++) {
            Message message = messages.get(i);
            // 当前任务的用户输入已在主输入区提供，避免重复放大 prompt。
            if (task.getId() != null && task.getId().equals(message.getTaskId()) && message.getRole() == MessageRole.USER) {
                continue;
            }
            builder.append(message.getRole() == MessageRole.USER ? "用户: " : "助手: ")
                    .append(abbreviate(message.getContent(), 500))
                    .append("\n");
        }
        return builder.length() == 0 ? "未提供" : builder.toString();
    }

    private TuningResult parseValidateReviewAndRepairOnce(SqlTuningTask task,
                                                          TuningResult original,
                                                          LlmResponse response,
                                                          ContextPackage context,
                                                          SqlStatementProfile profile,
                                                          SqlDialect dialect,
                                                          String systemPrompt) {
        String errors;
        try {
            TuningResult reviewed = parseReviewResponse(response, original, context);
            errors = reviewErrors(reviewed, context, profile, dialect);
            if (!hasText(errors)) {
                return reviewed;
            }
        } catch (ModelOutputFormatException e) {
            errors = e.getMessage();
        }

        addArtifact(task, "reviewValidateFailed", "深度复核输出未通过严格校验，执行一次修复", errors);
        LlmResponse repaired = llmClient.analyze(new LlmRequest(
                systemPrompt,
                promptCompiler.reviewRepairPrompt(response.getContent(), errors),
                true));
        addArtifact(task, "llmReviewRepair", "深度复核 JSON 修复调用完成", repaired);
        final TuningResult reviewed;
        try {
            reviewed = parseReviewResponse(repaired, original, context);
        } catch (ModelOutputFormatException e) {
            throw new IllegalStateException("深度复核修复输出不是合法 JSON", e);
        }
        String repairedErrors = reviewErrors(reviewed, context, profile, dialect);
        if (hasText(repairedErrors)) {
            throw new IllegalStateException("深度复核修复输出未通过严格校验: " + repairedErrors);
        }
        return reviewed;
    }

    private TuningResult parseReviewResponse(LlmResponse response,
                                             TuningResult original,
                                             ContextPackage context) {
        String json = stripSingleJsonFence(response.getContent());
        final JsonNode root;
        try {
            root = objectMapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("深度复核根节点必须是 JSON 对象");
            }
            if (!root.has("verdict")) {
                // 兼容一版旧审查器：旧协议直接返回带 review 字段的完整 TuningResult。
                return parseStrictResult(response, context);
            }

            requireText(root, "verdict", "reviewEnvelope");
            requireText(root, "notes", "reviewEnvelope");
            requireStringArray(root, "revisions", "reviewEnvelope");
            String verdict = root.get("verdict").asText().trim().toUpperCase(Locale.ROOT);
            if (!"PASS".equals(verdict) && !"REVISE".equals(verdict) && !"REJECT".equals(verdict)) {
                throw new IllegalArgumentException("深度复核 verdict 必须为 PASS、REVISE 或 REJECT");
            }

            ReviewResult review = new ReviewResult();
            review.setVerdict(verdict);
            review.setNotes(root.get("notes").asText());
            List<String> revisions = new ArrayList<String>();
            for (JsonNode revision : root.get("revisions")) {
                revisions.add(revision.asText());
            }
            review.setRevisions(revisions);

            if ("PASS".equals(verdict)) {
                original.setReview(review);
                return original;
            }

            JsonNode revisedResult = requireObject(root, "revisedResult", "reviewEnvelope");
            LlmResponse revisedResponse = new LlmResponse(
                    response.getProvider(),
                    response.getModel(),
                    objectMapper.writeValueAsString(revisedResult),
                    response.getElapsedMs(),
                    response.isMock());
            TuningResult revised = parseStrictResult(revisedResponse, context);
            revised.setReview(review);
            return revised;
        } catch (ModelOutputFormatException e) {
            throw e;
        } catch (Exception e) {
            log.warn("parseReviewResponse result 结果: 深度复核输出不是严格 JSON 包络, errorType: {}",
                    e.getClass().getSimpleName());
            throw new ModelOutputFormatException("深度复核输出不是合法 JSON 包络", e);
        }
    }

    private String reviewErrors(TuningResult reviewed,
                                ContextPackage context,
                                SqlStatementProfile profile,
                                SqlDialect dialect) {
        ValidationOutcome validation = resultValidator.validate(reviewed, context, profile, dialect);
        List<String> errors = new ArrayList<String>(validation.getErrors());
        if (reviewed.getReview() == null || !hasText(reviewed.getReview().getVerdict())) {
            errors.add("深度复核缺少 PASS/REVISE/REJECT 结论");
        } else {
            String verdict = reviewed.getReview().getVerdict().trim().toUpperCase(Locale.ROOT);
            if (!"PASS".equals(verdict) && !"REVISE".equals(verdict) && !"REJECT".equals(verdict)) {
                errors.add("深度复核结论必须为 PASS、REVISE 或 REJECT");
            }
            if ("REJECT".equals(verdict) && !"NEEDS_INPUT".equals(reviewed.getOutcome())) {
                errors.add("深度复核 REJECT 必须返回 outcome=NEEDS_INPUT");
            }
        }
        return errors.isEmpty() ? "" : String.join("; ", errors);
    }

    private TuningResult parseStrictResult(LlmResponse response, ContextPackage context) {
        TuningResult result;
        String json = stripSingleJsonFence(response.getContent());
        try {
            JsonNode root = objectMapper.readTree(json);
            validateStrictJsonShape(root);
            result = objectMapper.treeToValue(root, TuningResult.class);
        } catch (Exception e) {
            // Jackson 的异常消息可能内嵌完整模型输出，不能写入日志或任务状态。
            log.warn("parseStrictResult result 结果: 模型输出不是严格 JSON, errorType: {}",
                    e.getClass().getSimpleName());
            throw new ModelOutputFormatException("模型输出不是合法 JSON", e);
        }
        result.setRawModelOutput(response.getContent());
        result.setMockModel(response.isMock());
        applyContextDefaults(result, context);
        return result;
    }

    private void validateStrictJsonShape(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("根节点必须是 JSON 对象");
        }
        requireText(root, "outcome", "root");
        requireText(root, "summary", "root");
        JsonNode assessment = requireObject(root, "contextAssessment", "root");
        requireText(assessment, "completeness", "contextAssessment");
        requireText(assessment, "maxConfidence", "contextAssessment");
        requireStringArray(assessment, "availableEvidence", "contextAssessment");
        requireStringArray(assessment, "missingInformation", "contextAssessment");
        requireStringArray(assessment, "policyNotes", "contextAssessment");

        JsonNode evidence = requireArray(root, "evidenceCatalog", "root");
        for (int i = 0; i < evidence.size(); i++) {
            JsonNode item = requireArrayObject(evidence, i, "evidenceCatalog");
            requireText(item, "id", "evidenceCatalog[" + i + "]");
            requireText(item, "source", "evidenceCatalog[" + i + "]");
            requireText(item, "summary", "evidenceCatalog[" + i + "]");
            requireText(item, "trustLevel", "evidenceCatalog[" + i + "]");
        }

        validateSuggestionArray(root, "diagnoses", new String[]{"severity", "title", "impact", "confidence", "precondition"}, true);
        validateSuggestionArray(root, "rewriteCandidates", new String[]{"sql", "change", "semanticCheck", "risk", "validation"}, true);
        JsonNode indexes = validateSuggestionArray(root, "indexCandidates",
                new String[]{"tableName", "ddl", "benefit", "writeCost", "risk", "validation", "confidence"}, true);
        for (int i = 0; i < indexes.size(); i++) {
            requireStringArray(indexes.get(i), "columnOrder", "indexCandidates[" + i + "]");
        }
        validateSuggestionArray(root, "validationPlan", new String[]{"action", "expectedSignal"}, true);
        requireStringArray(root, "missingInformation", "root");
        requireStringArray(root, "safetyWarnings", "root");
        JsonNode review = requireObject(root, "review", "root");
        requireText(review, "verdict", "review");
        requireText(review, "notes", "review");
    }

    private JsonNode validateSuggestionArray(JsonNode root,
                                             String field,
                                             String[] textFields,
                                             boolean requireEvidenceRefs) {
        JsonNode array = requireArray(root, field, "root");
        for (int i = 0; i < array.size(); i++) {
            JsonNode item = requireArrayObject(array, i, field);
            String path = field + "[" + i + "]";
            for (String textField : textFields) {
                requireText(item, textField, path);
            }
            if (requireEvidenceRefs) {
                requireStringArray(item, "evidenceRefs", path);
            }
        }
        return array;
    }

    private JsonNode requireObject(JsonNode parent, String field, String path) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(path + "." + field + " 必须是对象");
        }
        return value;
    }

    private JsonNode requireArray(JsonNode parent, String field, String path) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException(path + "." + field + " 必须是数组");
        }
        return value;
    }

    private JsonNode requireArrayObject(JsonNode array, int index, String path) {
        JsonNode value = array.get(index);
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(path + "[" + index + "] 必须是对象");
        }
        return value;
    }

    private void requireText(JsonNode parent, String field, String path) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(path + "." + field + " 必须是字符串");
        }
    }

    private void requireStringArray(JsonNode parent, String field, String path) {
        JsonNode array = requireArray(parent, field, path);
        for (int i = 0; i < array.size(); i++) {
            if (!array.get(i).isTextual()) {
                throw new IllegalArgumentException(path + "." + field + "[" + i + "] 必须是字符串");
            }
        }
    }

    private TuningResult parseValidateAndRepairOnce(SqlTuningTask task,
                                                    LlmResponse response,
                                                    ContextPackage context,
                                                    SqlStatementProfile profile,
                                                    SqlDialect dialect,
                                                    String systemPrompt) {
        TuningResult result = null;
        String validationErrors;
        try {
            result = parseStrictResult(response, context);
            ValidationOutcome validation = resultValidator.validate(result, context, profile, dialect);
            if (validation.isValid()) {
                return result;
            }
            validationErrors = validation.summary();
        } catch (ModelOutputFormatException e) {
            validationErrors = e.getMessage();
        }

        addArtifact(task, "resultValidateFailed", "模型输出未通过严格校验，执行一次修复", validationErrors);
        LlmResponse repaired = llmClient.analyze(new LlmRequest(
                systemPrompt,
                promptCompiler.repairPrompt(response.getContent(), validationErrors),
                false));
        addArtifact(task, "llmRepair", "模型 JSON 修复调用完成", repaired);

        try {
            result = parseStrictResult(repaired, context);
        } catch (ModelOutputFormatException e) {
            throw new IllegalStateException("模型修复输出不是合法 JSON: " + e.getMessage(), e);
        }
        ValidationOutcome repairedValidation = resultValidator.validate(result, context, profile, dialect);
        if (!repairedValidation.isValid()) {
            throw new IllegalStateException("模型修复输出未通过严格校验: " + repairedValidation.summary());
        }
        return result;
    }

    /**
     * 兼容模型常见的单层 Markdown JSON 代码块，同时拒绝代码块外的解释文字。
     * 真正非法的内容仍会进入一次、且仅一次修复调用。
     */
    private String stripSingleJsonFence(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstLineEnd = trimmed.indexOf('\n');
        if (firstLineEnd < 0 || !trimmed.endsWith("```")) {
            return trimmed;
        }
        String opener = trimmed.substring(0, firstLineEnd).trim();
        if (!"```".equals(opener) && !"```json".equalsIgnoreCase(opener)) {
            return trimmed;
        }
        return trimmed.substring(firstLineEnd + 1, trimmed.length() - 3).trim();
    }

    private static final class ModelOutputFormatException extends RuntimeException {
        private ModelOutputFormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private void applyContextDefaults(TuningResult result, ContextPackage context) {
        if (!hasText(result.getOutcome())) {
            result.setOutcome(context.isAllowRewrite() ? "ADVICE" : "NEEDS_INPUT");
        }
        if (!hasText(result.getSummary())) {
            result.setSummary("已完成结构化 SQL 诊断。");
        }
        // 完整度和证据目录由后端事实包生成，绝不接受模型自行抬高证据等级或虚构证据。
        result.setContextAssessment(context.getAssessment());
        result.setEvidenceCatalog(context.getEvidenceCatalog());
        if (result.getReview() == null) {
            ReviewResult review = new ReviewResult();
            review.setVerdict("NOT_REQUESTED");
            review.setNotes("");
            result.setReview(review);
        }
        if (result.getMissingInformation().isEmpty()) {
            result.getMissingInformation().addAll(context.getAssessment().getMissingInformation());
        }
        if (!context.isAllowRewrite()) {
            result.getRewriteCandidates().clear();
        }
        if (!context.isAllowIndexDirection()) {
            result.getIndexCandidates().clear();
        }
    }

    private void applyAllowedActions(TuningResult result, SqlTuningTask task) {
        if (task.getAllowedActions() == null || task.getAllowedActions().isEmpty()) {
            return;
        }
        java.util.Set<String> allowed = new java.util.HashSet<String>();
        for (String action : task.getAllowedActions()) {
            if (action != null) {
                allowed.add(action.trim().toLowerCase(Locale.ROOT));
            }
        }
        List<String> removed = new ArrayList<String>();
        if (!allowed.contains("diagnosis") && !result.getDiagnoses().isEmpty()) {
            result.getDiagnoses().clear();
            removed.add("diagnosis");
        }
        if (!allowed.contains("rewrite") && !result.getRewriteCandidates().isEmpty()) {
            result.getRewriteCandidates().clear();
            removed.add("rewrite");
        }
        if (!allowed.contains("index") && !result.getIndexCandidates().isEmpty()) {
            result.getIndexCandidates().clear();
            removed.add("index");
        }
        if (!allowed.contains("validation") && !result.getValidationPlan().isEmpty()) {
            result.getValidationPlan().clear();
            removed.add("validation");
        }
        if (!removed.isEmpty()) {
            result.getSafetyWarnings().add("已按 allowedActions 移除未授权建议类型: " + removed);
        }
    }

    private void applyLegacyMapping(TuningResult result) {
        if (result.getFindings().isEmpty()) {
            for (Diagnosis diagnosis : result.getDiagnoses()) {
                ResultFinding finding = new ResultFinding();
                finding.setTitle(diagnosis.getTitle());
                finding.setEvidence(String.valueOf(diagnosis.getEvidenceRefs()));
                finding.setImpact(diagnosis.getImpact());
                finding.setConfidence(diagnosis.getConfidence());
                result.getFindings().add(finding);
            }
        }
        if (!hasText(result.getRewriteSql()) && !result.getRewriteCandidates().isEmpty()) {
            result.setRewriteSql(result.getRewriteCandidates().get(0).getSql());
        }
        if (result.getIndexSuggestions().isEmpty()) {
            for (IndexCandidate candidate : result.getIndexCandidates()) {
                IndexSuggestion suggestion = new IndexSuggestion();
                suggestion.setIndexName(hasText(candidate.getDdl()) ? candidate.getDdl() : "候选索引: " + candidate.getTableName());
                suggestion.setColumns(candidate.getColumnOrder());
                suggestion.setBenefit(candidate.getBenefit());
                suggestion.setRisk(firstNonEmpty(candidate.getRisk(), candidate.getWriteCost()));
                suggestion.setValidation(candidate.getValidation());
                result.getIndexSuggestions().add(suggestion);
            }
        }
        if (result.getValidationSteps().isEmpty()) {
            for (ValidationStep step : result.getValidationPlan()) {
                result.getValidationSteps().add(step.getAction() + (hasText(step.getExpectedSignal()) ? "；观察: " + step.getExpectedSignal() : ""));
            }
        }
        if (result.getRiskWarnings().isEmpty()) {
            result.getRiskWarnings().addAll(result.getSafetyWarnings());
        }
        if (result.getNeedMoreInfo().isEmpty()) {
            result.getNeedMoreInfo().addAll(result.getMissingInformation());
        }
    }

    private String firstNonEmpty(String left, String right) {
        return hasText(left) ? left : right;
    }

    private void appendRuleFindings(TuningResult result, List<RuleFinding> ruleFindings) {
        for (RuleFinding rule : ruleFindings) {
            ResultFinding finding = new ResultFinding();
            finding.setTitle(rule.getTitle());
            finding.setEvidence(rule.getEvidence());
            finding.setImpact(rule.getSuggestion());
            finding.setConfidence("rule-" + rule.getSeverity().name().toLowerCase());
            result.getFindings().add(finding);
        }
    }

    private Object safeContextSummary(SqlTuningTask task) {
        return "dbDialect=" + task.getDbDialect()
                + ", sqlLength=" + (task.getOriginalSql() == null ? 0 : task.getOriginalSql().length())
                + ", hasExplain=" + hasText(task.getExplainText())
                + ", hasSchema=" + hasText(task.getSchemaText())
                + ", hasIndex=" + hasText(task.getIndexText());
    }

    private Object skillSummary(SkillVersion skill) {
        return "skillName=" + skill.getName() + ", version=" + skill.getVersion() + ", contentLength=" + skill.getContent().length();
    }

    private Object promptSummary(String prompt, SkillVersion skill) {
        return "skillName=" + skill.getName() + ", version=" + skill.getVersion() + ", promptLength=" + prompt.length();
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\r', ' ').trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private String firstText(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.asText("");
                if (hasText(text)) {
                    return text;
                }
            }
        }
        return "";
    }

    private String resolveInputType(String requestedType, String input) {
        if (hasText(requestedType)) {
            return requestedType.trim();
        }
        String normalized = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("select ")
                || normalized.startsWith("update ")
                || normalized.startsWith("delete ")
                || normalized.startsWith("insert ")
                || normalized.startsWith("with ")
                || normalized.startsWith("explain ")) {
            return "sql";
        }
        return "natural_language";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
