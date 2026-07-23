package com.codex.sqltuner.tuning;

import com.codex.sqltuner.conversation.Conversation;
import com.codex.sqltuner.conversation.ConversationRepository;
import com.codex.sqltuner.conversation.MessageRole;
import com.codex.sqltuner.rule.SqlDialect;
import com.codex.sqltuner.tuning.accuracy.SqlStatementParser;
import com.codex.sqltuner.tuning.accuracy.SqlStatementProfile;
import com.codex.sqltuner.tuning.input.ParsedReport;
import com.codex.sqltuner.tuning.input.ReportTextParser;
import com.codex.sqltuner.tuning.inputimage.InputImageRepository;
import com.codex.sqltuner.tuning.inputimage.InputImageValidator;
import com.codex.sqltuner.tuning.inputimage.TaskInputImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Owns request normalization, validation, and durable task creation.
 */
final class TuningTaskCreator {
    private static final Logger log = LoggerFactory.getLogger(TuningTaskCreator.class);
    private static final Pattern SQL_FIELD_CLAIM = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])SQL(?!\\s*ID\\b)\\s*[:：]");

    private final TuningTaskRepository taskRepository;
    private final ConversationRepository conversationRepository;
    private final SqlStatementParser statementParser;
    private final InputImageValidator inputImageValidator;
    private final InputImageRepository inputImageRepository;
    private final TuningContextAssembler contextAssembler;
    private final TaskExecutionCoordinator taskExecution;
    private final ReportTextParser reportTextParser = new ReportTextParser();

    TuningTaskCreator(TuningTaskRepository taskRepository,
                      ConversationRepository conversationRepository,
                      SqlStatementParser statementParser,
                      InputImageValidator inputImageValidator,
                      InputImageRepository inputImageRepository,
                      TuningContextAssembler contextAssembler,
                      TaskExecutionCoordinator taskExecution) {
        this.taskRepository = taskRepository;
        this.conversationRepository = conversationRepository;
        this.statementParser = statementParser;
        this.inputImageValidator = inputImageValidator;
        this.inputImageRepository = inputImageRepository;
        this.contextAssembler = contextAssembler;
        this.taskExecution = taskExecution;
    }

    SqlTuningTask create(Long userId, CreateTuningTaskRequest request, String idempotencyKey) {
        String submittedText = request.getSqlText();
        Long conversationId = request.getConversationId();
        SqlTuningTask previousTask = null;
        if (conversationId != null) {
            // 与过期会话清理共用父行锁，避免用户继续旧会话时被清理任务并发删除。
            conversationRepository.getForUserForUpdate(conversationId, userId);
            previousTask = taskRepository.findLatestForConversation(conversationId, userId);
        }
        boolean inheritedConversationContext = normalizeInput(request, previousTask, submittedText);
        SqlDialect dialect = SqlDialect.from(request.getDbDialect());
        validateRequest(request, dialect);
        List<TaskInputImage> inputImages = inputImageValidator.validate(request.getPlanImages());
        if (conversationId == null) {
            Conversation conversation = conversationRepository.create(userId, "新建调优");
            conversationId = conversation.getId();
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
        if (inheritedConversationContext && inputImages.isEmpty() && previousTask != null) {
            task.setPlanImageFacts(previousTask.getPlanImageFacts());
        }
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

        // 分析任务使用报告中提取出的单条 SQL，会话消息保留用户实际提交的完整原文。
        conversationRepository.addMessage(conversationId, MessageRole.USER, submittedText, task.getId());
        taskExecution.addArtifact(task, "received", "接收用户输入和上下文", contextAssembler.safeContextSummary(task));
        taskRepository.update(task);
        taskExecution.publish(task);
        log.info("createTask result 结果: taskId: {}, userId: {}, conversationId: {}, dbDialect: {}, inputType: {}, inputLength: {}",
                task.getId(), userId, conversationId, task.getDbDialect(), task.getInputType(), submittedText == null ? 0 : submittedText.length());
        return task;
    }

    private boolean normalizeInput(CreateTuningTaskRequest request,
                                   SqlTuningTask previousTask,
                                   String submittedText) {
        try {
            normalizePastedReport(request);
            return false;
        } catch (IllegalArgumentException error) {
            if (!isSupplementalMessage(request, error)) {
                throw error;
            }
            if (previousTask == null || !TuningText.hasText(previousTask.getOriginalSql())) {
                throw new IllegalArgumentException("请先提供一条可解析的 SQL，再补充执行计划或其他证据");
            }
            inheritConversationContext(request, previousTask, submittedText);
            return true;
        }
    }

    private boolean isSupplementalMessage(CreateTuningTaskRequest request, IllegalArgumentException error) {
        if (!"报告中未找到 SQL 字段".equals(error.getMessage())) {
            return false;
        }
        String inputType = request.getInputType();
        if (TuningText.hasText(inputType) && "sql".equalsIgnoreCase(inputType.trim())) {
            return false;
        }
        String text = request.getSqlText();
        return !TuningText.hasText(text) || !SQL_FIELD_CLAIM.matcher(text).find();
    }

    private void inheritConversationContext(CreateTuningTaskRequest request,
                                            SqlTuningTask previousTask,
                                            String submittedText) {
        ParsedReport supplemental = TuningText.hasText(submittedText)
                ? reportTextParser.parseSupplement(submittedText)
                : null;
        String suppliedSchema = TuningText.joinDistinctNonEmpty(
                request.getSchemaText(), supplemental == null ? null : supplemental.getSchemaText());
        String suppliedIndexes = TuningText.joinDistinctNonEmpty(
                request.getIndexText(), supplemental == null ? null : supplemental.getIndexText());
        String suppliedExplain = TuningText.joinDistinctNonEmpty(
                request.getExplainText(), supplemental == null ? null : supplemental.getExplainText());
        String suppliedStats = TuningText.joinDistinctNonEmpty(
                request.getTableStatsText(), supplemental == null ? null : supplemental.getTableStatsText());
        String suppliedRuntime = TuningText.joinDistinctNonEmpty(
                request.getRuntimeMetricsText(), supplemental == null ? null : supplemental.getRuntimeMetricsText());
        String suppliedBusinessInvariants = TuningText.joinDistinctNonEmpty(
                request.getBusinessInvariants(), supplemental == null ? null : supplemental.getBusinessInvariants());
        List<String> suppliedAllowedActions = supplemental == null ? null : supplemental.getAllowedActions();

        request.setSqlText(previousTask.getOriginalSql());
        request.setDbDialect(previousTask.getDbDialect());
        request.setInputType("natural_language");
        request.setSchemaText(TuningText.joinDistinctNonEmpty(previousTask.getSchemaText(), suppliedSchema));
        request.setIndexText(TuningText.joinDistinctNonEmpty(previousTask.getIndexText(), suppliedIndexes));
        request.setExplainText(TuningText.joinDistinctNonEmpty(previousTask.getExplainText(), suppliedExplain));
        request.setTableStatsText(TuningText.joinDistinctNonEmpty(previousTask.getTableStatsText(), suppliedStats));
        request.setRuntimeMetricsText(TuningText.joinDistinctNonEmpty(previousTask.getRuntimeMetricsText(), suppliedRuntime));
        request.setObVersion(TuningText.firstNonEmpty(
                request.getObVersion(),
                supplemental == null ? null : supplemental.getObVersion(),
                previousTask.getObVersion()));
        if (!TuningText.hasText(suppliedBusinessInvariants)) {
            request.setBusinessInvariants(previousTask.getBusinessInvariants());
        } else {
            request.setBusinessInvariants(TuningText.joinDistinctNonEmpty(previousTask.getBusinessInvariants(), suppliedBusinessInvariants));
        }
        if (request.getAllowedActions() == null || request.getAllowedActions().isEmpty()) {
            request.setAllowedActions(suppliedAllowedActions != null && !suppliedAllowedActions.isEmpty()
                    ? new ArrayList<String>(suppliedAllowedActions)
                    : (previousTask.getAllowedActions() == null
                    ? null
                    : new ArrayList<String>(previousTask.getAllowedActions())));
        }

        String supplementalContext = TuningText.hasText(submittedText)
                ? "本轮用户补充（作为待核验背景）:\n" + TuningText.abbreviate(submittedText.trim(), TuningContextAssembler.CURRENT_SUPPLEMENT_MAX_CHARS)
                : "本轮用户仅补充了执行计划截图";
        String parserWarnings = supplemental != null && !supplemental.getWarnings().isEmpty()
                ? "文本补充解析提示: " + supplemental.getWarnings()
                : null;
        String snapshot = conversationRepository.getContextSnapshot(previousTask.getConversationId());
        if (!TuningText.hasText(snapshot)) {
            snapshot = contextAssembler.buildConversationContextSnapshot(previousTask);
        }
        String currentSupplement = TuningText.abbreviateUtf8(TuningText.joinNonEmpty(
                TuningText.hasText(request.getBusinessContext()) ? "本轮业务补充:\n" + request.getBusinessContext() : null,
                supplemental == null || !TuningText.hasText(supplemental.getPriorAnalysisText())
                        ? null
                        : "本轮已有分析文本:\n" + supplemental.getPriorAnalysisText(),
                parserWarnings,
                supplementalContext), TuningContextAssembler.CURRENT_SUPPLEMENT_MAX_CHARS);
        request.setBusinessContext(TuningText.abbreviateUtf8(TuningText.joinNonEmpty(
                TuningText.hasText(snapshot) ? "会话上下文快照（确定性摘要）:\n" + snapshot : null,
                TuningText.hasText(currentSupplement) ? "本轮补充（待核验）:\n" + currentSupplement : null),
                TuningContextAssembler.CONTEXT_SNAPSHOT_MAX_CHARS + TuningContextAssembler.CURRENT_SUPPLEMENT_MAX_CHARS));
    }

    private void normalizePastedReport(CreateTuningTaskRequest request) {
        ParsedReport parsed = reportTextParser.parse(request.getSqlText());
        boolean structuredReport = TuningText.hasText(parsed.getRuntimeMetricsText())
                || TuningText.hasText(parsed.getTableStatsText())
                || TuningText.hasText(parsed.getPriorAnalysisText())
                || TuningText.hasText(parsed.getExplainText())
                || TuningText.hasText(parsed.getSchemaText())
                || TuningText.hasText(parsed.getIndexText())
                || TuningText.hasText(parsed.getObVersion())
                || TuningText.hasText(parsed.getBusinessInvariants())
                || !parsed.getAllowedActions().isEmpty();
        if (!structuredReport) {
            return;
        }
        request.setSqlText(parsed.getExtractedSql());
        if (!TuningText.hasText(request.getDbDialect()) || "OceanBase Oracle".equals(parsed.getInferredDialect())) {
            // 只有 Oracle 专有语法才足以覆盖用户选择；普通 SELECT 默认 MySQL 不能反向推断方言。
            request.setDbDialect(parsed.getInferredDialect());
        }
        request.setInputType("sql");
        if (!TuningText.hasText(request.getRuntimeMetricsText())) {
            request.setRuntimeMetricsText(parsed.getRuntimeMetricsText());
        }
        if (!TuningText.hasText(request.getTableStatsText())) {
            request.setTableStatsText(parsed.getTableStatsText());
        }
        if (!TuningText.hasText(request.getExplainText())) {
            request.setExplainText(parsed.getExplainText());
        }
        if (!TuningText.hasText(request.getSchemaText())) {
            request.setSchemaText(parsed.getSchemaText());
        }
        if (!TuningText.hasText(request.getIndexText())) {
            request.setIndexText(parsed.getIndexText());
        }
        if (!TuningText.hasText(request.getObVersion())) {
            request.setObVersion(parsed.getObVersion());
        }
        if (!TuningText.hasText(request.getBusinessInvariants())) {
            request.setBusinessInvariants(parsed.getBusinessInvariants());
        }
        if (request.getAllowedActions() == null || request.getAllowedActions().isEmpty()) {
            request.setAllowedActions(parsed.getAllowedActions());
        }
        if (TuningText.hasText(parsed.getPriorAnalysisText())) {
            String existing = request.getBusinessContext();
            request.setBusinessContext(TuningText.hasText(existing)
                    ? existing.trim() + "\n\n" + parsed.getPriorAnalysisText()
                    : parsed.getPriorAnalysisText());
        }
        if (!parsed.getWarnings().isEmpty()) {
            String warningText = "文本报告解析提示: " + parsed.getWarnings();
            String existing = request.getBusinessContext();
            request.setBusinessContext(TuningText.hasText(existing)
                    ? existing.trim() + "\n\n" + warningText
                    : warningText);
        }
    }

    private void validateRequest(CreateTuningTaskRequest request, SqlDialect dialect) {
        contextAssembler.validateRequestLimits(request);
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

    private String resolveInputType(String requestedType, String input) {
        if (TuningText.hasText(requestedType)) {
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
}
