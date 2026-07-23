package com.codex.sqltuner.tuning;

import com.codex.sqltuner.conversation.ConversationRepository;
import com.codex.sqltuner.llm.LlmClient;
import com.codex.sqltuner.llm.LlmRequest;
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
import com.codex.sqltuner.tuning.inputimage.InputImageRepository;
import com.codex.sqltuner.tuning.inputimage.InputImageValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * Coordinates the SQL tuning flow; input, context, model stages, and completion
 * each live in a focused package-private collaborator.
 */
@Service
public class TuningHarnessService {
    private static final Logger log = LoggerFactory.getLogger(TuningHarnessService.class);

    private final SqlSanitizer sanitizer;
    private final RuleEngine ruleEngine;
    private final SkillRepository skillRepository;
    private final SqlStatementParser statementParser;
    private final ContextAssessor contextAssessor;
    private final PromptCompiler promptCompiler;
    private final TuningTaskCreator taskCreator;
    private final TuningContextAssembler contextAssembler;
    private final TaskExecutionCoordinator taskExecution;
    private final TuningModelStageExecutor modelStages;
    private final TuningResultFinalizer resultFinalizer;

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
        this.sanitizer = sanitizer;
        this.ruleEngine = ruleEngine;
        this.skillRepository = skillRepository;
        this.statementParser = statementParser;
        this.contextAssessor = contextAssessor;
        this.promptCompiler = promptCompiler;
        this.taskExecution = new TaskExecutionCoordinator(taskRepository, eventBroker);
        this.contextAssembler = new TuningContextAssembler(conversationRepository);
        this.taskCreator = new TuningTaskCreator(taskRepository, conversationRepository, statementParser,
                inputImageValidator, inputImageRepository, contextAssembler, taskExecution);
        this.modelStages = new TuningModelStageExecutor(taskRepository, llmClient, objectMapper,
                inputImageRepository, taskExecution);
        this.resultFinalizer = new TuningResultFinalizer(conversationRepository, objectMapper, promptCompiler,
                resultValidator, modelStages, contextAssembler, taskExecution);
    }

    public SqlTuningTask createTask(Long userId, CreateTuningTaskRequest request) {
        return createTask(userId, request, null);
    }

    @Transactional
    public SqlTuningTask createTask(Long userId, CreateTuningTaskRequest request, String idempotencyKey) {
        return taskCreator.create(userId, request, idempotencyKey);
    }

    public void run(SqlTuningTask task) {
        log.info("runHarness param 入参: taskId: {}, deepAnalysis: {}", task.getId(), task.isDeepAnalysis());
        modelStages.startTaskBudget(task.isDeepAnalysis());
        try {
            contextAssembler.validateInputLimits(task);
            SqlDialect dialect = SqlDialect.from(task.getDbDialect());
            SqlStatementProfile profile = statementParser.parse(task.getOriginalSql(), dialect);
            if (!profile.isValid()) {
                throw new IllegalArgumentException(profile.getReason());
            }

            SanitizedSql sanitized = sanitizer.sanitize(task.getOriginalSql(), dialect);
            task.setSanitizedSql(sanitized.getSanitizedSql());
            task.setSqlHash(sanitized.getSqlHash());
            taskExecution.transition(task, TaskStatus.SANITIZED, "SQL 已脱敏");
            taskExecution.addArtifact(task, "sanitize", "完成 SQL 脱敏", sanitized);

            List<RuleFinding> findings = ruleEngine.inspect(task.getSanitizedSql(), task.getIndexText(), task.getExplainText(), task.getDbDialect());
            task.setRuleFindings(findings);
            taskExecution.transition(task, TaskStatus.RULE_CHECKED, "规则扫描完成，命中 " + findings.size() + " 条");
            taskExecution.addArtifact(task, "ruleCheck", "确定性规则扫描完成", findings);

            modelStages.maybeExtractPlanImages(task);

            ContextPackage context = contextAssessor.assess(task, profile, findings);
            taskExecution.transition(task, TaskStatus.CONTEXT_CHECKED, "上下文证据门禁完成: " + context.getAssessment().getCompleteness());
            taskExecution.addArtifact(task, "contextAssess", "上下文完整度和证据目录", context.getAssessment());

            SkillVersion skill = skillRepository.activeDefault();
            task.setSkillId(skill.getId());
            task.setSkillName(skill.getName());
            task.setSkillVersion(skill.getVersion());
            taskExecution.addArtifact(task, "skillSelect", "绑定技能版本，保证任务可追溯", skillSummary(skill));

            String systemPrompt = promptCompiler.systemPrompt(skill, dialect);
            String userPrompt = promptCompiler.analysisPrompt(task, dialect, profile, context, findings,
                    contextAssembler.recentConversationContext(task));
            taskExecution.transition(task, TaskStatus.LLM_ANALYZING, "正在调用模型分析");
            taskExecution.addArtifact(task, "promptBuild", "构建模型输入", promptSummary(userPrompt, skill));
            LlmResponse response = modelStages.analyzeStageWithStream(task, "ANALYZE", new LlmRequest(systemPrompt, userPrompt, false));
            taskExecution.addArtifact(task, "llmAnalyze", "模型分析完成", response);

            taskExecution.transition(task, TaskStatus.VERIFYING, "正在校验模型结构化输出");
            TuningResult result = resultFinalizer.parseValidateAndRepairOnce(task, response, context, profile, dialect, systemPrompt);

            if (task.isDeepAnalysis()) {
                taskExecution.transition(task, TaskStatus.REVIEWING, "正在复核模型建议");
                LlmResponse reviewResponse = modelStages.analyzeStageWithStream(task, "DEEP_REVIEW",
                        new LlmRequest(systemPrompt, promptCompiler.reviewPrompt(result), true));
                taskExecution.addArtifact(task, "llmReview", "深度分析复核完成", reviewResponse);
                TuningResult reviewed = resultFinalizer.parseValidateReviewAndRepairOnce(
                        task, result, reviewResponse, context, profile, dialect, systemPrompt);
                String reviewVerdict = reviewed.getReview().getVerdict().trim().toUpperCase(Locale.ROOT);
                if ("REJECT".equals(reviewVerdict) && !"NEEDS_INPUT".equals(reviewed.getOutcome())) {
                    throw new IllegalStateException("深度复核 REJECT 必须返回 outcome=NEEDS_INPUT");
                }
                reviewed.getReview().setVerdict(reviewVerdict);
                result = reviewed;
            }

            resultFinalizer.complete(task, result, context, findings);
        } finally {
            modelStages.clearTaskBudget();
        }
    }

    private Object skillSummary(SkillVersion skill) {
        return "skillName=" + skill.getName() + ", version=" + skill.getVersion() + ", contentLength=" + skill.getContent().length();
    }

    private Object promptSummary(String prompt, SkillVersion skill) {
        return "skillName=" + skill.getName() + ", version=" + skill.getVersion() + ", promptLength=" + prompt.length();
    }
}
