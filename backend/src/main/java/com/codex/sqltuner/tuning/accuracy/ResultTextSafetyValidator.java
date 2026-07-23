package com.codex.sqltuner.tuning.accuracy;

import com.codex.sqltuner.rule.SqlDialect;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ResultTextSafetyValidator {
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
    private static final Pattern EXECUTABLE_DDL = Pattern.compile(
            "(?:\\bcreate\\s+(?:unique\\s+)?index\\s+[`\"a-zA-Z0-9_$#.]+\\s+on\\s+"
                    + "[`\"a-zA-Z0-9_$#.]+\\s*\\(|"
                    + "\\balter\\s+table\\s+[`\"a-zA-Z0-9_$#.]+\\s+"
                    + "(?:add|drop|modify|alter|rename|change)\\b|"
                    + "\\bdrop\\s+index\\s+[`\"a-zA-Z0-9_$#.]+(?:\\s+on\\s+[`\"a-zA-Z0-9_$#.]+)?\\b)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    void validateNarrativeText(String field,
                               String value,
                               SqlDialect dialect,
                               ContextPackage context,
                               ValidationOutcome outcome) {
        validateTextWithoutEmptyGuard(field, value, dialect, context, outcome);
    }

    void validateDisplayText(String field,
                             String value,
                             SqlDialect dialect,
                             ContextPackage context,
                             ValidationOutcome outcome) {
        if (!hasText(value)) {
            return;
        }
        validateTextWithoutEmptyGuard(field, value, dialect, context, outcome);
    }

    void validateClaimText(String field, String value, ContextPackage context, ValidationOutcome outcome) {
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

    void validatePerformancePromise(String field, String value, ValidationOutcome outcome) {
        if (hasText(value) && (UNSUPPORTED_PERFORMANCE_PROMISE.matcher(value).find()
                || UNSUPPORTED_ROW_COUNT_PROMISE.matcher(value).find())) {
            outcome.reject(field + " 包含无依据量化性能承诺");
        }
    }

    static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    static String combine(String... values) {
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

    private void validateTextWithoutEmptyGuard(String field,
                                               String value,
                                               SqlDialect dialect,
                                               ContextPackage context,
                                               ValidationOutcome outcome) {
        if (EXECUTABLE_DDL.matcher(value).find()) {
            outcome.reject(field + " 不得直接包含 DDL");
        }
        if (value.toLowerCase(Locale.ROOT).matches("(?s).*\\b(select\\s+.+?\\s+from|update\\s+.+?\\s+set|insert\\s+into|delete\\s+from)\\b.*")) {
            outcome.reject(field + " 不得直接包含完整 SQL");
        }
        if (dialect == SqlDialect.OB_ORACLE && value.toLowerCase(Locale.ROOT).contains("filesort")) {
            outcome.reject(field + " 在 OceanBase Oracle 模式不得使用 MySQL FILESORT 术语");
        }
        validateClaimText(field, value, context, outcome);
    }

    private Set<String> unqualifiedActualRowClaims(String value) {
        List<RowCountMention> mentions = new ArrayList<RowCountMention>();
        Matcher rowMatcher = UNQUALIFIED_ROW_CLAIM_NUMBER.matcher(value);
        while (rowMatcher.find()) {
            mentions.add(new RowCountMention(rowMatcher.start(), rowMatcher.end(),
                    capturedRowCountsFromCurrentMatch(rowMatcher), true));
        }
        Matcher referenceMatcher = ROW_COUNT_REFERENCE_NUMBER.matcher(value);
        while (referenceMatcher.find()) {
            if (!overlapsMention(mentions, referenceMatcher.start(), referenceMatcher.end())) {
                mentions.add(new RowCountMention(referenceMatcher.start(), referenceMatcher.end(),
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

    private boolean hasBoundNonActualQualifier(String value, List<RowCountMention> mentions, int mentionIndex) {
        RowCountMention current = mentions.get(mentionIndex);
        int clauseStart = clauseStart(value, current.start);
        int clauseEnd = clauseEnd(value, current.end);
        Matcher qualifierMatcher = NON_ACTUAL_ROW_QUALIFIER.matcher(value);
        qualifierMatcher.region(clauseStart, clauseEnd);
        while (qualifierMatcher.find()) {
            if (isNegatedQualifier(value, qualifierMatcher, clauseStart, clauseEnd)) {
                continue;
            }
            int distance = distanceBetween(qualifierMatcher.start(), qualifierMatcher.end(), current.start, current.end);
            if (distance <= 32 && (nearestMentionIndex(mentions, clauseStart, clauseEnd,
                    qualifierMatcher.start(), qualifierMatcher.end()) == mentionIndex
                    || noRowCountMentionBetween(mentions, current,
                    qualifierMatcher.start(), qualifierMatcher.end()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 同一句中，表统计可能位于另一个带“计划估计”限定的计数之前；前者不能让后者失去限定词。
     */
    private boolean noRowCountMentionBetween(List<RowCountMention> mentions,
                                             RowCountMention current,
                                             int qualifierStart,
                                             int qualifierEnd) {
        int start;
        int end;
        if (qualifierEnd <= current.start) {
            start = qualifierEnd;
            end = current.start;
        } else if (current.end <= qualifierStart) {
            start = current.end;
            end = qualifierStart;
        } else {
            return true;
        }
        for (RowCountMention mention : mentions) {
            if (mention == current) {
                continue;
            }
            if (mention.start >= start && mention.end <= end) {
                return false;
            }
        }
        return true;
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
        return current == '.' && index + 1 < value.length()
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
        return !claimedRows.isEmpty() && !evidenceRows.isEmpty() && evidenceRows.containsAll(claimedRows);
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
}
