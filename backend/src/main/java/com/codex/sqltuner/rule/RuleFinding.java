package com.codex.sqltuner.rule;

public class RuleFinding {
    private String code;
    private RuleSeverity severity;
    private String title;
    private String evidence;
    private String suggestion;

    public RuleFinding() {
    }

    public RuleFinding(String code, RuleSeverity severity, String title, String evidence, String suggestion) {
        this.code = code;
        this.severity = severity;
        this.title = title;
        this.evidence = evidence;
        this.suggestion = suggestion;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public RuleSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(RuleSeverity severity) {
        this.severity = severity;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getEvidence() {
        return evidence;
    }

    public void setEvidence(String evidence) {
        this.evidence = evidence;
    }

    public String getSuggestion() {
        return suggestion;
    }

    public void setSuggestion(String suggestion) {
        this.suggestion = suggestion;
    }
}
