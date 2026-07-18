package com.codex.sqltuner.tuning;

import com.codex.sqltuner.tuning.result.ContextAssessment;
import com.codex.sqltuner.tuning.result.AnalysisNarrative;
import com.codex.sqltuner.tuning.result.Diagnosis;
import com.codex.sqltuner.tuning.result.EvidenceItem;
import com.codex.sqltuner.tuning.result.IndexCandidate;
import com.codex.sqltuner.tuning.result.ReviewResult;
import com.codex.sqltuner.tuning.result.RewriteCandidate;
import com.codex.sqltuner.tuning.result.ValidationStep;

import java.util.ArrayList;
import java.util.List;

public class TuningResult {
    private String outcome;
    private String summary;
    private AnalysisNarrative analysisNarrative;
    private ContextAssessment contextAssessment;
    private List<EvidenceItem> evidenceCatalog = new ArrayList<EvidenceItem>();
    private List<Diagnosis> diagnoses = new ArrayList<Diagnosis>();
    private List<RewriteCandidate> rewriteCandidates = new ArrayList<RewriteCandidate>();
    private List<IndexCandidate> indexCandidates = new ArrayList<IndexCandidate>();
    private List<ValidationStep> validationPlan = new ArrayList<ValidationStep>();
    private List<String> missingInformation = new ArrayList<String>();
    private List<String> safetyWarnings = new ArrayList<String>();
    private ReviewResult review;
    private List<ResultFinding> findings = new ArrayList<ResultFinding>();
    private String rewriteSql;
    private List<IndexSuggestion> indexSuggestions = new ArrayList<IndexSuggestion>();
    private List<String> validationSteps = new ArrayList<String>();
    private List<String> riskWarnings = new ArrayList<String>();
    private List<String> needMoreInfo = new ArrayList<String>();
    private String rawModelOutput;
    private boolean mockModel;

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public AnalysisNarrative getAnalysisNarrative() {
        return analysisNarrative;
    }

    public void setAnalysisNarrative(AnalysisNarrative analysisNarrative) {
        this.analysisNarrative = analysisNarrative;
    }

    public ContextAssessment getContextAssessment() {
        return contextAssessment;
    }

    public void setContextAssessment(ContextAssessment contextAssessment) {
        this.contextAssessment = contextAssessment;
    }

    public List<EvidenceItem> getEvidenceCatalog() {
        return evidenceCatalog;
    }

    public void setEvidenceCatalog(List<EvidenceItem> evidenceCatalog) {
        this.evidenceCatalog = evidenceCatalog == null ? new ArrayList<EvidenceItem>() : evidenceCatalog;
    }

    public List<Diagnosis> getDiagnoses() {
        return diagnoses;
    }

    public void setDiagnoses(List<Diagnosis> diagnoses) {
        this.diagnoses = diagnoses == null ? new ArrayList<Diagnosis>() : diagnoses;
    }

    public List<RewriteCandidate> getRewriteCandidates() {
        return rewriteCandidates;
    }

    public void setRewriteCandidates(List<RewriteCandidate> rewriteCandidates) {
        this.rewriteCandidates = rewriteCandidates == null ? new ArrayList<RewriteCandidate>() : rewriteCandidates;
    }

    public List<IndexCandidate> getIndexCandidates() {
        return indexCandidates;
    }

    public void setIndexCandidates(List<IndexCandidate> indexCandidates) {
        this.indexCandidates = indexCandidates == null ? new ArrayList<IndexCandidate>() : indexCandidates;
    }

    public List<ValidationStep> getValidationPlan() {
        return validationPlan;
    }

    public void setValidationPlan(List<ValidationStep> validationPlan) {
        this.validationPlan = validationPlan == null ? new ArrayList<ValidationStep>() : validationPlan;
    }

    public List<String> getMissingInformation() {
        return missingInformation;
    }

    public void setMissingInformation(List<String> missingInformation) {
        this.missingInformation = missingInformation == null ? new ArrayList<String>() : missingInformation;
    }

    public List<String> getSafetyWarnings() {
        return safetyWarnings;
    }

    public void setSafetyWarnings(List<String> safetyWarnings) {
        this.safetyWarnings = safetyWarnings == null ? new ArrayList<String>() : safetyWarnings;
    }

    public ReviewResult getReview() {
        return review;
    }

    public void setReview(ReviewResult review) {
        this.review = review;
    }

    public List<ResultFinding> getFindings() {
        return findings;
    }

    public void setFindings(List<ResultFinding> findings) {
        this.findings = findings == null ? new ArrayList<ResultFinding>() : findings;
    }

    public String getRewriteSql() {
        return rewriteSql;
    }

    public void setRewriteSql(String rewriteSql) {
        this.rewriteSql = rewriteSql;
    }

    public List<IndexSuggestion> getIndexSuggestions() {
        return indexSuggestions;
    }

    public void setIndexSuggestions(List<IndexSuggestion> indexSuggestions) {
        this.indexSuggestions = indexSuggestions == null ? new ArrayList<IndexSuggestion>() : indexSuggestions;
    }

    public List<String> getValidationSteps() {
        return validationSteps;
    }

    public void setValidationSteps(List<String> validationSteps) {
        this.validationSteps = validationSteps == null ? new ArrayList<String>() : validationSteps;
    }

    public List<String> getRiskWarnings() {
        return riskWarnings;
    }

    public void setRiskWarnings(List<String> riskWarnings) {
        this.riskWarnings = riskWarnings == null ? new ArrayList<String>() : riskWarnings;
    }

    public List<String> getNeedMoreInfo() {
        return needMoreInfo;
    }

    public void setNeedMoreInfo(List<String> needMoreInfo) {
        this.needMoreInfo = needMoreInfo == null ? new ArrayList<String>() : needMoreInfo;
    }

    public String getRawModelOutput() {
        return rawModelOutput;
    }

    public void setRawModelOutput(String rawModelOutput) {
        this.rawModelOutput = rawModelOutput;
    }

    public boolean isMockModel() {
        return mockModel;
    }

    public void setMockModel(boolean mockModel) {
        this.mockModel = mockModel;
    }
}
