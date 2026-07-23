package com.codex.sqltuner.tuning.input;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class ReportTextParser {
    static final int MAX_INPUT_BYTES = 128 * 1024;
    public static final String UNVERIFIED_REPORT_METRIC_MARKER = "（报告结论内识别，待核验）";

    private final ReportSqlExtractor sqlExtractor = new ReportSqlExtractor();
    private final ReportSectionExtractor sectionExtractor = new ReportSectionExtractor();
    private final ReportMetricsExtractor metricsExtractor = new ReportMetricsExtractor();

    public ParsedReport parse(String input) {
        String text = validatedText(input);
        if (sqlExtractor.looksLikeSql(text)) {
            List<String> warnings = new ArrayList<String>();
            warnings.add("未识别到报告字段，已按纯 SQL 处理；请补充运行指标和文本 EXPLAIN。");
            return new ParsedReport(text.trim(), sqlExtractor.inferDialect(text), "", "", "", "", "", "", "", "",
                    Collections.<String>emptyList(), warnings);
        }

        ReportSqlExtractor.SqlExtraction sql = sqlExtractor.extractLabeledSql(text, sectionExtractor.sqlEndLabels());
        List<String> warnings = new ArrayList<String>();
        ReportEvidence evidence = parseEvidence(text, sql.reportMetricText(text), warnings, true);
        return new ParsedReport(sql.getSql(), sqlExtractor.inferDialect(sql.getSql()),
                evidence.getRuntimeMetricsText(), evidence.getTableStatsText(), evidence.getPriorAnalysisText(),
                evidence.getExplainText(), evidence.getSchemaText(), evidence.getIndexText(), evidence.getObVersion(),
                sectionExtractor.extractBusinessInvariants(text), sectionExtractor.extractAllowedActions(text), warnings);
    }

    /**
     * 解析后续补充材料时不返回 SQL，避免补充消息意外替换既有对话中的原 SQL。
     */
    public ParsedReport parseSupplement(String input) {
        String text = validatedText(input);
        List<String> warnings = new ArrayList<String>();
        ReportEvidence evidence = parseEvidence(text, text, warnings, false);
        return new ParsedReport("", "", evidence.getRuntimeMetricsText(), evidence.getTableStatsText(),
                evidence.getPriorAnalysisText(), evidence.getExplainText(), evidence.getSchemaText(), evidence.getIndexText(),
                evidence.getObVersion(), sectionExtractor.extractBusinessInvariants(text),
                sectionExtractor.extractAllowedActions(text), warnings);
    }

    private ReportEvidence parseEvidence(String text,
                                         String reportMetricText,
                                         List<String> warnings,
                                         boolean warnWhenPlanMissing) {
        ReportMetricExtraction metrics = metricsExtractor.extract(text, reportMetricText, sectionExtractor, warnings);
        ReportPlanExtraction plan = sectionExtractor.extractPlan(text, warnings, warnWhenPlanMissing);
        String schemaText = sectionExtractor.extractSchema(text);
        String indexText = sectionExtractor.extractIndexText(text, schemaText);
        String tableStats = ReportParsingSupport.joinNonEmpty(
                plan.getTableStatsText(), sectionExtractor.extractTableStats(text));
        return new ReportEvidence(metrics.getRuntimeMetricsText(), tableStats, metrics.getPriorAnalysisText(),
                plan.getExplainText(), schemaText, indexText, sectionExtractor.extractObVersion(text));
    }

    private static String validatedText(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("报告文本不能为空");
        }
        if (input.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("报告文本不能超过 128 KiB");
        }
        return input.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\u00A0', ' ')
                .trim();
    }
}
