package com.codex.sqltuner.tuning;

import com.codex.sqltuner.conversation.Conversation;
import com.codex.sqltuner.conversation.ConversationRepository;
import com.codex.sqltuner.conversation.Message;
import com.codex.sqltuner.conversation.MessageRole;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

@Service
public class TuningHarnessService {
    private static final Logger log = LoggerFactory.getLogger(TuningHarnessService.class);
    private final TuningTaskRepository taskRepository;
    private final ConversationRepository conversationRepository;
    private final SqlSanitizer sanitizer;
    private final RuleEngine ruleEngine;
    private final SkillRepository skillRepository;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public TuningHarnessService(TuningTaskRepository taskRepository,
                                ConversationRepository conversationRepository,
                                SqlSanitizer sanitizer,
                                RuleEngine ruleEngine,
                                SkillRepository skillRepository,
                                LlmClient llmClient,
                                ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.conversationRepository = conversationRepository;
        this.sanitizer = sanitizer;
        this.ruleEngine = ruleEngine;
        this.skillRepository = skillRepository;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    public SqlTuningTask createTask(Long userId, CreateTuningTaskRequest request) {
        Long conversationId = request.getConversationId();
        if (conversationId == null) {
            Conversation conversation = conversationRepository.create(userId, "新建调优");
            conversationId = conversation.getId();
        } else {
            conversationRepository.getForUser(conversationId, userId);
        }

        SqlTuningTask task = new SqlTuningTask();
        SqlDialect dialect = SqlDialect.from(request.getDbDialect());
        task.setUserId(userId);
        task.setConversationId(conversationId);
        task.setDbDialect(dialect.getDisplayName());
        task.setInputType(resolveInputType(request.getInputType(), request.getSqlText()));
        task.setOriginalSql(request.getSqlText());
        task.setSchemaText(request.getSchemaText());
        task.setIndexText(request.getIndexText());
        task.setExplainText(request.getExplainText());
        task.setBusinessContext(request.getBusinessContext());
        task.setDeepAnalysis(Boolean.TRUE.equals(request.getDeepAnalysis()));
        task.setStatus(TaskStatus.RECEIVED);
        task.setStatusMessage("已收到调优请求");
        taskRepository.create(task);

        conversationRepository.addMessage(conversationId, MessageRole.USER, request.getSqlText(), task.getId());
        addArtifact(task, "received", "接收用户输入和上下文", safeContextSummary(task));
        taskRepository.update(task);
        log.info("createTask result 结果: taskId: {}, userId: {}, conversationId: {}, dbDialect: {}, inputType: {}, inputLength: {}",
                task.getId(), userId, conversationId, task.getDbDialect(), task.getInputType(), request.getSqlText().length());
        return task;
    }

    @Async("tuningTaskExecutor")
    public void runAsync(Long taskId, Long userId) {
        SqlTuningTask task = taskRepository.getForUser(taskId, userId);
        try {
            run(task);
        } catch (Exception e) {
            task.setStatus(TaskStatus.FAILED);
            task.setStatusMessage("调优失败: " + e.getMessage());
            addArtifact(task, "failed", "Harness 执行失败", e.getMessage());
            taskRepository.update(task);
            log.error("runAsync taskId: {} error 异常: {}", taskId, e.getMessage(), e);
        }
    }

    public void run(SqlTuningTask task) {
        log.info("runHarness param 入参: taskId: {}, deepAnalysis: {}", task.getId(), task.isDeepAnalysis());

        SanitizedSql sanitized = sanitizer.sanitize(task.getOriginalSql());
        task.setSanitizedSql(sanitized.getSanitizedSql());
        task.setSqlHash(sanitized.getSqlHash());
        transition(task, TaskStatus.SANITIZED, "SQL 已脱敏");
        addArtifact(task, "sanitize", "完成 SQL 脱敏", sanitized);

        List<RuleFinding> findings = ruleEngine.inspect(task.getSanitizedSql(), task.getIndexText(), task.getExplainText(), task.getDbDialect());
        task.setRuleFindings(findings);
        transition(task, TaskStatus.RULE_CHECKED, "规则扫描完成，命中 " + findings.size() + " 条");
        addArtifact(task, "ruleCheck", "确定性规则扫描完成", findings);

        SkillVersion skill = skillRepository.activeDefault();
        task.setSkillId(skill.getId());
        task.setSkillName(skill.getName());
        task.setSkillVersion(skill.getVersion());
        addArtifact(task, "skillSelect", "绑定技能版本，保证任务可追溯", skillSummary(skill));

        String userPrompt = buildPrompt(task, findings);
        transition(task, TaskStatus.LLM_ANALYZING, "正在调用模型分析");
        addArtifact(task, "promptBuild", "构建模型输入", promptSummary(userPrompt, skill));
        LlmResponse response = llmClient.analyze(new LlmRequest(skill.getContent(), userPrompt, task.isDeepAnalysis()));
        addArtifact(task, "llmAnalyze", "模型分析完成", response);

        if (task.isDeepAnalysis()) {
            transition(task, TaskStatus.REVIEWING, "正在复核模型建议");
            // 第一版复核保留为独立节点，后续可替换成二次 LLM 调用或人工审核。
            addArtifact(task, "llmReview", "深度分析复核完成", "已检查输出字段完整性和风险提示。");
        }

        TuningResult result = parseResult(response);
        appendRuleFindings(result, findings);
        task.setResult(result);
        transition(task, TaskStatus.DONE, "调优建议已生成");
        addArtifact(task, "resultAssemble", "结构化结果组装完成", result.getSummary());
        conversationRepository.addMessage(task.getConversationId(), MessageRole.ASSISTANT, result.getSummary(), task.getId());
        taskRepository.update(task);
        log.info("runHarness result 结果: taskId: {}, status: {}, findings: {}, mockModel: {}",
                task.getId(), task.getStatus(), result.getFindings().size(), result.isMockModel());
    }

    private void transition(SqlTuningTask task, TaskStatus status, String message) {
        task.setStatus(status);
        task.setStatusMessage(message);
        taskRepository.update(task);
        log.info("transitionTask result 结果: taskId: {}, status: {}, message: {}", task.getId(), status, message);
    }

    private void addArtifact(SqlTuningTask task, String nodeName, String summary, Object payload) {
        task.getArtifacts().add(new HarnessArtifact(nodeName, summary, payload, LocalDateTime.now()));
    }

    private String buildPrompt(SqlTuningTask task, List<RuleFinding> findings) {
        SqlDialect dialect = SqlDialect.from(task.getDbDialect());
        StringBuilder builder = new StringBuilder();
        builder.append("请分析以下 ").append(dialect.getDisplayName()).append(" 兼容数据库调优请求，并输出严格 JSON。\n");
        builder.append("数据库方言: ").append(dialect.getDisplayName()).append("\n");
        builder.append("方言要求: ").append(dialectInstruction(dialect)).append("\n");
        builder.append("输入类型: ").append(task.getInputType()).append("\n");
        if ("natural_language".equals(task.getInputType())) {
            builder.append("用户自然语言问题/描述:\n").append(task.getSanitizedSql()).append("\n\n");
        } else {
            builder.append("脱敏 SQL:\n").append(task.getSanitizedSql()).append("\n\n");
        }
        builder.append("表结构:\n").append(emptyToPlaceholder(task.getSchemaText())).append("\n\n");
        builder.append("索引信息:\n").append(emptyToPlaceholder(task.getIndexText())).append("\n\n");
        builder.append("执行计划:\n").append(emptyToPlaceholder(task.getExplainText())).append("\n\n");
        builder.append("业务说明:\n").append(emptyToPlaceholder(task.getBusinessContext())).append("\n\n");
        builder.append("最近会话上下文摘要:\n").append(recentConversationContext(task)).append("\n\n");
        builder.append("规则扫描结果:\n");
        for (RuleFinding finding : findings) {
            builder.append("- [").append(finding.getSeverity()).append("] ")
                    .append(finding.getCode()).append(": ")
                    .append(finding.getTitle()).append("。证据: ")
                    .append(finding.getEvidence()).append("。建议: ")
                    .append(finding.getSuggestion()).append("\n");
        }
        builder.append("\n输出 JSON 字段: summary, findings, rewriteSql, indexSuggestions, validationSteps, riskWarnings, needMoreInfo。");
        builder.append("\n如果输入是自然语言且缺少 SQL、表结构或执行计划，直接说明缺失信息，不要编造表结构、行数、索引或耗时。");
        builder.append("\n如果最近会话上下文与本轮输入冲突，以本轮输入和用户最新补充为准。");
        return builder.toString();
    }

    private String dialectInstruction(SqlDialect dialect) {
        if (dialect == SqlDialect.OB_ORACLE) {
            return "按 OceanBase Oracle 兼容模式分析；改写 SQL 避免 MySQL 专属 LIMIT、DATE_FORMAT、反引号语法，分页优先使用 ROWNUM 或 FETCH FIRST，索引建议需考虑 Oracle 兼容函数索引/组合索引。";
        }
        return "按 OceanBase MySQL 兼容模式分析；改写 SQL 可使用 LIMIT、范围条件、生成列或函数索引，但必须说明版本和验证方式。";
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

    private TuningResult parseResult(LlmResponse response) {
        TuningResult result = new TuningResult();
        result.setRawModelOutput(response.getContent());
        result.setMockModel(response.isMock());
        try {
            JsonNode root = objectMapper.readTree(response.getContent());
            result.setSummary(root.path("summary").asText("模型已返回分析结果"));
            result.setRewriteSql(root.path("rewriteSql").asText(""));
            result.setFindings(parseFindings(root.path("findings")));
            result.setIndexSuggestions(parseIndexSuggestions(root.path("indexSuggestions")));
            result.setValidationSteps(parseStringList(root.path("validationSteps")));
            result.setRiskWarnings(parseStringList(root.path("riskWarnings")));
            result.setNeedMoreInfo(parseStringList(root.path("needMoreInfo")));
        } catch (Exception e) {
            log.warn("parseResult result 结果: 模型输出不是严格 JSON, reason: {}", e.getMessage());
            result.setSummary("模型返回了非结构化内容，请查看原始输出并补充上下文后重试。");
            result.getRiskWarnings().add("模型输出不是严格 JSON，已保留原始输出。");
        }
        return result;
    }

    private List<ResultFinding> parseFindings(JsonNode node) {
        List<ResultFinding> list = new ArrayList<ResultFinding>();
        if (node != null && node.isArray()) {
            Iterator<JsonNode> iterator = node.elements();
            while (iterator.hasNext()) {
                JsonNode item = iterator.next();
                ResultFinding finding = new ResultFinding();
                if (item.isTextual()) {
                    finding.setTitle(item.asText());
                    finding.setEvidence("模型分析");
                    finding.setImpact("");
                    finding.setConfidence("model");
                } else {
                    finding.setTitle(firstText(item, "title", "name", "problem"));
                    finding.setEvidence(firstText(item, "evidence", "reason", "source"));
                    finding.setImpact(firstText(item, "impact", "suggestion", "benefit"));
                    finding.setConfidence(firstText(item, "confidence", "level", "severity"));
                }
                list.add(finding);
            }
        }
        return list;
    }

    private List<IndexSuggestion> parseIndexSuggestions(JsonNode node) {
        List<IndexSuggestion> list = new ArrayList<IndexSuggestion>();
        if (node != null && node.isArray()) {
            Iterator<JsonNode> iterator = node.elements();
            while (iterator.hasNext()) {
                JsonNode item = iterator.next();
                IndexSuggestion suggestion = new IndexSuggestion();
                if (item.isTextual()) {
                    suggestion.setIndexName("候选索引");
                    suggestion.setBenefit(item.asText());
                } else {
                    suggestion.setIndexName(firstText(item, "indexName", "name", "index", "definition"));
                    suggestion.setBenefit(firstText(item, "benefit", "reason"));
                    suggestion.setRisk(firstText(item, "risk", "writeCost", "cost"));
                    suggestion.setValidation(firstText(item, "validation", "validationMethod", "verify"));
                    suggestion.setColumns(parseStringList(item.path("columns")));
                }
                list.add(suggestion);
            }
        }
        return list;
    }

    private List<String> parseStringList(JsonNode node) {
        List<String> list = new ArrayList<String>();
        if (node != null && node.isArray()) {
            Iterator<JsonNode> iterator = node.elements();
            while (iterator.hasNext()) {
                list.add(iterator.next().asText());
            }
        }
        return list;
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

    private String emptyToPlaceholder(String value) {
        return hasText(value) ? value : "未提供";
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
