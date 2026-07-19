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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class StrictResultValidator {
    private static final String ROW_COUNT_VALUE = "(\\d[\\d,]*(?:\\.\\d+)?\\s*(?:万|亿|[kKmM])?)";
    private static final Pattern UNSUPPORTED_PERFORMANCE_PROMISE = Pattern.compile(
            "(?:预计|预期|保证|确保|优化后|调整后|上线后|"
                    + "可(?:以)?(?:降(?:低|至|到)|提升|减少|缩短)|"
                    + "能够(?:降(?:低|至|到)|提升|减少|缩短)|"
                    + "将(?:会)?(?:降(?:低|至|到)|提升|减少|缩短)|"
                    + "会(?:降(?:低|至|到)|提升|减少|缩短)|"
                    + "降(?:至|到)|提升(?:至|到)|减少(?:至|到)|缩短(?:至|到)|"
                    + "reduce(?:d)?(?:\\s+to)?|drop(?:ped)?(?:\\s+to)?|improve(?:d)?(?:\\s+(?:to|by))?)"
                    + "[^。；\\n]{0,60}"
                    + "(?:\\d+(?:\\.\\d+)?\\s*(?:ms|毫秒|s|秒|%|倍|x)|十位数|个位数)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern UNSUPPORTED_ROW_COUNT_PROMISE = Pattern.compile(
            "(?:(?:预计|预期|保证|确保|优化后|调整后|上线后)[^。；\\n]{0,32})?"
                    + "(?:扫描(?:行数|量)?|读取(?:行数|量)?|处理(?:行数|量)?|返回行数|结果行数|逻辑读|物理读|rows?)"
                    + "[^。；\\n]{0,32}"
                    + "(?:可(?:以)?|能够|将(?:会)?|会|预计|预期|降(?:低|至|到)|减少(?:至|到)|缩短(?:至|到))"
                    + "[^。；\\n]{0,24}\\d+(?:\\.\\d+)?\\s*(?:行|rows?|次)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ACTUAL_ROW_CLAIM_NUMBER = Pattern.compile(
            "(?:(?:实际|真实|平均)[^。；\\n]{0,20}(?:"
                    + "(?:扫描|读取|处理|返回|输出|结果)(?:的)?(?:行数|记录数)[^。；\\n]{0,12}?" + ROW_COUNT_VALUE + "(?:\\s*(?:行|条))?|"
                    + "(?:扫描|读取|处理|返回|输出|结果)[^。；\\n]{0,12}?" + ROW_COUNT_VALUE + "\\s*(?:行|条))|"
                    + "actual\\s+(?:rows?|cardinality)\\s*(?:is|are|=|:)?\\s*" + ROW_COUNT_VALUE + ")",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern UNQUALIFIED_ROW_CLAIM_NUMBER = Pattern.compile(
            "(?:"
                    + "(?:扫描|读取|处理|返回|输出|结果)(?:的)?(?:行数|记录数)[^。；\\n]{0,12}?" + ROW_COUNT_VALUE + "(?:\\s*(?:行|条))?|"
                    + "(?:扫描|读取|处理|返回|输出)[^。；\\n]{0,16}?" + ROW_COUNT_VALUE + "\\s*(?:行|条)|"
                    + "(?:scan(?:ned)?|read|return(?:ed)?|process(?:ed)?)\\s+rows?[^.;\\n]{0,12}?" + ROW_COUNT_VALUE + "(?:\\s*rows?)?|"
                    + "(?:scan(?:ned)?|read|return(?:ed)?|process(?:ed)?)[^.;\\n]{0,16}?" + ROW_COUNT_VALUE + "\\s*rows?)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ROW_COUNT_REFERENCE_NUMBER = Pattern.compile(
            "(?:(?:行数|记录数)[^。；\\n]{0,12}?" + ROW_COUNT_VALUE + "(?:\\s*(?:行|条))?|"
                    + "(?:rows?|cardinality)[^.;\\n]{0,12}?" + ROW_COUNT_VALUE + "(?:\\s*rows?)?|"
                    + ROW_COUNT_VALUE + "\\s*(?:行|条|rows?))",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern NON_ACTUAL_ROW_QUALIFIER = Pattern.compile(
            "(?:估计|预估|估算|预计|计划估计|预计行数|"
                    + "最多|至多|不超过|不多于|上限|限制|限定|封顶|"
                    + "\\b(?:estimated|estimate|at\\s+most|up\\s+to|no\\s+more\\s+than|"
                    + "maximum|max|limit(?:ed)?|cap(?:ped)?|caps?)\\b|\\best\\.?)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern NEGATED_QUALIFIER_PREFIX = Pattern.compile(
            "(?:并不是|不是|并非|不受|没有|无|非)\\s*$|"
                    + "(?:\\b(?:not|no|without|is\\s+not|isn't|isnt)(?:\\s+(?:a|an))?\\s*)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern NEGATED_QUALIFIER_SUFFIX = Pattern.compile(
            "^\\s*(?:值|结论|限制)?\\s*(?:并不是|不是|并非|不存在|不成立|不适用|"
                    + "is\\s+not|isn't|isnt|does\\s+not\\s+apply)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern NEGATED_QUALIFIER_CONTEXT = Pattern.compile(
            "(?:(?:并不是|不是|并非|非|没有|无)[^。；\\n]{0,6}(?:估计|预估|估算|预计)|"
                    + "(?:不受|没有|无)[^。；\\n]{0,6}(?:上限|限制|限定|封顶)|"
                    + "\\b(?:not|no|without)(?:\\s+(?:a|an))?\\s+[^.;\\n]{0,8}"
                    + "(?:estimated|estimate|limit|max(?:imum)?|cap(?:ped)?)\\b)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ACTUAL_ROW_EVIDENCE_NUMBER = Pattern.compile(
            "(?:(?:实际|真实|平均)[^。；\\n]{0,20}(?:"
                    + "(?:扫描|读取|处理|返回|输出|结果)(?:的)?(?:行数|记录数)[^。；\\n]{0,12}?" + ROW_COUNT_VALUE + "(?:\\s*(?:行|条))?|"
                    + "(?:扫描|读取|处理|返回|输出|结果)[^。；\\n]{0,12}?" + ROW_COUNT_VALUE + "\\s*(?:行|条))|"
                    + "actual\\s+(?:rows?|cardinality)\\s*(?:is|are|=|:)?\\s*" + ROW_COUNT_VALUE + "|"
                    + "a-rows\\s*(?:is|are|=|:)?\\s*" + ROW_COUNT_VALUE + "|"
                    + "rows\\s+(?:produced|returned)\\s*(?:is|are|=|:)?\\s*" + ROW_COUNT_VALUE + ")",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CREATE_INDEX_DEFINITION = Pattern.compile(
            "\\bcreate\\s+(?:unique\\s+)?index\\s+[`\"a-zA-Z0-9_$#.]+\\s+on\\s+"
                    + "([`\"a-zA-Z0-9_$#.]+)\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern EXECUTABLE_DDL = Pattern.compile(
            "(?:\\bcreate\\s+(?:unique\\s+)?index\\s+[`\"a-zA-Z0-9_$#.]+\\s+on\\s+"
                    + "[`\"a-zA-Z0-9_$#.]+\\s*\\(|"
                    + "\\balter\\s+table\\s+[`\"a-zA-Z0-9_$#.]+\\s+"
                    + "(?:add|drop|modify|alter|rename|change)\\b|"
                    + "\\bdrop\\s+index\\s+[`\"a-zA-Z0-9_$#.]+(?:\\s+on\\s+[`\"a-zA-Z0-9_$#.]+)?\\b)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SHOW_INDEX_TABLE = Pattern.compile(
            "\\bshow\\s+(?:index|indexes|keys)\\s+(?:from|in)\\s+([`\"a-zA-Z0-9_$#.]+)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CREATE_TABLE_START = Pattern.compile(
            "\\bcreate\\s+table\\s+([`\"a-zA-Z0-9_$#.]+)\\s*\\(",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern INLINE_INDEX_DEFINITION = Pattern.compile(
            "(?:^|[,;\\r\\n])\\s*(?:(?:constraint\\s+[`\"a-zA-Z0-9_$#.]+\\s+)?primary\\s+key|"
                    + "(?:unique\\s+)?(?:key|index)(?:\\s+[`\"a-zA-Z0-9_$#.]+)?)\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern INDEX_COLUMN = Pattern.compile(
            "^\\s*([`\"a-zA-Z0-9_$#.]+)(?:\\s+(ASC|DESC))?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

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
        validateClaimText("summary", result.getSummary(), context, outcome);
        validateNarrative(result.getAnalysisNarrative(), evidenceIds, dialect, context, outcome);
        validateDiagnoses(result, evidenceIds, context, outcome);
        validateRewriteCandidates(result, context, originalProfile, dialect, evidenceIds, outcome);
        validateIndexCandidates(result, context, originalProfile, evidenceIds, outcome);
        validateValidationPlan(result, evidenceIds, outcome);
        validateSupplementalText(result, dialect, context, outcome);
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
                                   SqlDialect dialect,
                                   ContextPackage context,
                                   ValidationOutcome outcome) {
        if (narrative == null || !hasText(narrative.getConclusion())) {
            outcome.reject("缺少 analysisNarrative.conclusion");
            return;
        }
        if (narrative.getConclusion().length() > 480) {
            outcome.reject("analysisNarrative.conclusion 超过长度限制");
        }
        validateNarrativeText("analysisNarrative.conclusion", narrative.getConclusion(), dialect, context, outcome);
        if (narrative.getSections().isEmpty() || narrative.getSections().size() > 4) {
            outcome.reject("analysisNarrative.sections 必须包含 1 至 4 个阅读块");
        }
        for (NarrativeSection section : narrative.getSections()) {
            if (!hasText(section.getKind()) || !hasText(section.getTitle()) || !hasText(section.getBody())) {
                outcome.reject("analysisNarrative.sections 缺少 kind/title/body");
                continue;
            }
            if (!isNarrativeKind(section.getKind())) {
                outcome.reject("analysisNarrative.sections 包含不支持的 kind: " + section.getKind());
            }
            if (section.getBody().length() > 600) {
                outcome.reject("analysisNarrative.sections.body 超过长度限制");
            }
            if (section.getTitle().length() > 60) {
                outcome.reject("analysisNarrative.sections.title 超过长度限制");
            }
            validateNarrativeText("analysisNarrative.sections", section.getTitle() + "\n" + section.getBody(), dialect, context, outcome);
            validateRefs("analysisNarrative.sections", section.getEvidenceRefs(), evidenceIds, outcome);
        }
    }

    private void validateNarrativeText(String field,
                                       String value,
                                       SqlDialect dialect,
                                       ContextPackage context,
                                       ValidationOutcome outcome) {
        if (containsExecutableDdl(value)) {
            outcome.reject(field + " 不得直接包含 DDL");
        }
        if (containsExecutableSql(value)) {
            outcome.reject(field + " 不得直接包含完整 SQL");
        }
        if (dialect == SqlDialect.OB_ORACLE && value.toLowerCase(Locale.ROOT).contains("filesort")) {
            outcome.reject(field + " 在 OceanBase Oracle 模式不得使用 MySQL FILESORT 术语");
        }
        validateClaimText(field, value, context, outcome);
    }

    private boolean isNarrativeKind(String kind) {
        String value = kind.trim().toUpperCase(Locale.ROOT);
        return "CONCLUSION".equals(value) || "EVIDENCE".equals(value) || "CAUTION".equals(value)
                || "ACTION".equals(value) || "VALIDATION".equals(value);
    }

    private boolean containsExecutableDdl(String value) {
        return EXECUTABLE_DDL.matcher(value).find();
    }

    private boolean containsExecutableSql(String value) {
        return value.toLowerCase(Locale.ROOT).matches("(?s).*\\b(select\\s+.+?\\s+from|update\\s+.+?\\s+set|insert\\s+into|delete\\s+from)\\b.*");
    }

    private void validateDiagnoses(TuningResult result,
                                   Set<String> evidenceIds,
                                   ContextPackage context,
                                   ValidationOutcome outcome) {
        for (Diagnosis diagnosis : result.getDiagnoses()) {
            if (!hasText(diagnosis.getTitle()) || !hasText(diagnosis.getSeverity()) || !hasText(diagnosis.getConfidence())) {
                outcome.reject("diagnoses 字段缺少 title/severity/confidence");
            }
            validateRefs("diagnoses", diagnosis.getEvidenceRefs(), evidenceIds, outcome);
            validateClaimText("diagnoses", combine(
                    diagnosis.getTitle(), diagnosis.getImpact(), diagnosis.getPrecondition()), context, outcome);
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
            validateClaimText("rewriteCandidates", combine(
                    candidate.getChange(), candidate.getSemanticCheck(), candidate.getRisk(), candidate.getValidation()),
                    context, outcome);
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

    private void validateIndexCandidates(TuningResult result,
                                         ContextPackage context,
                                         SqlStatementProfile originalProfile,
                                         Set<String> evidenceIds,
                                         ValidationOutcome outcome) {
        for (IndexCandidate candidate : result.getIndexCandidates()) {
            if (!hasText(candidate.getTableName()) || candidate.getColumnOrder().isEmpty()) {
                outcome.reject("indexCandidates 缺少 tableName/columnOrder");
            }
            validateRefs("indexCandidates", candidate.getEvidenceRefs(), evidenceIds, outcome);
            validateClaimText("indexCandidates", combine(
                    candidate.getBenefit(), candidate.getWriteCost(), candidate.getRisk(), candidate.getValidation()),
                    context, outcome);
            IndexDefinition candidateDdl = validateCandidateDdl(candidate, outcome);
            List<String> candidateColumns = candidateDdl == null
                    ? normalizeColumns(candidate.getColumnOrder())
                    : candidateDdl.columns;
            String candidateTable = candidateDdl == null
                    ? normalizeIdentifier(candidate.getTableName())
                    : candidateDdl.table;
            if (candidateColumns.isEmpty()) {
                outcome.reject("indexCandidates.columnOrder 包含非法或不可识别列名");
            }
            if (context.isRestrictIndexDirectionToSql()) {
                validateIndexDirectionReferencesSql(candidateTable, candidateColumns, originalProfile, outcome);
            }
            if (isCoveredByExistingIndex(candidateTable, candidateColumns, context.getIndexText(), originalProfile)) {
                outcome.reject("indexCandidates 与现有索引前缀重复: " + safeIdentifier(candidate.getTableName()));
            }
            if (!context.isAllowIndexDdl() && hasText(candidate.getDdl())) {
                outcome.reject("证据不足时禁止输出候选索引 DDL");
            }
            if (!context.isAllowHighConfidence() && "HIGH".equalsIgnoreCase(candidate.getConfidence())) {
                candidate.setConfidence("MEDIUM");
            }
        }
    }

    private void validateIndexDirectionReferencesSql(String candidateTable,
                                                      List<String> candidateColumns,
                                                      SqlStatementProfile originalProfile,
                                                      ValidationOutcome outcome) {
        if (originalProfile == null) {
            outcome.reject("缺少原 SQL 结构，无法校验索引方向");
            return;
        }
        boolean tableReferenced = false;
        for (String table : originalProfile.getTables()) {
            if (candidateTable.equals(normalizeIdentifier(table))) {
                tableReferenced = true;
                break;
            }
        }
        if (!tableReferenced) {
            outcome.reject("indexCandidates 引用了原 SQL 中不存在的表: " + safeIdentifier(candidateTable));
        }

        Set<String> referencedColumns = new HashSet<String>();
        for (String column : originalProfile.getIndexRelevantColumns()) {
            referencedColumns.add(normalizeIdentifier(column));
        }
        for (String column : candidateColumns) {
            int separator = column.lastIndexOf(' ');
            String name = separator < 0 ? column : column.substring(0, separator);
            if (!referencedColumns.contains(normalizeIdentifier(name))) {
                outcome.reject("indexCandidates 引用了原 SQL 过滤、连接、分组或排序中不存在的列: "
                        + safeIdentifier(name));
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
            validatePerformancePromise("validationPlan", combine(step.getAction(), step.getExpectedSignal()), outcome);
        }
    }

    private void validateSupplementalText(TuningResult result,
                                          SqlDialect dialect,
                                          ContextPackage context,
                                          ValidationOutcome outcome) {
        for (String value : result.getMissingInformation()) {
            validateDisplayText("missingInformation", value, dialect, context, outcome);
        }
        for (String value : result.getSafetyWarnings()) {
            validateDisplayText("safetyWarnings", value, dialect, context, outcome);
        }
        for (EvidenceItem evidence : result.getEvidenceCatalog()) {
            validateDisplayText("evidenceCatalog", evidence.getSummary(), dialect, context, outcome);
        }
        if (result.getReview() != null) {
            validateDisplayText("review", result.getReview().getNotes(), dialect, context, outcome);
            for (String revision : result.getReview().getRevisions()) {
                validateDisplayText("review", revision, dialect, context, outcome);
            }
        }
    }

    private void validateDisplayText(String field,
                                     String value,
                                     SqlDialect dialect,
                                     ContextPackage context,
                                     ValidationOutcome outcome) {
        if (!hasText(value)) {
            return;
        }
        if (containsExecutableDdl(value)) {
            outcome.reject(field + " 不得直接包含 DDL");
        }
        if (containsExecutableSql(value)) {
            outcome.reject(field + " 不得直接包含完整 SQL");
        }
        if (dialect == SqlDialect.OB_ORACLE && value.toLowerCase(Locale.ROOT).contains("filesort")) {
            outcome.reject(field + " 在 OceanBase Oracle 模式不得使用 MySQL FILESORT 术语");
        }
        validateClaimText(field, value, context, outcome);
    }

    private void validateClaimText(String field,
                                   String value,
                                   ContextPackage context,
                                   ValidationOutcome outcome) {
        if (!hasText(value)) {
            return;
        }
        validatePerformancePromise(field, value, outcome);
        Set<String> claimedRows = new HashSet<String>();
        claimedRows.addAll(capturedRowCounts(ACTUAL_ROW_CLAIM_NUMBER.matcher(value)));
        claimedRows.addAll(unqualifiedActualRowClaims(value));
        if (!claimedRows.isEmpty() && !hasMatchingActualRowEvidence(claimedRows, context)) {
            outcome.reject(field + " 缺少实际行数证据，不得把估计行数写成实际结果");
        }
    }

    private void validatePerformancePromise(String field, String value, ValidationOutcome outcome) {
        if (hasText(value) && (UNSUPPORTED_PERFORMANCE_PROMISE.matcher(value).find()
                || UNSUPPORTED_ROW_COUNT_PROMISE.matcher(value).find())) {
            outcome.reject(field + " 包含无依据量化性能承诺");
        }
    }

    private Set<String> unqualifiedActualRowClaims(String value) {
        List<RowCountMention> mentions = new ArrayList<RowCountMention>();
        Matcher rowMatcher = UNQUALIFIED_ROW_CLAIM_NUMBER.matcher(value);
        while (rowMatcher.find()) {
            mentions.add(new RowCountMention(
                    rowMatcher.start(), rowMatcher.end(),
                    capturedRowCountsFromCurrentMatch(rowMatcher), true));
        }
        Matcher referenceMatcher = ROW_COUNT_REFERENCE_NUMBER.matcher(value);
        while (referenceMatcher.find()) {
            if (!overlapsMention(mentions, referenceMatcher.start(), referenceMatcher.end())) {
                mentions.add(new RowCountMention(
                        referenceMatcher.start(), referenceMatcher.end(),
                        capturedRowCountsFromCurrentMatch(referenceMatcher), false));
            }
        }
        Set<String> values = new HashSet<String>();
        for (int index = 0; index < mentions.size(); index++) {
            RowCountMention mention = mentions.get(index);
            if (mention.claim && !hasBoundNonActualQualifier(value, mentions, index)) {
                values.addAll(mention.values);
            }
        }
        return values;
    }

    private boolean overlapsMention(List<RowCountMention> mentions, int start, int end) {
        for (RowCountMention mention : mentions) {
            if (start < mention.end && mention.start < end) {
                return true;
            }
        }
        return false;
    }

    private boolean hasBoundNonActualQualifier(String value,
                                               List<RowCountMention> mentions,
                                               int mentionIndex) {
        RowCountMention current = mentions.get(mentionIndex);
        int clauseStart = clauseStart(value, current.start);
        int clauseEnd = clauseEnd(value, current.end);
        Matcher qualifierMatcher = NON_ACTUAL_ROW_QUALIFIER.matcher(value);
        qualifierMatcher.region(clauseStart, clauseEnd);
        while (qualifierMatcher.find()) {
            if (isNegatedQualifier(value, qualifierMatcher, clauseStart, clauseEnd)) {
                continue;
            }
            int distance = distanceBetween(
                    qualifierMatcher.start(), qualifierMatcher.end(), current.start, current.end);
            if (distance <= 32
                    && nearestMentionIndex(mentions, clauseStart, clauseEnd,
                    qualifierMatcher.start(), qualifierMatcher.end()) == mentionIndex) {
                return true;
            }
        }
        return false;
    }

    private boolean isNegatedQualifier(String value,
                                       Matcher qualifierMatcher,
                                       int clauseStart,
                                       int clauseEnd) {
        int contextStart = Math.max(clauseStart, qualifierMatcher.start() - 16);
        int contextEnd = Math.min(clauseEnd, qualifierMatcher.end() + 16);
        if (NEGATED_QUALIFIER_CONTEXT.matcher(value.substring(contextStart, contextEnd)).find()) {
            return true;
        }
        int prefixStart = Math.max(clauseStart, qualifierMatcher.start() - 16);
        String prefix = value.substring(prefixStart, qualifierMatcher.start());
        if (NEGATED_QUALIFIER_PREFIX.matcher(prefix).find()) {
            return true;
        }
        int suffixEnd = Math.min(clauseEnd, qualifierMatcher.end() + 20);
        String suffix = value.substring(qualifierMatcher.end(), suffixEnd);
        return NEGATED_QUALIFIER_SUFFIX.matcher(suffix).find();
    }

    private int nearestMentionIndex(List<RowCountMention> mentions,
                                    int clauseStart,
                                    int clauseEnd,
                                    int qualifierStart,
                                    int qualifierEnd) {
        int nearestIndex = -1;
        int nearestDistance = Integer.MAX_VALUE;
        for (int index = 0; index < mentions.size(); index++) {
            RowCountMention mention = mentions.get(index);
            if (mention.start < clauseStart || mention.end > clauseEnd) {
                continue;
            }
            int distance = distanceBetween(qualifierStart, qualifierEnd, mention.start, mention.end);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestIndex = index;
            }
        }
        return nearestIndex;
    }

    private int distanceBetween(int firstStart, int firstEnd, int secondStart, int secondEnd) {
        if (firstEnd < secondStart) {
            return secondStart - firstEnd;
        }
        if (secondEnd < firstStart) {
            return firstStart - secondEnd;
        }
        return 0;
    }

    private int clauseStart(String value, int position) {
        for (int index = position - 1; index >= 0; index--) {
            if (isClauseBoundary(value, index)) {
                return index + 1;
            }
        }
        return 0;
    }

    private int clauseEnd(String value, int position) {
        for (int index = position; index < value.length(); index++) {
            if (isClauseBoundary(value, index)) {
                return index;
            }
        }
        return value.length();
    }

    private boolean isClauseBoundary(String value, int index) {
        char current = value.charAt(index);
        if (current == '。' || current == '；' || current == ';'
                || current == '\n' || current == '！' || current == '!'
                || current == '？' || current == '?') {
            return true;
        }
        return current == '.'
                && index + 1 < value.length()
                && Character.isWhitespace(value.charAt(index + 1))
                && !isEstimateAbbreviation(value, index);
    }

    private boolean isEstimateAbbreviation(String value, int periodIndex) {
        int start = Math.max(0, periodIndex - 3);
        return "est.".equalsIgnoreCase(value.substring(start, periodIndex + 1));
    }

    private boolean hasMatchingActualRowEvidence(Set<String> claimedRows, ContextPackage context) {
        if (context == null) {
            return false;
        }
        Set<String> evidenceRows = capturedRowCounts(ACTUAL_ROW_EVIDENCE_NUMBER.matcher(combine(
                context.getRuntimeMetricsText(), context.getExplainText())));
        if (claimedRows.isEmpty() || evidenceRows.isEmpty()) {
            return false;
        }
        return evidenceRows.containsAll(claimedRows);
    }

    private Set<String> capturedRowCounts(Matcher matcher) {
        Set<String> values = new HashSet<String>();
        while (matcher.find()) {
            values.addAll(capturedRowCountsFromCurrentMatch(matcher));
        }
        return values;
    }

    private Set<String> capturedRowCountsFromCurrentMatch(Matcher matcher) {
        Set<String> values = new HashSet<String>();
        for (int group = 1; group <= matcher.groupCount(); group++) {
            if (matcher.group(group) != null) {
                values.add(normalizeRowCount(matcher.group(group)));
            }
        }
        return values;
    }

    private String normalizeRowCount(String rawValue) {
        String normalized = rawValue.replace(",", "").replace(" ", "").trim().toLowerCase(Locale.ROOT);
        BigDecimal multiplier = BigDecimal.ONE;
        if (normalized.endsWith("万")) {
            normalized = normalized.substring(0, normalized.length() - 1);
            multiplier = new BigDecimal("10000");
        } else if (normalized.endsWith("亿")) {
            normalized = normalized.substring(0, normalized.length() - 1);
            multiplier = new BigDecimal("100000000");
        } else if (normalized.endsWith("k")) {
            normalized = normalized.substring(0, normalized.length() - 1);
            multiplier = new BigDecimal("1000");
        } else if (normalized.endsWith("m")) {
            normalized = normalized.substring(0, normalized.length() - 1);
            multiplier = new BigDecimal("1000000");
        }
        try {
            return new BigDecimal(normalized).multiply(multiplier).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ignored) {
            return normalized;
        }
    }

    private static final class RowCountMention {
        private final int start;
        private final int end;
        private final Set<String> values;
        private final boolean claim;

        private RowCountMention(int start, int end, Set<String> values, boolean claim) {
            this.start = start;
            this.end = end;
            this.values = values;
            this.claim = claim;
        }
    }

    private IndexDefinition validateCandidateDdl(IndexCandidate candidate, ValidationOutcome outcome) {
        if (candidate == null || !hasText(candidate.getDdl())) {
            return null;
        }
        Matcher matcher = CREATE_INDEX_DEFINITION.matcher(candidate.getDdl());
        if (!matcher.find()) {
            outcome.reject("indexCandidates.ddl 必须是单条 CREATE INDEX");
            return null;
        }
        String remainder = (candidate.getDdl().substring(0, matcher.start())
                + candidate.getDdl().substring(matcher.end())).replace(";", "").trim();
        String ddlTable = matcher.group(1);
        String ddlColumns = matcher.group(2);
        if (!remainder.isEmpty() || matcher.find()) {
            outcome.reject("indexCandidates.ddl 只能包含单条 CREATE INDEX");
            return null;
        }
        IndexDefinition definition = indexDefinition(ddlTable, ddlColumns);
        List<String> metadataColumns = normalizeColumns(candidate.getColumnOrder());
        if (definition == null
                || !definition.table.equals(normalizeIdentifier(candidate.getTableName()))
                || !definition.columns.equals(metadataColumns)) {
            outcome.reject("indexCandidates DDL 与 tableName/columnOrder 不一致");
            return null;
        }
        return definition;
    }

    private boolean isCoveredByExistingIndex(String candidateTable,
                                             List<String> candidateColumns,
                                             String indexText,
                                             SqlStatementProfile originalProfile) {
        if (!hasText(candidateTable) || candidateColumns == null || candidateColumns.isEmpty() || !hasText(indexText)) {
            return false;
        }
        for (IndexDefinition existing : existingIndexDefinitions(indexText)) {
            if (!sameIndexTable(candidateTable, existing.table, originalProfile)) {
                continue;
            }
            if (startsWith(existing.columns, candidateColumns)) {
                return true;
            }
        }
        return false;
    }

    private List<IndexDefinition> existingIndexDefinitions(String indexText) {
        List<IndexDefinition> definitions = new ArrayList<IndexDefinition>();
        Matcher standalone = CREATE_INDEX_DEFINITION.matcher(indexText);
        while (standalone.find()) {
            IndexDefinition definition = indexDefinition(standalone.group(1), standalone.group(2));
            if (definition != null) {
                definitions.add(definition);
            }
        }
        definitions.addAll(showIndexDefinitions(indexText));

        boolean scopedInlineFound = false;
        Matcher tableMatcher = CREATE_TABLE_START.matcher(indexText);
        while (tableMatcher.find()) {
            int open = tableMatcher.end() - 1;
            int close = matchingParenthesis(indexText, open);
            if (close < 0) {
                continue;
            }
            Matcher inline = INLINE_INDEX_DEFINITION.matcher(indexText.substring(open + 1, close));
            while (inline.find()) {
                IndexDefinition definition = indexDefinition(tableMatcher.group(1), inline.group(1));
                if (definition != null) {
                    definitions.add(definition);
                    scopedInlineFound = true;
                }
            }
        }

        if (!scopedInlineFound) {
            Matcher inline = INLINE_INDEX_DEFINITION.matcher(indexText);
            while (inline.find()) {
                IndexDefinition definition = indexDefinition("", inline.group(1));
                if (definition != null) {
                    definitions.add(definition);
                }
            }
        }
        return definitions;
    }

    private List<IndexDefinition> showIndexDefinitions(String indexText) {
        List<IndexDefinition> definitions = new ArrayList<IndexDefinition>();
        String fallbackTable = "";
        Matcher showIndex = SHOW_INDEX_TABLE.matcher(indexText);
        if (showIndex.find()) {
            fallbackTable = normalizeIdentifier(showIndex.group(1));
        }

        String[] lines = indexText.split("\\r?\\n");
        List<String> headers = null;
        int tableColumn = -1;
        int keyColumn = -1;
        int sequenceColumn = -1;
        int nameColumn = -1;
        int collationColumn = -1;
        Map<String, TreeMap<Integer, String>> grouped = new LinkedHashMap<String, TreeMap<Integer, String>>();
        Map<String, String> groupedTables = new LinkedHashMap<String, String>();
        for (String line : lines) {
            List<String> cells = tableCells(line);
            if (cells.isEmpty()) {
                continue;
            }
            if (headers == null) {
                List<String> normalizedHeaders = normalizedHeaders(cells);
                keyColumn = normalizedHeaders.indexOf("keyname");
                sequenceColumn = normalizedHeaders.indexOf("seqinindex");
                nameColumn = normalizedHeaders.indexOf("columnname");
                if (keyColumn < 0 || sequenceColumn < 0 || nameColumn < 0) {
                    continue;
                }
                headers = normalizedHeaders;
                tableColumn = headers.indexOf("table");
                collationColumn = headers.indexOf("collation");
                continue;
            }
            int requiredMax = Math.max(keyColumn, Math.max(sequenceColumn, nameColumn));
            if (cells.size() <= requiredMax || isSeparatorRow(cells)) {
                continue;
            }
            Integer sequence = positiveInteger(cells.get(sequenceColumn));
            String keyName = normalizeIdentifier(cells.get(keyColumn));
            String columnName = normalizeIdentifier(cells.get(nameColumn));
            String table = tableColumn >= 0 && cells.size() > tableColumn
                    ? normalizeIdentifier(cells.get(tableColumn))
                    : fallbackTable;
            if (sequence == null || keyName.isEmpty() || columnName.isEmpty()
                    || !columnName.matches("[a-z0-9_$#]+")) {
                continue;
            }
            String direction = "asc";
            if (collationColumn >= 0 && cells.size() > collationColumn
                    && "d".equalsIgnoreCase(cells.get(collationColumn).trim())) {
                direction = "desc";
            }
            String groupKey = table + "\u0000" + keyName;
            TreeMap<Integer, String> columns = grouped.get(groupKey);
            if (columns == null) {
                columns = new TreeMap<Integer, String>();
                grouped.put(groupKey, columns);
                groupedTables.put(groupKey, table);
            }
            columns.put(sequence, columnName + " " + direction);
        }
        for (Map.Entry<String, TreeMap<Integer, String>> entry : grouped.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                definitions.add(new IndexDefinition(groupedTables.get(entry.getKey()),
                        new ArrayList<String>(entry.getValue().values())));
            }
        }
        return definitions;
    }

    private List<String> tableCells(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty() || trimmed.matches("^[+|\\-: ]+$")) {
            return new ArrayList<String>();
        }
        String[] raw;
        if (trimmed.indexOf('|') >= 0) {
            raw = trimmed.replaceFirst("^\\|", "").replaceFirst("\\|$", "").split("\\s*\\|\\s*", -1);
        } else if (trimmed.indexOf('\t') >= 0) {
            raw = trimmed.split("\\t+", -1);
        } else {
            raw = trimmed.split("\\s{2,}", -1);
        }
        List<String> cells = new ArrayList<String>();
        for (String value : raw) {
            cells.add(value.trim());
        }
        return cells;
    }

    private List<String> normalizedHeaders(List<String> cells) {
        List<String> headers = new ArrayList<String>();
        for (String cell : cells) {
            headers.add(cell.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""));
        }
        return headers;
    }

    private boolean isSeparatorRow(List<String> cells) {
        for (String cell : cells) {
            if (!cell.matches("^[-+: ]*$")) {
                return false;
            }
        }
        return true;
    }

    private Integer positiveInteger(String value) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private IndexDefinition indexDefinition(String table, String columnsText) {
        List<String> columns = normalizeColumns(splitColumns(columnsText));
        if (columns.isEmpty()) {
            return null;
        }
        return new IndexDefinition(normalizeIdentifier(table), columns);
    }

    private boolean sameIndexTable(String candidateTable,
                                   String existingTable,
                                   SqlStatementProfile originalProfile) {
        if (candidateTable.equals(existingTable)) {
            return true;
        }
        if (!existingTable.isEmpty() || originalProfile == null || originalProfile.getTables().size() != 1) {
            return false;
        }
        return candidateTable.equals(normalizeIdentifier(originalProfile.getTables().get(0)));
    }

    private int matchingParenthesis(String value, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < value.length(); i++) {
            char current = value.charAt(i);
            if (quote != 0) {
                if (current == quote) {
                    if (i + 1 < value.length() && value.charAt(i + 1) == quote) {
                        i++;
                    } else {
                        quote = 0;
                    }
                } else if (current == '\\' && i + 1 < value.length()) {
                    i++;
                }
                continue;
            }
            if (current == '\'' || current == '\"' || current == '`') {
                quote = current;
            } else if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private List<String> splitColumns(String value) {
        List<String> columns = new ArrayList<String>();
        if (!hasText(value)) {
            return columns;
        }
        for (String column : value.split(",")) {
            columns.add(column);
        }
        return columns;
    }

    private List<String> normalizeColumns(Iterable<String> values) {
        List<String> columns = new ArrayList<String>();
        if (values == null) {
            return columns;
        }
        for (String value : values) {
            if (!hasText(value)) {
                return new ArrayList<String>();
            }
            Matcher columnMatcher = INDEX_COLUMN.matcher(value);
            if (!columnMatcher.matches()) {
                return new ArrayList<String>();
            }
            String normalized = normalizeIdentifier(columnMatcher.group(1));
            if (!normalized.matches("[a-z0-9_$#]+")) {
                return new ArrayList<String>();
            }
            String direction = columnMatcher.group(2) == null
                    ? "asc"
                    : columnMatcher.group(2).toLowerCase(Locale.ROOT);
            columns.add(normalized + " " + direction);
        }
        return columns;
    }

    private boolean startsWith(List<String> existing, List<String> candidate) {
        if (existing.size() < candidate.size()) {
            return false;
        }
        for (int i = 0; i < candidate.size(); i++) {
            if (!existing.get(i).equals(candidate.get(i))) {
                return false;
            }
        }
        return true;
    }

    private String normalizeIdentifier(String value) {
        if (!hasText(value)) {
            return "";
        }
        String normalized = value.trim()
                .replace("`", "")
                .replace("\"", "")
                .replace("[", "")
                .replace("]", "");
        int separator = normalized.lastIndexOf('.');
        if (separator >= 0) {
            normalized = normalized.substring(separator + 1);
        }
        return normalized.trim().toLowerCase(Locale.ROOT);
    }

    private String safeIdentifier(String value) {
        String normalized = normalizeIdentifier(value);
        return normalized.isEmpty() ? "unknown" : normalized;
    }

    private String combine(String... values) {
        StringBuilder builder = new StringBuilder();
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!hasText(value)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(value);
        }
        return builder.toString();
    }

    private static final class IndexDefinition {
        private final String table;
        private final List<String> columns;

        private IndexDefinition(String table, List<String> columns) {
            this.table = table;
            this.columns = columns;
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
