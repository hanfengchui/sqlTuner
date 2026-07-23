package com.codex.sqltuner.tuning.input;

final class ReportEvidence {
    private final String runtimeMetricsText;
    private final String tableStatsText;
    private final String priorAnalysisText;
    private final String explainText;
    private final String schemaText;
    private final String indexText;
    private final String obVersion;

    ReportEvidence(String runtimeMetricsText,
                   String tableStatsText,
                   String priorAnalysisText,
                   String explainText,
                   String schemaText,
                   String indexText,
                   String obVersion) {
        this.runtimeMetricsText = runtimeMetricsText;
        this.tableStatsText = tableStatsText;
        this.priorAnalysisText = priorAnalysisText;
        this.explainText = explainText;
        this.schemaText = schemaText;
        this.indexText = indexText;
        this.obVersion = obVersion;
    }

    String getRuntimeMetricsText() {
        return runtimeMetricsText;
    }

    String getTableStatsText() {
        return tableStatsText;
    }

    String getPriorAnalysisText() {
        return priorAnalysisText;
    }

    String getExplainText() {
        return explainText;
    }

    String getSchemaText() {
        return schemaText;
    }

    String getIndexText() {
        return indexText;
    }

    String getObVersion() {
        return obVersion;
    }
}
