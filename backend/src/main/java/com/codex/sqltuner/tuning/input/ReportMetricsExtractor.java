package com.codex.sqltuner.tuning.input;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ReportMetricsExtractor {
    private static final Pattern SQL_ID_LABEL = Pattern.compile("(?i)(?<![A-Za-z0-9_])SQL\\s*ID\\s*[:：]\\s*");
    static final Pattern EXECUTIONS_LABEL = Pattern.compile("(?<!\\S)执行次数\\s*[:：]\\s*");
    static final Pattern CPU_LABEL = Pattern.compile("(?i)(?<!\\S)CPU\\s*占比\\s*[:：]\\s*");
    static final Pattern DURATION_LABEL = Pattern.compile("(?<!\\S)平均耗时\\s*[:：]\\s*");
    static final Pattern RUNTIME_METRICS_LABEL = Pattern.compile(
            "(?im)^[\\t ]*(?:运行(?:性能)?指标|性能指标|监控指标|RUNTIME[\\t ]+METRICS?)[\\t ]*(?:[:：][\\t ]*|(?:\\r?\\n|$))");
    static final Pattern AVERAGE_ROWS_FIELD = Pattern.compile(
            "(?im)^[\\t ]*(?:平均返回行数|(?:AVG(?:ERAGE)?[\\t ]+)?ROWS?[\\t ]+RETURNED)"
                    + "[\\t ]*(?:[:：=][\\t ]*)?(?:仅|约|为)?[\\t ]*(?=[0-9])");
    static final Pattern LOGICAL_READS_FIELD = Pattern.compile(
            "(?im)^[\\t ]*(?:逻辑读(?:取|次数|块数)?|LOGICAL[\\t ]+READS?)"
                    + "[\\t ]*(?:[:：=][\\t ]*)?(?:约|为)?[\\t ]*(?=[0-9])");
    static final Pattern PHYSICAL_READS_FIELD = Pattern.compile(
            "(?im)^[\\t ]*(?:物理读(?:取|次数|块数)?|PHYSICAL[\\t ]+READS?)"
                    + "[\\t ]*(?:[:：=][\\t ]*)?(?:约|为)?[\\t ]*(?=[0-9])");
    private static final Pattern AVERAGE_ROWS_VALUE = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])(?:平均返回行数|(?:AVG(?:ERAGE)?\\s+)?ROWS?\\s+RETURNED)"
                    + "\\s*(?:[:：=]\\s*)?(?:仅|约|为)?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(行|ROWS?)?");
    private static final Pattern LOGICAL_READS_VALUE = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])(?:逻辑读(?:取|次数|块数)?|LOGICAL\\s+READS?)"
                    + "\\s*(?:[:：=]\\s*)?(?:约|为)?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(次|页|块|KB|MB|GB)?");
    private static final Pattern PHYSICAL_READS_VALUE = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])(?:物理读(?:取|次数|块数)?|PHYSICAL\\s+READS?)"
                    + "\\s*(?:[:：=]\\s*)?(?:约|为)?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(次|页|块|KB|MB|GB)?");

    ReportMetricExtraction extract(String text,
                                   String reportMetricText,
                                   ReportSectionExtractor sectionExtractor,
                                   List<String> warnings) {
        String trustedMetricText = sectionExtractor.directEvidencePrefix(reportMetricText);
        MetricValue sqlId = extractSqlIdMetric(reportMetricText, trustedMetricText);
        MetricValue executions = extractLabeledMetric(reportMetricText, trustedMetricText, EXECUTIONS_LABEL, Arrays.asList(
                CPU_LABEL, DURATION_LABEL, AVERAGE_ROWS_FIELD, LOGICAL_READS_FIELD, PHYSICAL_READS_FIELD,
                ReportSectionExtractor.ROOT_CAUSE_LABEL, ReportSectionExtractor.THROTTLE_LABEL,
                ReportSectionExtractor.ADVICE_LABEL, ReportSectionExtractor.PLAN_LABEL,
                ReportSectionExtractor.SCHEMA_LABEL, ReportSectionExtractor.INDEX_LABEL,
                ReportSectionExtractor.TABLE_STATS_LABEL, ReportSectionExtractor.OB_VERSION_LABEL,
                ReportSectionExtractor.BUSINESS_INVARIANTS_LABEL, ReportSectionExtractor.ALLOWED_ACTIONS_LABEL));
        MetricValue cpu = extractLabeledMetric(reportMetricText, trustedMetricText, CPU_LABEL, Arrays.asList(
                DURATION_LABEL, AVERAGE_ROWS_FIELD, LOGICAL_READS_FIELD, PHYSICAL_READS_FIELD,
                ReportSectionExtractor.ROOT_CAUSE_LABEL, ReportSectionExtractor.THROTTLE_LABEL,
                ReportSectionExtractor.ADVICE_LABEL, ReportSectionExtractor.PLAN_LABEL,
                ReportSectionExtractor.SCHEMA_LABEL, ReportSectionExtractor.INDEX_LABEL,
                ReportSectionExtractor.TABLE_STATS_LABEL, ReportSectionExtractor.OB_VERSION_LABEL,
                ReportSectionExtractor.BUSINESS_INVARIANTS_LABEL, ReportSectionExtractor.ALLOWED_ACTIONS_LABEL));
        MetricValue duration = extractLabeledMetric(reportMetricText, trustedMetricText, DURATION_LABEL, Arrays.asList(
                AVERAGE_ROWS_FIELD, LOGICAL_READS_FIELD, PHYSICAL_READS_FIELD, ReportSectionExtractor.ROOT_CAUSE_LABEL,
                ReportSectionExtractor.THROTTLE_LABEL, ReportSectionExtractor.ADVICE_LABEL,
                ReportSectionExtractor.PLAN_LABEL, ReportSectionExtractor.SCHEMA_LABEL,
                ReportSectionExtractor.INDEX_LABEL, ReportSectionExtractor.TABLE_STATS_LABEL,
                ReportSectionExtractor.OB_VERSION_LABEL, ReportSectionExtractor.BUSINESS_INVARIANTS_LABEL,
                ReportSectionExtractor.ALLOWED_ACTIONS_LABEL));
        MetricValue averageRows = extractMetricValue(reportMetricText, trustedMetricText, AVERAGE_ROWS_VALUE, "行");
        MetricValue logicalReads = extractMetricValue(reportMetricText, trustedMetricText, LOGICAL_READS_VALUE, "");
        MetricValue physicalReads = extractMetricValue(reportMetricText, trustedMetricText, PHYSICAL_READS_VALUE, "");
        String rootCause = sectionExtractor.extractValue(text, ReportSectionExtractor.ROOT_CAUSE_LABEL, Arrays.asList(
                ReportSectionExtractor.THROTTLE_LABEL, ReportSectionExtractor.ADVICE_LABEL,
                ReportSectionExtractor.PLAN_LABEL, ReportSectionExtractor.SCHEMA_LABEL,
                ReportSectionExtractor.INDEX_LABEL, ReportSectionExtractor.TABLE_STATS_LABEL,
                ReportSectionExtractor.OB_VERSION_LABEL, ReportSectionExtractor.BUSINESS_INVARIANTS_LABEL,
                ReportSectionExtractor.ALLOWED_ACTIONS_LABEL));
        MetricValue throttle = extractLabeledMetric(reportMetricText, trustedMetricText,
                ReportSectionExtractor.THROTTLE_LABEL, Arrays.asList(
                        ReportSectionExtractor.ADVICE_LABEL, ReportSectionExtractor.PLAN_LABEL,
                        ReportSectionExtractor.SCHEMA_LABEL, ReportSectionExtractor.INDEX_LABEL,
                        ReportSectionExtractor.TABLE_STATS_LABEL, ReportSectionExtractor.OB_VERSION_LABEL,
                        ReportSectionExtractor.BUSINESS_INVARIANTS_LABEL,
                        ReportSectionExtractor.ALLOWED_ACTIONS_LABEL));
        String advice = sectionExtractor.extractValue(text, ReportSectionExtractor.ADVICE_LABEL, Arrays.asList(
                ReportSectionExtractor.PLAN_LABEL, ReportSectionExtractor.SCHEMA_LABEL,
                ReportSectionExtractor.INDEX_LABEL, ReportSectionExtractor.TABLE_STATS_LABEL,
                ReportSectionExtractor.OB_VERSION_LABEL, ReportSectionExtractor.BUSINESS_INVARIANTS_LABEL,
                ReportSectionExtractor.ALLOWED_ACTIONS_LABEL));
        return new ReportMetricExtraction(
                buildRuntimeMetrics(sqlId, executions, cpu, duration, averageRows, logicalReads, physicalReads, throttle),
                buildPriorAnalysis(rootCause, advice, warnings));
    }

    private MetricValue extractSqlIdMetric(String text, String trustedText) {
        String trusted = extractSqlId(trustedText);
        return ReportParsingSupport.hasText(trusted)
                ? new MetricValue(trusted, false)
                : new MetricValue(extractSqlId(text), true);
    }

    private String extractSqlId(String text) {
        Matcher matcher = SQL_ID_LABEL.matcher(text);
        if (!matcher.find()) {
            return "";
        }
        int end = ReportParsingSupport.firstLabelStart(text, matcher.end(),
                Arrays.asList(ReportSqlExtractor.SQL_LABEL, EXECUTIONS_LABEL));
        return ReportParsingSupport.cleanValue(text.substring(matcher.end(), end));
    }

    private MetricValue extractLabeledMetric(String text,
                                              String trustedText,
                                              Pattern label,
                                              List<Pattern> endLabels) {
        String trusted = extractValue(trustedText, label, endLabels);
        return ReportParsingSupport.hasText(trusted)
                ? new MetricValue(trusted, false)
                : new MetricValue(extractValue(text, label, endLabels), true);
    }

    private String extractValue(String text, Pattern label, List<Pattern> endLabels) {
        Matcher matcher = label.matcher(text);
        if (!matcher.find()) {
            return "";
        }
        int end = ReportParsingSupport.firstLabelStart(text, matcher.end(), endLabels);
        return ReportParsingSupport.cleanValue(text.substring(matcher.end(), end));
    }

    private MetricValue extractMetricValue(String text,
                                            String trustedText,
                                            Pattern pattern,
                                            String defaultUnit) {
        String trusted = extractNumericMetricValue(trustedText, pattern, defaultUnit);
        return ReportParsingSupport.hasText(trusted)
                ? new MetricValue(trusted, false)
                : new MetricValue(extractNumericMetricValue(text, pattern, defaultUnit), true);
    }

    private String extractNumericMetricValue(String text, Pattern pattern, String defaultUnit) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return "";
        }
        String number = matcher.group(1).replace(",", "");
        String unit = matcher.groupCount() > 1 ? matcher.group(2) : "";
        if (!ReportParsingSupport.hasText(unit)) {
            unit = defaultUnit;
        } else if ("row".equalsIgnoreCase(unit) || "rows".equalsIgnoreCase(unit)) {
            unit = "行";
        }
        return ReportParsingSupport.hasText(unit) ? number + " " + unit : number;
    }

    private String buildRuntimeMetrics(MetricValue sqlId,
                                       MetricValue executions,
                                       MetricValue cpu,
                                       MetricValue duration,
                                       MetricValue averageRows,
                                       MetricValue logicalReads,
                                       MetricValue physicalReads,
                                       MetricValue throttle) {
        List<String> lines = new ArrayList<String>();
        addMetric(lines, "SQL ID", sqlId);
        addMetric(lines, "执行次数", executions);
        addMetric(lines, "CPU 占比", cpu);
        addMetric(lines, "平均耗时", duration);
        addMetric(lines, "平均返回行数", averageRows);
        addMetric(lines, "逻辑读", logicalReads);
        addMetric(lines, "物理读", physicalReads);
        addMetric(lines, "限流值", throttle);
        return ReportParsingSupport.join(lines);
    }

    private void addMetric(List<String> lines, String label, MetricValue metric) {
        if (ReportParsingSupport.hasText(metric.value)) {
            String visibleLabel = metric.unverifiedReportClaim
                    ? label + ReportTextParser.UNVERIFIED_REPORT_METRIC_MARKER
                    : label;
            lines.add(visibleLabel + ": " + metric.value);
        }
    }

    private String buildPriorAnalysis(String rootCause, String advice, List<String> warnings) {
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
        return ReportParsingSupport.joinSections(sections);
    }

    private static final class MetricValue {
        private final String value;
        private final boolean unverifiedReportClaim;

        private MetricValue(String value, boolean unverifiedReportClaim) {
            this.value = value == null ? "" : value;
            this.unverifiedReportClaim = unverifiedReportClaim;
        }
    }
}
