package com.codex.sqltuner.tuning.accuracy;

import com.codex.sqltuner.rule.SqlDialect;
import com.codex.sqltuner.tuning.TuningResult;
import com.codex.sqltuner.tuning.result.RewriteCandidate;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class SqlSemanticValidator {
    private final SqlStatementParser parser;
    private final EvidenceReferenceValidator evidenceReferenceValidator;
    private final ResultTextSafetyValidator textSafetyValidator;

    SqlSemanticValidator(SqlStatementParser parser,
                         EvidenceReferenceValidator evidenceReferenceValidator,
                         ResultTextSafetyValidator textSafetyValidator) {
        this.parser = parser;
        this.evidenceReferenceValidator = evidenceReferenceValidator;
        this.textSafetyValidator = textSafetyValidator;
    }

    void validateRewriteCandidates(TuningResult result,
                                   ContextPackage context,
                                   SqlStatementProfile originalProfile,
                                   SqlDialect dialect,
                                   Set<String> evidenceIds,
                                   ValidationOutcome outcome) {
        Iterator<RewriteCandidate> iterator = result.getRewriteCandidates().iterator();
        while (iterator.hasNext()) {
            RewriteCandidate candidate = iterator.next();
            evidenceReferenceValidator.validateRefs("rewriteCandidates", candidate.getEvidenceRefs(), evidenceIds, outcome);
            textSafetyValidator.validateClaimText("rewriteCandidates", ResultTextSafetyValidator.combine(
                    candidate.getChange(), candidate.getSemanticCheck(), candidate.getRisk(), candidate.getValidation()),
                    context, outcome);
            if (!ResultTextSafetyValidator.hasText(candidate.getSql())) {
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

    void validateIndexDirectionReferencesSql(String candidateTable,
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

    static String normalizeIdentifier(String value) {
        if (!ResultTextSafetyValidator.hasText(value)) {
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

    static String safeIdentifier(String value) {
        String normalized = normalizeIdentifier(value);
        return normalized.isEmpty() ? "unknown" : normalized;
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
}
