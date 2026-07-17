package com.codex.sqltuner.tuning.result;

import java.util.ArrayList;
import java.util.List;

public class ReviewResult {
    private String verdict;
    private String notes;
    private List<String> revisions = new ArrayList<String>();

    public String getVerdict() {
        return verdict;
    }

    public void setVerdict(String verdict) {
        this.verdict = verdict;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public List<String> getRevisions() {
        return revisions;
    }

    public void setRevisions(List<String> revisions) {
        this.revisions = revisions == null ? new ArrayList<String>() : revisions;
    }
}
