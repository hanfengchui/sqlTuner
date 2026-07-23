package com.codex.sqltuner.tuning.input;

final class ReportMetricExtraction {
    private final String runtimeMetricsText;
    private final String priorAnalysisText;

    ReportMetricExtraction(String runtimeMetricsText, String priorAnalysisText) {
        this.runtimeMetricsText = runtimeMetricsText;
        this.priorAnalysisText = priorAnalysisText;
    }

    String getRuntimeMetricsText() {
        return runtimeMetricsText;
    }

    String getPriorAnalysisText() {
        return priorAnalysisText;
    }
}
