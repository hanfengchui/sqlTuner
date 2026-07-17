package com.codex.sqltuner.tuning.input;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ParsedReport {
    private final String extractedSql;
    private final String inferredDialect;
    private final String runtimeMetricsText;
    private final String tableStatsText;
    private final String priorAnalysisText;
    private final String explainText;
    private final List<String> warnings;

    public ParsedReport(String extractedSql,
                        String inferredDialect,
                        String runtimeMetricsText,
                        String tableStatsText,
                        String priorAnalysisText,
                        String explainText,
                        List<String> warnings) {
        this.extractedSql = valueOrEmpty(extractedSql);
        this.inferredDialect = valueOrEmpty(inferredDialect);
        this.runtimeMetricsText = valueOrEmpty(runtimeMetricsText);
        this.tableStatsText = valueOrEmpty(tableStatsText);
        this.priorAnalysisText = valueOrEmpty(priorAnalysisText);
        this.explainText = valueOrEmpty(explainText);
        this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings == null
                ? Collections.<String>emptyList()
                : warnings));
    }

    public String getExtractedSql() {
        return extractedSql;
    }

    public String getInferredDialect() {
        return inferredDialect;
    }

    public String getRuntimeMetricsText() {
        return runtimeMetricsText;
    }

    public String getTableStatsText() {
        return tableStatsText;
    }

    public String getPriorAnalysisText() {
        return priorAnalysisText;
    }

    public String getExplainText() {
        return explainText;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
