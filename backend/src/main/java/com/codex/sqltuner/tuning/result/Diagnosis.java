package com.codex.sqltuner.tuning.result;

import java.util.ArrayList;
import java.util.List;

public class Diagnosis {
    private String severity;
    private String title;
    private String impact;
    private String confidence;
    private String precondition;
    private List<String> evidenceRefs = new ArrayList<String>();

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getImpact() {
        return impact;
    }

    public void setImpact(String impact) {
        this.impact = impact;
    }

    public String getConfidence() {
        return confidence;
    }

    public void setConfidence(String confidence) {
        this.confidence = confidence;
    }

    public String getPrecondition() {
        return precondition;
    }

    public void setPrecondition(String precondition) {
        this.precondition = precondition;
    }

    public List<String> getEvidenceRefs() {
        return evidenceRefs;
    }

    public void setEvidenceRefs(List<String> evidenceRefs) {
        this.evidenceRefs = evidenceRefs == null ? new ArrayList<String>() : evidenceRefs;
    }
}
