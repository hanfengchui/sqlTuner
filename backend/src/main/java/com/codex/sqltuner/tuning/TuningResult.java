package com.codex.sqltuner.tuning;

import java.util.ArrayList;
import java.util.List;

public class TuningResult {
    private String summary;
    private List<ResultFinding> findings = new ArrayList<ResultFinding>();
    private String rewriteSql;
    private List<IndexSuggestion> indexSuggestions = new ArrayList<IndexSuggestion>();
    private List<String> validationSteps = new ArrayList<String>();
    private List<String> riskWarnings = new ArrayList<String>();
    private List<String> needMoreInfo = new ArrayList<String>();
    private String rawModelOutput;
    private boolean mockModel;

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<ResultFinding> getFindings() {
        return findings;
    }

    public void setFindings(List<ResultFinding> findings) {
        this.findings = findings;
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
        this.indexSuggestions = indexSuggestions;
    }

    public List<String> getValidationSteps() {
        return validationSteps;
    }

    public void setValidationSteps(List<String> validationSteps) {
        this.validationSteps = validationSteps;
    }

    public List<String> getRiskWarnings() {
        return riskWarnings;
    }

    public void setRiskWarnings(List<String> riskWarnings) {
        this.riskWarnings = riskWarnings;
    }

    public List<String> getNeedMoreInfo() {
        return needMoreInfo;
    }

    public void setNeedMoreInfo(List<String> needMoreInfo) {
        this.needMoreInfo = needMoreInfo;
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
