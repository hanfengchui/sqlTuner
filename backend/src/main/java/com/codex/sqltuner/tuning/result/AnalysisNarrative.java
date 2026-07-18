package com.codex.sqltuner.tuning.result;

import java.util.ArrayList;
import java.util.List;

/** 聊天主界面的可读答案，底层诊断和候选项仍是可校验的权威契约。 */
public class AnalysisNarrative {
    private String conclusion;
    private List<NarrativeSection> sections = new ArrayList<NarrativeSection>();

    public String getConclusion() {
        return conclusion;
    }

    public void setConclusion(String conclusion) {
        this.conclusion = conclusion;
    }

    public List<NarrativeSection> getSections() {
        return sections;
    }

    public void setSections(List<NarrativeSection> sections) {
        this.sections = sections == null ? new ArrayList<NarrativeSection>() : sections;
    }
}
