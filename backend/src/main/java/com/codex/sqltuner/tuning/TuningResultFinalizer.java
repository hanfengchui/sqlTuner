package com.codex.sqltuner.tuning;

import com.codex.sqltuner.conversation.ConversationRepository;
import com.codex.sqltuner.conversation.MessageRole;
import com.codex.sqltuner.llm.LlmRequest;
import com.codex.sqltuner.llm.LlmResponse;
import com.codex.sqltuner.rule.RuleFinding;
import com.codex.sqltuner.rule.SqlDialect;
import com.codex.sqltuner.tuning.accuracy.ContextPackage;
import com.codex.sqltuner.tuning.accuracy.PromptCompiler;
import com.codex.sqltuner.tuning.accuracy.SqlStatementProfile;
import com.codex.sqltuner.tuning.accuracy.StrictResultValidator;
import com.codex.sqltuner.tuning.accuracy.ValidationOutcome;
import com.codex.sqltuner.tuning.result.Diagnosis;
import com.codex.sqltuner.tuning.result.IndexCandidate;
import com.codex.sqltuner.tuning.result.ReviewResult;
import com.codex.sqltuner.tuning.result.ValidationStep;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validates model output, performs the bounded repair/review flow, and closes a task.
 */
final class TuningResultFinalizer {
    private static final Logger log = LoggerFactory.getLogger(TuningResultFinalizer.class);
    private static final Set<String> STRICT_RESULT_FIELDS =
            Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
                    "outcome", "summary", "analysisNarrative", "contextAssessment", "evidenceCatalog",
                    "diagnoses", "rewriteCandidates", "indexCandidates", "validationPlan",
                    "missingInformation", "safetyWarnings", "review")));

    private final ConversationRepository conversationRepository;
    private final ObjectMapper objectMapper;
    private final PromptCompiler promptCompiler;
    private final StrictResultValidator resultValidator;
    private final TuningModelStageExecutor modelStages;
    private final TuningContextAssembler contextAssembler;
    private final TaskExecutionCoordinator taskExecution;

    TuningResultFinalizer(ConversationRepository conversationRepository,
                          ObjectMapper objectMapper,
                          PromptCompiler promptCompiler,
                          StrictResultValidator resultValidator,
                          TuningModelStageExecutor modelStages,
                          TuningContextAssembler contextAssembler,
                          TaskExecutionCoordinator taskExecution) {
        this.conversationRepository = conversationRepository;
        this.objectMapper = objectMapper;
        this.promptCompiler = promptCompiler;
        this.resultValidator = resultValidator;
        this.modelStages = modelStages;
        this.contextAssembler = contextAssembler;
        this.taskExecution = taskExecution;
    }

    TuningResult parseValidateAndRepairOnce(SqlTuningTask task,
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

        taskExecution.addArtifact(task, "resultValidateFailed", "模型输出未通过严格校验，执行一次修复", validationErrors);
        LlmResponse repaired = modelStages.analyzeStageWithStream(task, "ANALYZE_REPAIR", new LlmRequest(
                systemPrompt,
                promptCompiler.repairPrompt(response.getContent(), validationErrors),
                false));
        taskExecution.addArtifact(task, "llmRepair", "模型 JSON 修复调用完成", repaired);

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

    TuningResult parseValidateReviewAndRepairOnce(SqlTuningTask task,
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
            if (!TuningText.hasText(errors)) {
                return reviewed;
            }
        } catch (ModelOutputFormatException e) {
            errors = e.getMessage();
        }

        taskExecution.addArtifact(task, "reviewValidateFailed", "深度复核输出未通过严格校验，执行一次修复", errors);
        LlmResponse repaired = modelStages.analyzeStageWithStream(task, "DEEP_REVIEW_REPAIR", new LlmRequest(
                systemPrompt,
                promptCompiler.reviewRepairPrompt(response.getContent(), errors),
                true));
        taskExecution.addArtifact(task, "llmReviewRepair", "深度复核 JSON 修复调用完成", repaired);
        final TuningResult reviewed;
        try {
            reviewed = parseReviewResponse(repaired, original, context);
        } catch (ModelOutputFormatException e) {
            throw new IllegalStateException("深度复核修复输出不是合法 JSON", e);
        }
        String repairedErrors = reviewErrors(reviewed, context, profile, dialect);
        if (TuningText.hasText(repairedErrors)) {
            throw new IllegalStateException("深度复核修复输出未通过严格校验: " + repairedErrors);
        }
        return reviewed;
    }

    void complete(SqlTuningTask task,
                  TuningResult result,
                  ContextPackage context,
                  List<RuleFinding> findings) {
        applyContextDefaults(result, context);
        applyAllowedActions(result, task);
        appendRuleFindings(result, findings);
        applyLegacyMapping(result);
        task.setResult(result);
        task.setStatus(TaskStatus.DONE);
        task.setStatusMessage("调优建议已生成");
        String expectedLeaseOwner = task.getLeaseOwner();
        int expectedAttemptCount = task.getAttemptCount();
        task.setLeaseOwner(null);
        task.setLeaseUntil(null);
        taskExecution.addArtifact(task, "resultAssemble", "结构化结果组装完成", result.getSummary());
        conversationRepository.addMessage(task.getConversationId(), MessageRole.ASSISTANT, result.getSummary(), task.getId());
        conversationRepository.updateContextSnapshot(task.getConversationId(), contextAssembler.buildConversationContextSnapshot(task));
        taskExecution.persistWorkerTask(task, expectedLeaseOwner, expectedAttemptCount);
        taskExecution.publish(task);
        log.info("runHarness result 结果: taskId: {}, status: {}, findings: {}, mockModel: {}",
                task.getId(), task.getStatus(), result.getFindings().size(), result.isMockModel());
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
        if (reviewed.getReview() == null || !TuningText.hasText(reviewed.getReview().getVerdict())) {
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
        Iterator<String> fieldNames = root.fieldNames();
        while (fieldNames.hasNext()) {
            if (!STRICT_RESULT_FIELDS.contains(fieldNames.next())) {
                throw new IllegalArgumentException("模型结果包含不支持的顶层字段");
            }
        }
        requireText(root, "outcome", "root");
        requireText(root, "summary", "root");
        JsonNode narrative = requireObject(root, "analysisNarrative", "root");
        requireText(narrative, "conclusion", "analysisNarrative");
        JsonNode narrativeSections = requireArray(narrative, "sections", "analysisNarrative");
        for (int i = 0; i < narrativeSections.size(); i++) {
            JsonNode section = requireArrayObject(narrativeSections, i, "analysisNarrative.sections");
            String path = "analysisNarrative.sections[" + i + "]";
            requireText(section, "kind", path);
            requireText(section, "title", path);
            requireText(section, "body", path);
            requireStringArray(section, "evidenceRefs", path);
        }
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

    private void applyContextDefaults(TuningResult result, ContextPackage context) {
        if (!TuningText.hasText(result.getOutcome())) {
            result.setOutcome(context.isAllowRewrite() || context.isAllowIndexDirection() ? "ADVICE" : "NEEDS_INPUT");
        }
        if (!TuningText.hasText(result.getSummary())) {
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
        Set<String> allowed = new HashSet<String>();
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
        if (!TuningText.hasText(result.getRewriteSql()) && !result.getRewriteCandidates().isEmpty()) {
            result.setRewriteSql(result.getRewriteCandidates().get(0).getSql());
        }
        if (result.getIndexSuggestions().isEmpty()) {
            for (IndexCandidate candidate : result.getIndexCandidates()) {
                IndexSuggestion suggestion = new IndexSuggestion();
                suggestion.setIndexName(TuningText.hasText(candidate.getDdl()) ? candidate.getDdl() : "候选索引: " + candidate.getTableName());
                suggestion.setColumns(candidate.getColumnOrder());
                suggestion.setBenefit(candidate.getBenefit());
                suggestion.setRisk(TuningText.firstNonEmpty(candidate.getRisk(), candidate.getWriteCost()));
                suggestion.setValidation(candidate.getValidation());
                result.getIndexSuggestions().add(suggestion);
            }
        }
        if (result.getValidationSteps().isEmpty()) {
            for (ValidationStep step : result.getValidationPlan()) {
                result.getValidationSteps().add(step.getAction() + (TuningText.hasText(step.getExpectedSignal()) ? "；观察: " + step.getExpectedSignal() : ""));
            }
        }
        if (result.getRiskWarnings().isEmpty()) {
            result.getRiskWarnings().addAll(result.getSafetyWarnings());
        }
        if (result.getNeedMoreInfo().isEmpty()) {
            result.getNeedMoreInfo().addAll(result.getMissingInformation());
        }
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
}
