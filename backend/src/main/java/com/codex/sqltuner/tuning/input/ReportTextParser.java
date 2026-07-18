package com.codex.sqltuner.tuning.input;

import com.codex.sqltuner.rule.SqlDialect;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ReportTextParser {
    static final int MAX_INPUT_BYTES = 128 * 1024;

    private static final Pattern SQL_ID_LABEL = Pattern.compile("(?i)(?<![A-Za-z0-9_])SQL\\s*ID\\s*[:：]\\s*");
    private static final Pattern SQL_LABEL = Pattern.compile("(?i)(?<![A-Za-z0-9_])SQL(?!\\s*ID\\b)\\s*[:：]\\s*");
    private static final Pattern EXECUTIONS_LABEL = Pattern.compile("(?<!\\S)执行次数\\s*[:：]\\s*");
    private static final Pattern CPU_LABEL = Pattern.compile("(?i)(?<!\\S)CPU\\s*占比\\s*[:：]\\s*");
    private static final Pattern DURATION_LABEL = Pattern.compile("(?<!\\S)平均耗时\\s*[:：]\\s*");
    private static final Pattern ROOT_CAUSE_LABEL = Pattern.compile("(?<!\\S)根因\\s*[:：]\\s*");
    private static final Pattern THROTTLE_LABEL = Pattern.compile("(?<!\\S)限流值\\s*[:：]\\s*");
    private static final Pattern ADVICE_LABEL = Pattern.compile("(?<!\\S)优化建议\\s*[:：]\\s*");
    private static final Pattern PLAN_LABEL = Pattern.compile(
            "(?im)^[\\t ]*(?:执行计划(?:文本)?|EXPLAIN(?:\\s+PLAN)?|PLAN)[\\t ]*(?:[:：][\\t ]*|(?:\\r?\\n|$))");
    private static final Pattern SCHEMA_LABEL = Pattern.compile(
            "(?im)^[\\t ]*(?:表结构|表定义|建表语句|表\\s*DDL|SCHEMA|TABLE\\s+DDL)[\\t ]*(?:[:：][\\t ]*|(?:\\r?\\n|$))");
    private static final Pattern INDEX_LABEL = Pattern.compile(
            "(?im)^[\\t ]*(?:现有[\\t ]*)?(?:索引(?:定义|信息|列表)?|INDEX(?:ES)?|INDEX[\\t ]+DEFINITIONS?|SHOW[\\t ]+INDEX(?:ES)?)[\\t ]*(?:[:：][\\t ]*|(?:\\r?\\n|$))");
    private static final Pattern TABLE_STATS_LABEL = Pattern.compile(
            "(?im)^[\\t ]*(?:表统计(?:信息)?|表行数|TABLE[\\t ]+STATS?)[\\t ]*(?:[:：][\\t ]*|(?:\\r?\\n|$))");
    private static final Pattern OB_VERSION_LABEL = Pattern.compile(
            "(?im)^[\\t ]*(?:(?:OCEANBASE|OB)[\\t ]*)?(?:数据库[\\t ]*)?(?:版本|VERSION)[\\t ]*[:：][\\t ]*([^\\r\\n]+)");
    private static final Pattern OB_VERSION_INLINE = Pattern.compile(
            "(?i)\\b(?:OCEANBASE|OB)[\\t ]*(?:(?:CE|EE)[\\t ]*)?(?:VERSION[\\t ]*)?[vV]?([0-9]+(?:\\.[0-9]+){1,5}(?:[-_][A-Za-z0-9.]+)?)");

    private static final Pattern SQL_START = Pattern.compile("(?is)^\\s*(?:SELECT|WITH|INSERT|UPDATE|DELETE)\\b");
    private static final Pattern ORACLE_DIALECT = Pattern.compile("(?is)(?:\\bROWNUM\\b|\\bFETCH\\s+FIRST\\b|\"[^\"\\r\\n]+\")");
    private static final Pattern CREATE_TABLE_STATEMENT = Pattern.compile(
            "(?is)\\bCREATE\\s+(?:GLOBAL\\s+TEMPORARY\\s+)?TABLE\\b.*?(?:;|\\z)");
    private static final Pattern CREATE_INDEX_STATEMENT = Pattern.compile(
            "(?is)\\bCREATE\\s+(?:UNIQUE\\s+)?INDEX\\b.*?(?:;|\\z)");
    private static final Pattern INDEX_DEFINITION = Pattern.compile(
            "(?is)\\b(?:CREATE\\s+(?:UNIQUE\\s+)?INDEX|SHOW\\s+INDEX(?:ES)?|PRIMARY\\s+KEY|UNIQUE\\s+(?:KEY|INDEX)|KEY\\s+[`\"]?[A-Za-z0-9_$]+|INDEX\\s+[`\"]?[A-Za-z0-9_$]+)\\b");
    private static final Pattern TABLE_ROW_COUNT = Pattern.compile(
            "(?i)^\\s*SELECT\\s+(?:\\*|COUNT\\s*\\(\\s*(?:\\*|1)\\s*\\))\\s+FROM\\s+([^\\s;]+)"
                    + "(?:\\s+[A-Za-z][A-Za-z0-9_$]*)?\\s*;?\\s+(?:行数\\s*[:：]?\\s*)?"
                    + "([0-9][0-9,]*)\\s*(?:行|ROWS?)?\\s*$");
    private static final Pattern PLAN_OPERATOR = Pattern.compile(
            "(?is)(?:\\bOPERATOR\\b|\\bPLAN\\s+HASH\\s+VALUE\\b|\\bTABLE\\s+(?:ACCESS|SCAN)\\b|"
                    + "\\bTABLE\\s+(?:ACCESS\\s+)?(?:FULL|RANGE|GET|SCAN)\\b|"
                    + "\\bINDEX\\s+(?:SCAN|RANGE|LOOKUP|UNIQUE)\\b|\\bNESTED[ _-]*LOOP\\b|\\bHASH\\s+JOIN\\b|"
                    + "\\bMERGE\\s+JOIN\\b|\\bPX\\s+(?:COORDINATOR|SEND|RECEIVE)\\b|\\bEXCHANGE\\b|"
                    + "\\bSUBPLAN\\b|\\bEST\\.?\\s*ROWS\\b|\\bSORT\\b|\\bGROUP\\s+BY\\b)");

    private static final List<Pattern> SQL_END_LABELS = Arrays.asList(
            EXECUTIONS_LABEL, CPU_LABEL, DURATION_LABEL, ROOT_CAUSE_LABEL,
            THROTTLE_LABEL, ADVICE_LABEL, PLAN_LABEL, SCHEMA_LABEL, INDEX_LABEL,
            TABLE_STATS_LABEL, OB_VERSION_LABEL);
    private static final List<Pattern> SECTION_END_LABELS = Arrays.asList(
            SQL_LABEL, EXECUTIONS_LABEL, CPU_LABEL, DURATION_LABEL, ROOT_CAUSE_LABEL,
            THROTTLE_LABEL, ADVICE_LABEL, PLAN_LABEL, SCHEMA_LABEL, INDEX_LABEL,
            TABLE_STATS_LABEL, OB_VERSION_LABEL);

    public ParsedReport parse(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("报告文本不能为空");
        }
        if (input.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("报告文本不能超过 128 KiB");
        }

        String text = normalize(input);
        if (looksLikeSql(text)) {
            List<String> warnings = new ArrayList<String>();
            warnings.add("未识别到报告字段，已按纯 SQL 处理；请补充运行指标和文本 EXPLAIN。");
            return new ParsedReport(text.trim(), inferDialect(text), "", "", "", "", "", "", "", warnings);
        }

        Matcher sqlLabel = SQL_LABEL.matcher(text);
        if (!sqlLabel.find()) {
            throw new IllegalArgumentException("报告中未找到 SQL 字段");
        }
        int sqlEnd = firstLabelStart(text, sqlLabel.end(), SQL_END_LABELS);
        String sql = text.substring(sqlLabel.end(), sqlEnd).trim();
        if (!looksLikeSql(sql)) {
            throw new IllegalArgumentException("报告中的 SQL 字段为空或不是可识别的 SQL");
        }

        String sqlId = extractSqlId(text);
        String executions = extractValue(text, EXECUTIONS_LABEL, Arrays.asList(
                CPU_LABEL, DURATION_LABEL, ROOT_CAUSE_LABEL, THROTTLE_LABEL, ADVICE_LABEL, PLAN_LABEL,
                SCHEMA_LABEL, INDEX_LABEL, TABLE_STATS_LABEL, OB_VERSION_LABEL));
        String cpu = extractValue(text, CPU_LABEL, Arrays.asList(
                DURATION_LABEL, ROOT_CAUSE_LABEL, THROTTLE_LABEL, ADVICE_LABEL, PLAN_LABEL,
                SCHEMA_LABEL, INDEX_LABEL, TABLE_STATS_LABEL, OB_VERSION_LABEL));
        String duration = extractValue(text, DURATION_LABEL, Arrays.asList(
                ROOT_CAUSE_LABEL, THROTTLE_LABEL, ADVICE_LABEL, PLAN_LABEL,
                SCHEMA_LABEL, INDEX_LABEL, TABLE_STATS_LABEL, OB_VERSION_LABEL));
        String rootCause = extractValue(text, ROOT_CAUSE_LABEL, Arrays.asList(
                THROTTLE_LABEL, ADVICE_LABEL, PLAN_LABEL, SCHEMA_LABEL, INDEX_LABEL,
                TABLE_STATS_LABEL, OB_VERSION_LABEL));
        String throttle = extractValue(text, THROTTLE_LABEL, Arrays.asList(
                ADVICE_LABEL, PLAN_LABEL, SCHEMA_LABEL, INDEX_LABEL, TABLE_STATS_LABEL, OB_VERSION_LABEL));
        String advice = extractValue(text, ADVICE_LABEL, Arrays.asList(
                PLAN_LABEL, SCHEMA_LABEL, INDEX_LABEL, TABLE_STATS_LABEL, OB_VERSION_LABEL));

        List<String> warnings = new ArrayList<String>();
        String priorAnalysis = buildPriorAnalysis(rootCause, advice, warnings);
        PlanExtraction plan = extractPlan(text, warnings);
        String schemaText = extractSchema(text);
        String indexText = extractIndexText(text, schemaText);
        String explicitStats = extractTableStats(text);
        String tableStats = joinNonEmpty(plan.tableStatsText, explicitStats);
        String obVersion = extractObVersion(text);

        return new ParsedReport(
                sql,
                inferDialect(sql),
                buildRuntimeMetrics(sqlId, executions, cpu, duration, throttle),
                tableStats,
                priorAnalysis,
                plan.explainText,
                schemaText,
                indexText,
                obVersion,
                warnings);
    }

    private static String normalize(String input) {
        return input.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\u00A0', ' ')
                .trim();
    }

    private static boolean looksLikeSql(String text) {
        return SQL_START.matcher(text).find();
    }

    private static String inferDialect(String sql) {
        return ORACLE_DIALECT.matcher(sql).find()
                ? SqlDialect.OB_ORACLE.getDisplayName()
                : SqlDialect.OB_MYSQL.getDisplayName();
    }

    private static String extractSqlId(String text) {
        Matcher matcher = SQL_ID_LABEL.matcher(text);
        if (!matcher.find()) {
            return "";
        }
        int end = firstLabelStart(text, matcher.end(), Arrays.asList(SQL_LABEL, EXECUTIONS_LABEL));
        return cleanValue(text.substring(matcher.end(), end));
    }

    private static String extractValue(String text, Pattern label, List<Pattern> endLabels) {
        Matcher matcher = label.matcher(text);
        if (!matcher.find()) {
            return "";
        }
        int end = firstLabelStart(text, matcher.end(), endLabels);
        return cleanValue(text.substring(matcher.end(), end));
    }

    private static int firstLabelStart(String text, int fromIndex, List<Pattern> labels) {
        int end = text.length();
        for (Pattern label : labels) {
            Matcher matcher = label.matcher(text);
            if (matcher.find(fromIndex) && matcher.start() < end) {
                end = matcher.start();
            }
        }
        return end;
    }

    private static String cleanValue(String value) {
        return value.trim().replaceAll("[\\t ]+", " ");
    }

    private static String buildRuntimeMetrics(String sqlId,
                                              String executions,
                                              String cpu,
                                              String duration,
                                              String throttle) {
        List<String> lines = new ArrayList<String>();
        addMetric(lines, "SQL ID", sqlId);
        addMetric(lines, "执行次数", executions);
        addMetric(lines, "CPU 占比", cpu);
        addMetric(lines, "平均耗时", duration);
        addMetric(lines, "限流值", throttle);
        return join(lines);
    }

    private static void addMetric(List<String> lines, String label, String value) {
        if (!value.isEmpty()) {
            lines.add(label + ": " + value);
        }
    }

    private static String buildPriorAnalysis(String rootCause, String advice, List<String> warnings) {
        List<String> sections = new ArrayList<String>();
        if (!rootCause.isEmpty()) {
            sections.add("历史根因（待核验，不作为事实证据）:\n" + rootCause);
        }
        if (!advice.isEmpty()) {
            sections.add("历史优化建议（待核验，不作为事实证据）:\n" + advice);
        }
        if (!sections.isEmpty()) {
            warnings.add("报告中的根因和优化建议属于历史结论，必须结合当前证据重新核验。");
        }
        return joinSections(sections);
    }

    private static String extractSchema(String text) {
        String labeled = extractLabeledSection(text, SCHEMA_LABEL);
        if (CREATE_TABLE_STATEMENT.matcher(labeled).find()) {
            return labeled;
        }
        return extractStatements(directEvidencePrefix(text), CREATE_TABLE_STATEMENT);
    }

    private static String extractIndexText(String text, String schemaText) {
        String labeled = extractLabeledSection(text, INDEX_LABEL);
        if (hasText(labeled)) {
            return labeled;
        }
        if (INDEX_DEFINITION.matcher(schemaText).find()) {
            // 建表 DDL 中的主键和索引同样是当前索引证据，无需要求用户重复粘贴。
            return schemaText;
        }
        return extractStatements(directEvidencePrefix(text), CREATE_INDEX_STATEMENT);
    }

    private static String extractTableStats(String text) {
        return extractLabeledSection(text, TABLE_STATS_LABEL);
    }

    private static String extractObVersion(String text) {
        Matcher labeled = OB_VERSION_LABEL.matcher(text);
        if (labeled.find()) {
            String value = cleanValue(labeled.group(1));
            return value.matches(".*[0-9].*") ? value : "";
        }
        Matcher inline = OB_VERSION_INLINE.matcher(text);
        if (inline.find()) {
            return "OceanBase " + inline.group(1);
        }
        return "";
    }

    private static String extractLabeledSection(String text, Pattern label) {
        Matcher matcher = label.matcher(text);
        if (!matcher.find()) {
            return "";
        }
        int end = firstLabelStart(text, matcher.end(), SECTION_END_LABELS);
        return text.substring(matcher.end(), end).trim();
    }

    private static String directEvidencePrefix(String text) {
        int end = firstLabelStart(text, 0, Arrays.asList(ROOT_CAUSE_LABEL, ADVICE_LABEL));
        return text.substring(0, end);
    }

    private static String extractStatements(String text, Pattern statement) {
        List<String> statements = new ArrayList<String>();
        Matcher matcher = statement.matcher(text);
        while (matcher.find()) {
            String value = matcher.group().trim();
            if (!value.isEmpty()) {
                statements.add(value);
            }
        }
        return join(statements);
    }

    private static PlanExtraction extractPlan(String text, List<String> warnings) {
        Matcher planLabel = PLAN_LABEL.matcher(text);
        if (!planLabel.find()) {
            warnings.add("报告未提供可识别的文本执行计划，请补充 EXPLAIN。");
            return new PlanExtraction("", "");
        }

        String planBody = extractLabeledSection(text, PLAN_LABEL);
        List<String> explainLines = new ArrayList<String>();
        List<String> tableStatLines = new ArrayList<String>();
        for (String line : planBody.split("\\n")) {
            Matcher rowCount = TABLE_ROW_COUNT.matcher(line);
            if (rowCount.matches()) {
                tableStatLines.add(rowCount.group(1) + ": " + rowCount.group(2).replace(",", "") + " 行");
            } else if (!line.trim().isEmpty()) {
                explainLines.add(line.trim());
            }
        }

        String planCandidate = join(explainLines);
        String explainText = PLAN_OPERATOR.matcher(planCandidate).find() ? planCandidate : "";
        if (explainText.isEmpty()) {
            if (!tableStatLines.isEmpty()) {
                warnings.add("执行计划部分仅包含表行数查询，不包含计划算子；请补充文本 EXPLAIN。");
            } else {
                warnings.add("执行计划部分未包含可识别的计划算子，请补充文本 EXPLAIN。");
            }
        }
        return new PlanExtraction(explainText, join(tableStatLines));
    }

    private static String joinNonEmpty(String left, String right) {
        if (!hasText(left)) {
            return right;
        }
        if (!hasText(right) || left.equals(right)) {
            return left;
        }
        return left + "\n" + right;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String join(List<String> lines) {
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line);
        }
        return builder.toString();
    }

    private static String joinSections(List<String> sections) {
        StringBuilder builder = new StringBuilder();
        for (String section : sections) {
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(section);
        }
        return builder.toString();
    }

    private static final class PlanExtraction {
        private final String explainText;
        private final String tableStatsText;

        private PlanExtraction(String explainText, String tableStatsText) {
            this.explainText = explainText;
            this.tableStatsText = tableStatsText;
        }
    }
}
