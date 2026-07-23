package com.codex.sqltuner.tuning;

import com.codex.sqltuner.conversation.ConversationRepository;
import com.codex.sqltuner.conversation.Message;
import com.codex.sqltuner.conversation.MessageRole;
import com.codex.sqltuner.tuning.result.Diagnosis;
import com.codex.sqltuner.tuning.result.IndexCandidate;
import com.codex.sqltuner.tuning.result.ValidationStep;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds bounded conversation context from stored facts and validated results.
 */
final class TuningContextAssembler {
    static final int CONTEXT_SNAPSHOT_MAX_CHARS = 8 * 1024;
    static final int RECENT_CONTEXT_MAX_CHARS = 4 * 1024;
    static final int CURRENT_SUPPLEMENT_MAX_CHARS = 4 * 1024;

    private final ConversationRepository conversationRepository;

    TuningContextAssembler(ConversationRepository conversationRepository) {
        this.conversationRepository = conversationRepository;
    }

    void validateInputLimits(SqlTuningTask task) {
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

    void validateRequestLimits(CreateTuningTaskRequest request) {
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
    }

    String recentConversationContext(SqlTuningTask task) {
        // 只取当前输入前的两轮对话，不能随着会话增长反复读取、拼接完整历史。
        List<Message> messages = conversationRepository.listMessagesBefore(task.getConversationId(), null, 5);
        if (messages.isEmpty()) {
            return "未提供";
        }
        StringBuilder builder = new StringBuilder();
        int start = Math.max(0, messages.size() - 5);
        for (int i = start; i < messages.size(); i++) {
            Message message = messages.get(i);
            // 当前任务的用户输入已在主输入区提供，避免重复放大 prompt。
            if (task.getId() != null && task.getId().equals(message.getTaskId()) && message.getRole() == MessageRole.USER) {
                continue;
            }
            builder.append(message.getRole() == MessageRole.USER ? "用户: " : "助手: ")
                    .append(TuningText.abbreviate(message.getContent(), 1000))
                    .append("\n");
        }
        return builder.length() == 0 ? "未提供" : TuningText.abbreviateUtf8(builder.toString(), RECENT_CONTEXT_MAX_CHARS);
    }

    /**
     * Only deterministic facts and validated results enter the persisted snapshot.
     */
    String buildConversationContextSnapshot(SqlTuningTask task) {
        if (task == null) {
            return "";
        }
        StringBuilder snapshot = new StringBuilder();
        snapshot.append("最新事实:\n");
        appendSnapshotLine(snapshot, "方言", TuningText.firstNonEmpty(task.getDbDialect(), "未提供"));
        if (TuningText.hasText(task.getObVersion())) {
            appendSnapshotLine(snapshot, "OceanBase 版本", TuningText.abbreviateUtf8(task.getObVersion(), 512));
        }
        appendSnapshotLine(snapshot, "已提供证据", providedEvidenceSummary(task));
        appendSnapshotLineIfPresent(snapshot, "运行指标", task.getRuntimeMetricsText(), 1536);
        appendSnapshotLineIfPresent(snapshot, "表统计", task.getTableStatsText(), 1536);
        appendSnapshotLineIfPresent(snapshot, "计划截图事实", task.getPlanImageFacts(), 1536);

        TuningResult result = task.getResult();
        if (result != null) {
            snapshot.append("\n最新结论:\n");
            appendSnapshotLine(snapshot, "结果", TuningText.firstNonEmpty(result.getOutcome(), "未提供"));
            appendSnapshotLineIfPresent(snapshot, "摘要", result.getSummary(), 1536);
            if (result.getDiagnoses() != null) {
                for (Diagnosis diagnosis : result.getDiagnoses()) {
                    if (diagnosis == null || !TuningText.hasText(diagnosis.getTitle())) {
                        continue;
                    }
                    String conclusion = TuningText.firstNonEmpty(diagnosis.getImpact(), diagnosis.getPrecondition());
                    appendSnapshotLine(snapshot,
                            "诊断" + (TuningText.hasText(diagnosis.getSeverity()) ? "[" + diagnosis.getSeverity() + "]" : ""),
                            diagnosis.getTitle() + (TuningText.hasText(conclusion) ? ": " + conclusion : ""),
                            768);
                }
            }
            if (result.getIndexCandidates() != null) {
                for (IndexCandidate candidate : result.getIndexCandidates()) {
                    if (candidate == null || !TuningText.hasText(candidate.getTableName())) {
                        continue;
                    }
                    appendSnapshotLine(snapshot, "索引方向",
                            candidate.getTableName() + "(" + candidate.getColumnOrder() + ")"
                                    + (TuningText.hasText(candidate.getBenefit()) ? ": " + candidate.getBenefit() : ""),
                            768);
                }
            }

            snapshot.append("\n待验证或补充:\n");
            if (result.getValidationPlan() != null) {
                for (ValidationStep step : result.getValidationPlan()) {
                    if (step != null && TuningText.hasText(step.getAction())) {
                        appendSnapshotLine(snapshot, "验证", step.getAction()
                                + (TuningText.hasText(step.getExpectedSignal()) ? ": " + step.getExpectedSignal() : ""), 768);
                    }
                }
            }
            appendSnapshotValues(snapshot, "待补充", result.getMissingInformation(), 768);
            if (result.getContextAssessment() != null) {
                appendSnapshotValues(snapshot, "待补充", result.getContextAssessment().getMissingInformation(), 768);
            }
        }

        if (TuningText.hasText(task.getBusinessInvariants())) {
            snapshot.append("\n业务约束:\n");
            appendSnapshotLine(snapshot, "约束", task.getBusinessInvariants(), 1536);
        }
        return TuningText.abbreviateUtf8(snapshot.toString(), CONTEXT_SNAPSHOT_MAX_CHARS);
    }

    Object safeContextSummary(SqlTuningTask task) {
        return "dbDialect=" + task.getDbDialect()
                + ", sqlLength=" + (task.getOriginalSql() == null ? 0 : task.getOriginalSql().length())
                + ", hasExplain=" + TuningText.hasText(task.getExplainText())
                + ", hasSchema=" + TuningText.hasText(task.getSchemaText())
                + ", hasIndex=" + TuningText.hasText(task.getIndexText());
    }

    private void validateLength(String field, String value, int max) {
        if (value != null && value.length() > max) {
            throw new IllegalArgumentException(field + " 超过限制 " + max + " 字符");
        }
    }

    private String providedEvidenceSummary(SqlTuningTask task) {
        List<String> provided = new ArrayList<String>();
        if (TuningText.hasText(task.getSchemaText())) {
            provided.add("表结构");
        }
        if (TuningText.hasText(task.getIndexText())) {
            provided.add("当前索引");
        }
        if (TuningText.hasText(task.getExplainText())) {
            provided.add("文本 EXPLAIN");
        }
        if (TuningText.hasText(task.getTableStatsText())) {
            provided.add("表统计");
        }
        if (TuningText.hasText(task.getRuntimeMetricsText())) {
            provided.add("运行指标");
        }
        if (TuningText.hasText(task.getPlanImageFacts())) {
            provided.add("计划截图");
        }
        return provided.isEmpty() ? "仅 SQL" : TuningText.joinNonEmpty(provided.toArray(new String[provided.size()])).replace("\n\n", "、");
    }

    private void appendSnapshotValues(StringBuilder snapshot, String label, List<String> values, int maxBytes) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            appendSnapshotLine(snapshot, label, value, maxBytes);
        }
    }

    private void appendSnapshotLineIfPresent(StringBuilder snapshot, String label, String value, int maxBytes) {
        if (TuningText.hasText(value)) {
            appendSnapshotLine(snapshot, label, value, maxBytes);
        }
    }

    private void appendSnapshotLine(StringBuilder snapshot, String label, String value) {
        appendSnapshotLine(snapshot, label, value, 768);
    }

    private void appendSnapshotLine(StringBuilder snapshot, String label, String value, int maxBytes) {
        if (!TuningText.hasText(value)) {
            return;
        }
        snapshot.append("- ").append(label).append(": ")
                .append(TuningText.abbreviateUtf8(value, maxBytes)).append("\n");
    }
}
