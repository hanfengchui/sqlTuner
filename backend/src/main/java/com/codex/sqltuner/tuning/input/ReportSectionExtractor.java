package com.codex.sqltuner.tuning.input;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ReportSectionExtractor {
    static final Pattern ROOT_CAUSE_LABEL = Pattern.compile("(?<!\\S)根因\\s*[:：]\\s*");
    static final Pattern THROTTLE_LABEL = Pattern.compile("(?<!\\S)限流值\\s*[:：]\\s*");
    static final Pattern ADVICE_LABEL = Pattern.compile("(?<!\\S)优化建议\\s*[:：]\\s*");
    static final Pattern PLAN_LABEL = Pattern.compile(
            "(?im)^[\\t ]*(?:执行计划(?:文本)?|EXPLAIN(?:\\s+PLAN)?|PLAN)[\\t ]*(?:[:：][\\t ]*|(?:\\r?\\n|$))");
    static final Pattern SCHEMA_LABEL = Pattern.compile(
            "(?im)^[\\t ]*(?:表结构|表定义|建表语句|表\\s*DDL|SCHEMA|TABLE\\s+DDL)[\\t ]*(?:[:：][\\t ]*|(?:\\r?\\n|$))");
    static final Pattern INDEX_LABEL = Pattern.compile(
            "(?im)^[\\t ]*(?:现有[\\t ]*)?(?:索引(?:定义|信息|列表)?|INDEX(?:ES)?|INDEX[\\t ]+DEFINITIONS?|SHOW[\\t ]+INDEX(?:ES)?)[\\t ]*(?:[:：][\\t ]*|(?:\\r?\\n|$))");
    static final Pattern TABLE_STATS_LABEL = Pattern.compile(
            "(?im)^[\\t ]*(?:表统计(?:信息)?|表行数|TABLE[\\t ]+STATS?)[\\t ]*(?:[:：][\\t ]*|(?:\\r?\\n|$))");
    static final Pattern OB_VERSION_LABEL = Pattern.compile(
            "(?im)^[\\t ]*(?:(?:OCEANBASE|OB)[\\t ]*)?(?:数据库[\\t ]*)?(?:版本|VERSION)[\\t ]*[:：][\\t ]*([^\\r\\n]+)");
    private static final Pattern OB_VERSION_INLINE = Pattern.compile(
            "(?i)\\b(?:OCEANBASE|OB)[\\t ]*(?:(?:CE|EE)[\\t ]*)?(?:VERSION[\\t ]*)?[vV]?([0-9]+(?:\\.[0-9]+){1,5}(?:[-_][A-Za-z0-9.]+)?)");
    static final Pattern BUSINESS_INVARIANTS_LABEL = Pattern.compile(
            "(?im)^[\\t ]*(?:业务(?:语义)?约束|业务规则|语义约束|BUSINESS[\\t ]+(?:INVARIANTS?|RULES?))[\\t ]*(?:[:：][\\t ]*|(?:\\r?\\n|$))");
    static final Pattern ALLOWED_ACTIONS_LABEL = Pattern.compile(
            "(?im)^[\\t ]*(?:允许(?:的)?(?:建议|操作)(?:类型)?|调优范围|ALLOWED[\\t ]+ACTIONS?)[\\t ]*(?:[:：][\\t ]*|(?:\\r?\\n|$))");
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
                    + "\\bSUBPLAN\\b|\\bEST\\.?\\s*ROWS\\b|\\bSORT\\b|\\bGROUP\\s+BY\\b|"
                    + "\\bSELECT_TYPE\\b|\\bPOSSIBLE_KEYS\\b|\\bACCESS_TYPE\\b|\\bROWS_EXAMINED_PER_SCAN\\b|"
                    + "\\bUSING\\s+(?:WHERE|FILESORT|TEMPORARY|INDEX(?:\\s+CONDITION)?)\\b)");

    List<Pattern> sqlEndLabels() {
        return Arrays.asList(
                ReportMetricsExtractor.RUNTIME_METRICS_LABEL, ReportMetricsExtractor.EXECUTIONS_LABEL,
                ReportMetricsExtractor.CPU_LABEL, ReportMetricsExtractor.DURATION_LABEL,
                ReportMetricsExtractor.AVERAGE_ROWS_FIELD, ReportMetricsExtractor.LOGICAL_READS_FIELD,
                ReportMetricsExtractor.PHYSICAL_READS_FIELD, ROOT_CAUSE_LABEL, THROTTLE_LABEL, ADVICE_LABEL,
                PLAN_LABEL, SCHEMA_LABEL, INDEX_LABEL, TABLE_STATS_LABEL, OB_VERSION_LABEL,
                BUSINESS_INVARIANTS_LABEL, ALLOWED_ACTIONS_LABEL);
    }

    String extractSchema(String text) {
        String labeled = extractLabeledSection(text, SCHEMA_LABEL);
        if (CREATE_TABLE_STATEMENT.matcher(labeled).find()) {
            return labeled;
        }
        return extractStatements(directEvidencePrefix(text), CREATE_TABLE_STATEMENT);
    }

    String extractIndexText(String text, String schemaText) {
        String labeled = extractLabeledSection(text, INDEX_LABEL);
        if (ReportParsingSupport.hasText(labeled)) {
            return labeled;
        }
        if (INDEX_DEFINITION.matcher(schemaText).find()) {
            // 建表 DDL 中的主键和索引同样是当前索引证据，无需要求用户重复粘贴。
            return schemaText;
        }
        return extractStatements(directEvidencePrefix(text), CREATE_INDEX_STATEMENT);
    }

    String extractTableStats(String text) {
        return extractLabeledSection(text, TABLE_STATS_LABEL);
    }

    String extractObVersion(String text) {
        Matcher labeled = OB_VERSION_LABEL.matcher(text);
        if (labeled.find()) {
            String value = ReportParsingSupport.cleanValue(labeled.group(1));
            return value.matches(".*[0-9].*") ? value : "";
        }
        Matcher inline = OB_VERSION_INLINE.matcher(text);
        if (inline.find()) {
            return "OceanBase " + inline.group(1);
        }
        return "";
    }

    String extractBusinessInvariants(String text) {
        return extractLabeledSection(text, BUSINESS_INVARIANTS_LABEL);
    }

    List<String> extractAllowedActions(String text) {
        String section = extractLabeledSection(text, ALLOWED_ACTIONS_LABEL);
        if (!ReportParsingSupport.hasText(section)) {
            return Collections.emptyList();
        }
        List<String> actions = new ArrayList<String>();
        for (String rawToken : section.split("[,，、;；\\s]+")) {
            String token = rawToken.replaceAll("^[*\\-]+", "").trim().toLowerCase(Locale.ROOT);
            if (token.isEmpty()) {
                continue;
            }
            String action = normalizedAllowedAction(token);
            if (action == null) {
                throw new IllegalArgumentException("允许建议类型包含不支持的值: " + rawToken);
            }
            if ("all".equals(action)) {
                addAllowedAction(actions, "diagnosis");
                addAllowedAction(actions, "index");
                addAllowedAction(actions, "rewrite");
                addAllowedAction(actions, "validation");
                continue;
            }
            addAllowedAction(actions, action);
        }
        if (actions.isEmpty()) {
            throw new IllegalArgumentException("允许建议类型不能为空");
        }
        return actions;
    }

    ReportPlanExtraction extractPlan(String text, List<String> warnings, boolean warnWhenMissing) {
        Matcher planLabel = PLAN_LABEL.matcher(text);
        if (!planLabel.find()) {
            if (warnWhenMissing) {
                warnings.add("报告未提供可识别的文本执行计划，请补充 EXPLAIN。");
            }
            return new ReportPlanExtraction("", "");
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

        String planCandidate = ReportParsingSupport.join(explainLines);
        String explainText = PLAN_OPERATOR.matcher(planCandidate).find() ? planCandidate : "";
        if (explainText.isEmpty()) {
            if (!tableStatLines.isEmpty()) {
                warnings.add("执行计划部分仅包含表行数查询，不包含计划算子；请补充文本 EXPLAIN。");
            } else {
                warnings.add("执行计划部分未包含可识别的计划算子，请补充文本 EXPLAIN。");
            }
        }
        return new ReportPlanExtraction(explainText, ReportParsingSupport.join(tableStatLines));
    }

    String extractValue(String text, Pattern label, List<Pattern> endLabels) {
        Matcher matcher = label.matcher(text);
        if (!matcher.find()) {
            return "";
        }
        int end = ReportParsingSupport.firstLabelStart(text, matcher.end(), endLabels);
        return ReportParsingSupport.cleanValue(text.substring(matcher.end(), end));
    }

    String extractLabeledSection(String text, Pattern label) {
        Matcher matcher = label.matcher(text);
        if (!matcher.find()) {
            return "";
        }
        int end = ReportParsingSupport.firstLabelStart(text, matcher.end(), sectionEndLabels());
        return text.substring(matcher.end(), end).trim();
    }

    String directEvidencePrefix(String text) {
        int end = ReportParsingSupport.firstLabelStart(text, 0, Arrays.asList(
                ROOT_CAUSE_LABEL, ADVICE_LABEL, BUSINESS_INVARIANTS_LABEL, ALLOWED_ACTIONS_LABEL));
        return text.substring(0, end);
    }

    private List<Pattern> sectionEndLabels() {
        return Arrays.asList(ReportSqlExtractor.SQL_LABEL, ReportMetricsExtractor.RUNTIME_METRICS_LABEL,
                ReportMetricsExtractor.EXECUTIONS_LABEL, ReportMetricsExtractor.CPU_LABEL,
                ReportMetricsExtractor.DURATION_LABEL, ROOT_CAUSE_LABEL, THROTTLE_LABEL, ADVICE_LABEL,
                PLAN_LABEL, SCHEMA_LABEL, INDEX_LABEL, TABLE_STATS_LABEL, OB_VERSION_LABEL,
                BUSINESS_INVARIANTS_LABEL, ALLOWED_ACTIONS_LABEL);
    }

    private String extractStatements(String text, Pattern statement) {
        List<String> statements = new ArrayList<String>();
        Matcher matcher = statement.matcher(text);
        while (matcher.find()) {
            String value = matcher.group().trim();
            if (!value.isEmpty()) {
                statements.add(value);
            }
        }
        return ReportParsingSupport.join(statements);
    }

    private void addAllowedAction(List<String> actions, String action) {
        if (!actions.contains(action)) {
            actions.add(action);
        }
    }

    private String normalizedAllowedAction(String token) {
        if ("全部".equals(token) || "all".equals(token)) {
            return "all";
        }
        if ("诊断".equals(token) || "diagnosis".equals(token)) {
            return "diagnosis";
        }
        if ("索引".equals(token) || "index".equals(token)) {
            return "index";
        }
        if ("改写".equals(token) || "rewrite".equals(token)) {
            return "rewrite";
        }
        if ("验证".equals(token) || "validation".equals(token)) {
            return "validation";
        }
        return null;
    }
}
