package com.codex.sqltuner.tuning.result;

import java.util.ArrayList;
import java.util.List;

public class ContextAssessment {
    private String completeness;
    private String maxConfidence;
    private List<String> availableEvidence = new ArrayList<String>();
    private List<String> missingInformation = new ArrayList<String>();
    private List<String> policyNotes = new ArrayList<String>();

    public String getCompleteness() {
        return completeness;
    }

    public void setCompleteness(String completeness) {
        this.completeness = completeness;
    }

    public String getMaxConfidence() {
        return maxConfidence;
    }

    public void setMaxConfidence(String maxConfidence) {
        this.maxConfidence = maxConfidence;
    }

    public List<String> getAvailableEvidence() {
        return availableEvidence;
    }

    public void setAvailableEvidence(List<String> availableEvidence) {
        this.availableEvidence = availableEvidence == null ? new ArrayList<String>() : availableEvidence;
    }

    public List<String> getMissingInformation() {
        return missingInformation;
    }

    public void setMissingInformation(List<String> missingInformation) {
        this.missingInformation = missingInformation == null ? new ArrayList<String>() : missingInformation;
    }

    public List<String> getPolicyNotes() {
        return policyNotes;
    }

    public void setPolicyNotes(List<String> policyNotes) {
        this.policyNotes = policyNotes == null ? new ArrayList<String>() : policyNotes;
    }
}
