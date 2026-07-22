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
    private final String schemaText;
    private final String indexText;
    private final String obVersion;
    private final String businessInvariants;
    private final List<String> allowedActions;
    private final List<String> warnings;

    public ParsedReport(String extractedSql,
                        String inferredDialect,
                        String runtimeMetricsText,
                        String tableStatsText,
                        String priorAnalysisText,
                        String explainText,
                        String schemaText,
                        String indexText,
                        String obVersion,
                        String businessInvariants,
                        List<String> allowedActions,
                        List<String> warnings) {
        this.extractedSql = valueOrEmpty(extractedSql);
        this.inferredDialect = valueOrEmpty(inferredDialect);
        this.runtimeMetricsText = valueOrEmpty(runtimeMetricsText);
        this.tableStatsText = valueOrEmpty(tableStatsText);
        this.priorAnalysisText = valueOrEmpty(priorAnalysisText);
        this.explainText = valueOrEmpty(explainText);
        this.schemaText = valueOrEmpty(schemaText);
        this.indexText = valueOrEmpty(indexText);
        this.obVersion = valueOrEmpty(obVersion);
        this.businessInvariants = valueOrEmpty(businessInvariants);
        this.allowedActions = Collections.unmodifiableList(new ArrayList<String>(allowedActions == null
                ? Collections.<String>emptyList()
                : allowedActions));
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

    public String getSchemaText() {
        return schemaText;
    }

    public String getIndexText() {
        return indexText;
    }

    public String getObVersion() {
        return obVersion;
    }

    public String getBusinessInvariants() {
        return businessInvariants;
    }

    public List<String> getAllowedActions() {
        return allowedActions;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
