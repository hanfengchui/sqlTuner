package com.codex.sqltuner.tuning.accuracy;

import com.codex.sqltuner.tuning.TuningResult;
import com.codex.sqltuner.tuning.result.Diagnosis;
import com.codex.sqltuner.tuning.result.IndexCandidate;

import java.util.Locale;

final class ConfidencePolicy {
    void capToContext(TuningResult result, ContextPackage context) {
        String maxConfidence = context.getAssessment() != null
                && ResultTextSafetyValidator.hasText(context.getAssessment().getMaxConfidence())
                ? context.getAssessment().getMaxConfidence().trim().toUpperCase(Locale.ROOT)
                : (context.isAllowHighConfidence() ? "HIGH" : "MEDIUM");
        for (Diagnosis diagnosis : result.getDiagnoses()) {
            diagnosis.setConfidence(cappedConfidence(diagnosis.getConfidence(), maxConfidence));
        }
        for (IndexCandidate candidate : result.getIndexCandidates()) {
            candidate.setConfidence(cappedConfidence(candidate.getConfidence(), maxConfidence));
        }
    }

    private String cappedConfidence(String confidence, String maxConfidence) {
        if (!ResultTextSafetyValidator.hasText(confidence)
                || confidenceRank(confidence) <= confidenceRank(maxConfidence)) {
            return confidence;
        }
        return maxConfidence;
    }

    private int confidenceRank(String confidence) {
        if ("HIGH".equalsIgnoreCase(confidence)) {
            return 3;
        }
        if ("MEDIUM".equalsIgnoreCase(confidence)) {
            return 2;
        }
        if ("LOW".equalsIgnoreCase(confidence)) {
            return 1;
        }
        return 0;
    }
}
