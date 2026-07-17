package com.codex.sqltuner.tuning.result;

import java.util.ArrayList;
import java.util.List;

public class ValidationStep {
    private String action;
    private String expectedSignal;
    private List<String> evidenceRefs = new ArrayList<String>();

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getExpectedSignal() {
        return expectedSignal;
    }

    public void setExpectedSignal(String expectedSignal) {
        this.expectedSignal = expectedSignal;
    }

    public List<String> getEvidenceRefs() {
        return evidenceRefs;
    }

    public void setEvidenceRefs(List<String> evidenceRefs) {
        this.evidenceRefs = evidenceRefs == null ? new ArrayList<String>() : evidenceRefs;
    }
}
