package com.codex.sqltuner.tuning.input;

final class ReportPlanExtraction {
    private final String explainText;
    private final String tableStatsText;

    ReportPlanExtraction(String explainText, String tableStatsText) {
        this.explainText = explainText;
        this.tableStatsText = tableStatsText;
    }

    String getExplainText() {
        return explainText;
    }

    String getTableStatsText() {
        return tableStatsText;
    }
}
