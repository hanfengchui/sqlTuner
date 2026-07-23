package com.codex.sqltuner.tuning.accuracy;

import com.codex.sqltuner.rule.SqlDialect;
import com.codex.sqltuner.tuning.TuningResult;
import com.codex.sqltuner.tuning.result.AnalysisNarrative;
import com.codex.sqltuner.tuning.result.Diagnosis;
import com.codex.sqltuner.tuning.result.EvidenceItem;
import com.codex.sqltuner.tuning.result.IndexCandidate;
import com.codex.sqltuner.tuning.result.NarrativeSection;
import com.codex.sqltuner.tuning.result.ValidationStep;

import java.util.Locale;
import java.util.Set;

final class ResultStructureValidator {
    private final EvidenceReferenceValidator evidenceReferenceValidator;
    private final ResultTextSafetyValidator textSafetyValidator;

    ResultStructureValidator(EvidenceReferenceValidator evidenceReferenceValidator,
                             ResultTextSafetyValidator textSafetyValidator) {
        this.evidenceReferenceValidator = evidenceReferenceValidator;
        this.textSafetyValidator = textSafetyValidator;
    }

    void validateRequiredFields(TuningResult result, ValidationOutcome outcome) {
        if (!"ADVICE".equals(result.getOutcome()) && !"NEEDS_INPUT".equals(result.getOutcome())) {
            outcome.reject("outcome 必须为 ADVICE 或 NEEDS_INPUT");
        }
        if (result.getContextAssessment() == null) {
            outcome.reject("缺少 contextAssessment");
        }
        if (result.getEvidenceCatalog() == null || result.getEvidenceCatalog().isEmpty()) {
            outcome.reject("缺少 evidenceCatalog");
        }
    }

    void validateSummaryNarrativeAndDiagnoses(TuningResult result,
                                              Set<String> evidenceIds,
                                              SqlDialect dialect,
                                              ContextPackage context,
                                              ValidationOutcome outcome) {
        textSafetyValidator.validateClaimText("summary", result.getSummary(), context, outcome);
        validateNarrative(result.getAnalysisNarrative(), evidenceIds, dialect, context, outcome);
        validateDiagnoses(result, evidenceIds, context, outcome);
    }

    void validateValidationPlan(TuningResult result, Set<String> evidenceIds, ValidationOutcome outcome) {
        if (result.getValidationPlan().isEmpty()) {
            outcome.reject("缺少 validationPlan");
        }
        for (ValidationStep step : result.getValidationPlan()) {
            if (!ResultTextSafetyValidator.hasText(step.getAction())) {
                outcome.reject("validationPlan 缺少 action");
            }
            evidenceReferenceValidator.validateRefs("validationPlan", step.getEvidenceRefs(), evidenceIds, outcome);
            textSafetyValidator.validatePerformancePromise(
                    "validationPlan", ResultTextSafetyValidator.combine(step.getAction(), step.getExpectedSignal()), outcome);
        }
    }

    void validateSupplementalText(TuningResult result,
                                  SqlDialect dialect,
                                  ContextPackage context,
                                  ValidationOutcome outcome) {
        for (String value : result.getMissingInformation()) {
            textSafetyValidator.validateDisplayText("missingInformation", value, dialect, context, outcome);
        }
        for (String value : result.getSafetyWarnings()) {
            textSafetyValidator.validateDisplayText("safetyWarnings", value, dialect, context, outcome);
        }
        for (EvidenceItem evidence : result.getEvidenceCatalog()) {
            textSafetyValidator.validateDisplayText("evidenceCatalog", evidence.getSummary(), dialect, context, outcome);
        }
        if (result.getReview() != null) {
            textSafetyValidator.validateDisplayText("review", result.getReview().getNotes(), dialect, context, outcome);
            for (String revision : result.getReview().getRevisions()) {
                textSafetyValidator.validateDisplayText("review", revision, dialect, context, outcome);
            }
        }
    }

    void enforceContextPermissions(TuningResult result, ContextPackage context, ValidationOutcome outcome) {
        if (!context.isAllowRewrite() && !result.getRewriteCandidates().isEmpty()) {
            outcome.reject("仅 SQL 场景禁止输出 rewriteCandidates");
        }
        if (!context.isAllowIndexDirection() && !result.getIndexCandidates().isEmpty()) {
            outcome.reject("缺少 schema/index 时禁止输出 indexCandidates");
        }
        if (!context.isAllowIndexDdl()) {
            for (IndexCandidate candidate : result.getIndexCandidates()) {
                candidate.setDdl("");
            }
        }
        if ("NEEDS_INPUT".equals(result.getOutcome()) && result.getMissingInformation().isEmpty()) {
            outcome.reject("NEEDS_INPUT 必须给出 missingInformation");
        }
    }

    private void validateNarrative(AnalysisNarrative narrative,
                                   Set<String> evidenceIds,
                                   SqlDialect dialect,
                                   ContextPackage context,
                                   ValidationOutcome outcome) {
        if (narrative == null || !ResultTextSafetyValidator.hasText(narrative.getConclusion())) {
            outcome.reject("缺少 analysisNarrative.conclusion");
            return;
        }
        if (narrative.getConclusion().length() > 480) {
            outcome.reject("analysisNarrative.conclusion 超过长度限制");
        }
        textSafetyValidator.validateNarrativeText(
                "analysisNarrative.conclusion", narrative.getConclusion(), dialect, context, outcome);
        if (narrative.getSections().isEmpty() || narrative.getSections().size() > 4) {
            outcome.reject("analysisNarrative.sections 必须包含 1 至 4 个阅读块");
        }
        for (NarrativeSection section : narrative.getSections()) {
            if (!ResultTextSafetyValidator.hasText(section.getKind())
                    || !ResultTextSafetyValidator.hasText(section.getTitle())
                    || !ResultTextSafetyValidator.hasText(section.getBody())) {
                outcome.reject("analysisNarrative.sections 缺少 kind/title/body");
                continue;
            }
            if (!isNarrativeKind(section.getKind())) {
                outcome.reject("analysisNarrative.sections 包含不支持的 kind: " + section.getKind());
            }
            if (section.getBody().length() > 600) {
                outcome.reject("analysisNarrative.sections.body 超过长度限制");
            }
            if (section.getTitle().length() > 60) {
                outcome.reject("analysisNarrative.sections.title 超过长度限制");
            }
            textSafetyValidator.validateNarrativeText("analysisNarrative.sections",
                    section.getTitle() + "\n" + section.getBody(), dialect, context, outcome);
            evidenceReferenceValidator.validateRefs(
                    "analysisNarrative.sections", section.getEvidenceRefs(), evidenceIds, outcome);
        }
    }

    private void validateDiagnoses(TuningResult result,
                                   Set<String> evidenceIds,
                                   ContextPackage context,
                                   ValidationOutcome outcome) {
        for (Diagnosis diagnosis : result.getDiagnoses()) {
            if (!ResultTextSafetyValidator.hasText(diagnosis.getTitle())
                    || !ResultTextSafetyValidator.hasText(diagnosis.getSeverity())
                    || !ResultTextSafetyValidator.hasText(diagnosis.getConfidence())) {
                outcome.reject("diagnoses 字段缺少 title/severity/confidence");
            }
            evidenceReferenceValidator.validateRefs("diagnoses", diagnosis.getEvidenceRefs(), evidenceIds, outcome);
            textSafetyValidator.validateClaimText("diagnoses", ResultTextSafetyValidator.combine(
                    diagnosis.getTitle(), diagnosis.getImpact(), diagnosis.getPrecondition()), context, outcome);
        }
    }

    private boolean isNarrativeKind(String kind) {
        String value = kind.trim().toUpperCase(Locale.ROOT);
        return "CONCLUSION".equals(value) || "EVIDENCE".equals(value) || "CAUTION".equals(value)
                || "ACTION".equals(value) || "VALIDATION".equals(value);
    }
}
