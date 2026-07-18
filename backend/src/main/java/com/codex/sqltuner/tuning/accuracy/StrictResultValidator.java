package com.codex.sqltuner.tuning.accuracy;

import com.codex.sqltuner.rule.SqlDialect;
import com.codex.sqltuner.tuning.TuningResult;
import com.codex.sqltuner.tuning.result.Diagnosis;
import com.codex.sqltuner.tuning.result.EvidenceItem;
import com.codex.sqltuner.tuning.result.IndexCandidate;
import com.codex.sqltuner.tuning.result.AnalysisNarrative;
import com.codex.sqltuner.tuning.result.NarrativeSection;
import com.codex.sqltuner.tuning.result.RewriteCandidate;
import com.codex.sqltuner.tuning.result.ValidationStep;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

@Component
public class StrictResultValidator {
    private final SqlStatementParser parser;

    public StrictResultValidator(SqlStatementParser parser) {
        this.parser = parser;
    }

    public ValidationOutcome validate(TuningResult result, ContextPackage context, SqlStatementProfile originalProfile, SqlDialect dialect) {
        ValidationOutcome outcome = new ValidationOutcome();
        if (result == null) {
            outcome.reject("模型输出为空");
            return outcome;
        }
        if (!"ADVICE".equals(result.getOutcome()) && !"NEEDS_INPUT".equals(result.getOutcome())) {
            outcome.reject("outcome 必须为 ADVICE 或 NEEDS_INPUT");
        }
        if (result.getContextAssessment() == null) {
            outcome.reject("缺少 contextAssessment");
        }
        if (result.getEvidenceCatalog() == null || result.getEvidenceCatalog().isEmpty()) {
            outcome.reject("缺少 evidenceCatalog");
        }
        Set<String> evidenceIds = evidenceIds(result);
        validateNarrative(result.getAnalysisNarrative(), evidenceIds, outcome);
        validateDiagnoses(result, evidenceIds, outcome);
        validateRewriteCandidates(result, context, originalProfile, dialect, evidenceIds, outcome);
        validateIndexCandidates(result, context, evidenceIds, outcome);
        validateValidationPlan(result, evidenceIds, outcome);
        if (!context.isAllowHighConfidence()) {
            downgradeHighConfidence(result);
        }
        if (!context.isAllowRewrite() && !result.getRewriteCandidates().isEmpty()) {
            outcome.reject("仅 SQL 场景禁止输出 rewriteCandidates");
        }
        if (!context.isAllowIndexDirection() && !result.getIndexCandidates().isEmpty()) {
            outcome.reject("缺少 schema/index 时禁止输出 indexCandidates");
        }
        if (!context.isAllowIndexDdl()) {
            for (IndexCandidate candidate : result.getIndexCandidates()) {
                candidate.setDdl("");
            }
        }
        if ("NEEDS_INPUT".equals(result.getOutcome()) && result.getMissingInformation().isEmpty()) {
            outcome.reject("NEEDS_INPUT 必须给出 missingInformation");
        }
        return outcome;
    }

    private void validateNarrative(AnalysisNarrative narrative,
                                   Set<String> evidenceIds,
                                   ValidationOutcome outcome) {
        if (narrative == null || !hasText(narrative.getConclusion())) {
            outcome.reject("缺少 analysisNarrative.conclusion");
            return;
        }
        if (narrative.getConclusion().length() > 1200) {
            outcome.reject("analysisNarrative.conclusion 超过长度限制");
        }
        validateNarrativeText("analysisNarrative.conclusion", narrative.getConclusion(), outcome);
        if (narrative.getSections().isEmpty() || narrative.getSections().size() > 5) {
            outcome.reject("analysisNarrative.sections 必须包含 1 至 5 个段落");
        }
        for (NarrativeSection section : narrative.getSections()) {
            if (!hasText(section.getKind()) || !hasText(section.getTitle()) || !hasText(section.getBody())) {
                outcome.reject("analysisNarrative.sections 缺少 kind/title/body");
                continue;
            }
            if (!isNarrativeKind(section.getKind())) {
                outcome.reject("analysisNarrative.sections 包含不支持的 kind: " + section.getKind());
            }
            if (section.getBody().length() > 1800) {
                outcome.reject("analysisNarrative.sections.body 超过长度限制");
            }
            if (section.getTitle().length() > 180) {
                outcome.reject("analysisNarrative.sections.title 超过长度限制");
            }
            validateNarrativeText("analysisNarrative.sections", section.getTitle() + "\n" + section.getBody(), outcome);
            validateRefs("analysisNarrative.sections", section.getEvidenceRefs(), evidenceIds, outcome);
        }
    }

    private void validateNarrativeText(String field, String value, ValidationOutcome outcome) {
        if (containsExecutableDdl(value)) {
            outcome.reject(field + " 不得直接包含 DDL");
        }
        if (containsExecutableSql(value)) {
            outcome.reject(field + " 不得直接包含完整 SQL");
        }
    }

    private boolean isNarrativeKind(String kind) {
        String value = kind.trim().toUpperCase(Locale.ROOT);
        return "CONCLUSION".equals(value) || "EVIDENCE".equals(value) || "CAUTION".equals(value)
                || "ACTION".equals(value) || "VALIDATION".equals(value);
    }

    private boolean containsExecutableDdl(String value) {
        return value.toLowerCase(Locale.ROOT).matches("(?s).*\\b(create\\s+(unique\\s+)?index|alter\\s+table|drop\\s+index)\\b.*");
    }

    private boolean containsExecutableSql(String value) {
        return value.toLowerCase(Locale.ROOT).matches("(?s).*\\b(select\\s+.+?\\s+from|update\\s+.+?\\s+set|insert\\s+into|delete\\s+from)\\b.*");
    }

    private void validateDiagnoses(TuningResult result, Set<String> evidenceIds, ValidationOutcome outcome) {
        for (Diagnosis diagnosis : result.getDiagnoses()) {
            if (!hasText(diagnosis.getTitle()) || !hasText(diagnosis.getSeverity()) || !hasText(diagnosis.getConfidence())) {
                outcome.reject("diagnoses 字段缺少 title/severity/confidence");
            }
            validateRefs("diagnoses", diagnosis.getEvidenceRefs(), evidenceIds, outcome);
        }
    }

    private void validateRewriteCandidates(TuningResult result,
                                           ContextPackage context,
                                           SqlStatementProfile originalProfile,
                                           SqlDialect dialect,
                                           Set<String> evidenceIds,
                                           ValidationOutcome outcome) {
        Iterator<RewriteCandidate> iterator = result.getRewriteCandidates().iterator();
        while (iterator.hasNext()) {
            RewriteCandidate candidate = iterator.next();
            validateRefs("rewriteCandidates", candidate.getEvidenceRefs(), evidenceIds, outcome);
            if (!hasText(candidate.getSql())) {
                outcome.reject("rewriteCandidates 缺少 sql");
                continue;
            }
            SqlStatementProfile rewrite = parser.parse(candidate.getSql(), dialect);
            if (!rewrite.isValid()) {
                outcome.reject("rewrite SQL 无法解析: " + rewrite.getReason());
                iterator.remove();
                continue;
            }
            if (!equalsIgnoreCase(originalProfile.getStatementType(), rewrite.getStatementType())) {
                outcome.reject("rewrite SQL 改变语句类型");
                iterator.remove();
                continue;
            }
            if (!sameTables(originalProfile, rewrite)) {
                outcome.reject("rewrite SQL 改变表集合");
                iterator.remove();
                continue;
            }
            if (originalProfile.isHasWhere() && !rewrite.isHasWhere()) {
                outcome.reject("rewrite SQL 删除 WHERE 业务条件");
                iterator.remove();
                continue;
            }
            if (originalProfile.isHasJoin() && !rewrite.isHasJoin()) {
                outcome.reject("rewrite SQL 删除 JOIN");
                iterator.remove();
                continue;
            }
            if (originalProfile.isHasGroupBy() && !rewrite.isHasGroupBy()) {
                outcome.reject("rewrite SQL 删除 GROUP BY");
                iterator.remove();
                continue;
            }
            if (originalProfile.isHasOrderBy() && !rewrite.isHasOrderBy()) {
                outcome.reject("rewrite SQL 删除 ORDER BY");
                iterator.remove();
                continue;
            }
            if (!sameSet(originalProfile.getWherePredicates(), rewrite.getWherePredicates())) {
                outcome.reject("rewrite SQL 改变 WHERE 业务条件");
                iterator.remove();
                continue;
            }
            if (!sameSet(originalProfile.getJoinSignatures(), rewrite.getJoinSignatures())) {
                outcome.reject("rewrite SQL 改变 JOIN 类型或 ON 条件");
                iterator.remove();
                continue;
            }
            if (!sameSet(originalProfile.getGroupByItems(), rewrite.getGroupByItems())) {
                outcome.reject("rewrite SQL 改变 GROUP BY 列");
                iterator.remove();
                continue;
            }
            if (!sameSet(originalProfile.getOrderByItems(), rewrite.getOrderByItems())) {
                outcome.reject("rewrite SQL 改变 ORDER BY 列或方向");
                iterator.remove();
                continue;
            }
            if (!sameSet(originalProfile.getPaginationSignatures(), rewrite.getPaginationSignatures())) {
                outcome.reject("rewrite SQL 改变分页语义");
                iterator.remove();
            }
        }
        if (!context.isAllowRewrite()) {
            result.getRewriteCandidates().clear();
        }
    }

    private void validateIndexCandidates(TuningResult result, ContextPackage context, Set<String> evidenceIds, ValidationOutcome outcome) {
        for (IndexCandidate candidate : result.getIndexCandidates()) {
            if (!hasText(candidate.getTableName()) || candidate.getColumnOrder().isEmpty()) {
                outcome.reject("indexCandidates 缺少 tableName/columnOrder");
            }
            validateRefs("indexCandidates", candidate.getEvidenceRefs(), evidenceIds, outcome);
            if (!context.isAllowIndexDdl() && hasText(candidate.getDdl())) {
                outcome.reject("证据不足时禁止输出候选索引 DDL");
            }
            if (!context.isAllowHighConfidence() && "HIGH".equalsIgnoreCase(candidate.getConfidence())) {
                candidate.setConfidence("MEDIUM");
            }
        }
    }

    private void validateValidationPlan(TuningResult result, Set<String> evidenceIds, ValidationOutcome outcome) {
        if (result.getValidationPlan().isEmpty()) {
            outcome.reject("缺少 validationPlan");
        }
        for (ValidationStep step : result.getValidationPlan()) {
            if (!hasText(step.getAction())) {
                outcome.reject("validationPlan 缺少 action");
            }
            validateRefs("validationPlan", step.getEvidenceRefs(), evidenceIds, outcome);
        }
    }

    private Set<String> evidenceIds(TuningResult result) {
        Set<String> ids = new HashSet<String>();
        for (EvidenceItem item : result.getEvidenceCatalog()) {
            if (hasText(item.getId())) {
                ids.add(item.getId());
            }
        }
        return ids;
    }

    private void validateRefs(String field, Iterable<String> refs, Set<String> evidenceIds, ValidationOutcome outcome) {
        boolean hasRef = false;
        for (String ref : refs) {
            hasRef = true;
            if (!evidenceIds.contains(ref)) {
                outcome.reject(field + " 引用了不存在的证据: " + ref);
            }
        }
        if (!hasRef) {
            outcome.reject(field + " 缺少 evidenceRefs");
        }
    }

    private void downgradeHighConfidence(TuningResult result) {
        for (Diagnosis diagnosis : result.getDiagnoses()) {
            if ("HIGH".equalsIgnoreCase(diagnosis.getConfidence())) {
                diagnosis.setConfidence("MEDIUM");
            }
        }
    }

    private boolean sameTables(SqlStatementProfile original, SqlStatementProfile rewrite) {
        return new HashSet<String>(lower(original.getTables())).equals(new HashSet<String>(lower(rewrite.getTables())));
    }

    private boolean sameSet(Iterable<String> left, Iterable<String> right) {
        return new HashSet<String>(lower(left)).equals(new HashSet<String>(lower(right)));
    }

    private Set<String> lower(Iterable<String> values) {
        Set<String> lowered = new HashSet<String>();
        for (String value : values) {
            lowered.add(value == null ? "" : value.toLowerCase(Locale.ROOT));
        }
        return lowered;
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return left == null ? right == null : left.equalsIgnoreCase(right);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
