package com.codex.sqltuner.tuning.result;

import java.util.ArrayList;
import java.util.List;

public class RewriteCandidate {
    private String sql;
    private String change;
    private String semanticCheck;
    private String risk;
    private String validation;
    private List<String> evidenceRefs = new ArrayList<String>();

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    public String getChange() {
        return change;
    }

    public void setChange(String change) {
        this.change = change;
    }

    public String getSemanticCheck() {
        return semanticCheck;
    }

    public void setSemanticCheck(String semanticCheck) {
        this.semanticCheck = semanticCheck;
    }

    public String getRisk() {
        return risk;
    }

    public void setRisk(String risk) {
        this.risk = risk;
    }

    public String getValidation() {
        return validation;
    }

    public void setValidation(String validation) {
        this.validation = validation;
    }

    public List<String> getEvidenceRefs() {
        return evidenceRefs;
    }

    public void setEvidenceRefs(List<String> evidenceRefs) {
        this.evidenceRefs = evidenceRefs == null ? new ArrayList<String>() : evidenceRefs;
    }
}
