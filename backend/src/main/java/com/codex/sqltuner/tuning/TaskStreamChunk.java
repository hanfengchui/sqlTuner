package com.codex.sqltuner.tuning;

public class TaskStreamChunk {
    private String phase;
    private String draftText;
    private int receivedChars;
    private long sequence;
    private int attempt;

    public TaskStreamChunk() {
    }

    public TaskStreamChunk(String phase, String draftText, int receivedChars, long sequence) {
        this.phase = phase;
        this.draftText = draftText;
        this.receivedChars = receivedChars;
        this.sequence = sequence;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public String getDraftText() {
        return draftText;
    }

    public void setDraftText(String draftText) {
        this.draftText = draftText;
    }

    public int getReceivedChars() {
        return receivedChars;
    }

    public void setReceivedChars(int receivedChars) {
        this.receivedChars = receivedChars;
    }

    public long getSequence() {
        return sequence;
    }

    public void setSequence(long sequence) {
        this.sequence = sequence;
    }

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = attempt;
    }
}
