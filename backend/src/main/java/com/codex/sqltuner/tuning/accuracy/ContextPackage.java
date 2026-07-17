package com.codex.sqltuner.tuning.accuracy;

import com.codex.sqltuner.tuning.result.ContextAssessment;
import com.codex.sqltuner.tuning.result.EvidenceItem;

import java.util.ArrayList;
import java.util.List;

public class ContextPackage {
    private ContextAssessment assessment;
    private List<EvidenceItem> evidenceCatalog = new ArrayList<EvidenceItem>();
    private boolean allowRewrite;
    private boolean allowIndexDirection;
    private boolean allowIndexDdl;
    private boolean allowHighConfidence;

    public ContextAssessment getAssessment() {
        return assessment;
    }

    public void setAssessment(ContextAssessment assessment) {
        this.assessment = assessment;
    }

    public List<EvidenceItem> getEvidenceCatalog() {
        return evidenceCatalog;
    }

    public void setEvidenceCatalog(List<EvidenceItem> evidenceCatalog) {
        this.evidenceCatalog = evidenceCatalog;
    }

    public boolean isAllowRewrite() {
        return allowRewrite;
    }

    public void setAllowRewrite(boolean allowRewrite) {
        this.allowRewrite = allowRewrite;
    }

    public boolean isAllowIndexDirection() {
        return allowIndexDirection;
    }

    public void setAllowIndexDirection(boolean allowIndexDirection) {
        this.allowIndexDirection = allowIndexDirection;
    }

    public boolean isAllowIndexDdl() {
        return allowIndexDdl;
    }

    public void setAllowIndexDdl(boolean allowIndexDdl) {
        this.allowIndexDdl = allowIndexDdl;
    }

    public boolean isAllowHighConfidence() {
        return allowHighConfidence;
    }

    public void setAllowHighConfidence(boolean allowHighConfidence) {
        this.allowHighConfidence = allowHighConfidence;
    }
}
