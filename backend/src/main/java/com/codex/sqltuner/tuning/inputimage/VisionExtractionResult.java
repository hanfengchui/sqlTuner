package com.codex.sqltuner.tuning.inputimage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class VisionExtractionResult {
    private boolean readable;
    private List<Object> operators = new ArrayList<Object>();
    private List<Object> tables = new ArrayList<Object>();
    private List<Object> rowEstimates = new ArrayList<Object>();
    private List<String> warnings = new ArrayList<String>();
    private String rawTextSummary;

    public boolean isReadable() {
        return readable;
    }

    public void setReadable(boolean readable) {
        this.readable = readable;
    }

    public List<Object> getOperators() {
        return operators;
    }

    public void setOperators(List<Object> operators) {
        this.operators = operators == null ? new ArrayList<Object>() : operators;
    }

    public List<Object> getTables() {
        return tables;
    }

    public void setTables(List<Object> tables) {
        this.tables = tables == null ? new ArrayList<Object>() : tables;
    }

    public List<Object> getRowEstimates() {
        return rowEstimates;
    }

    public void setRowEstimates(List<Object> rowEstimates) {
        this.rowEstimates = rowEstimates == null ? new ArrayList<Object>() : rowEstimates;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings == null ? new ArrayList<String>() : warnings;
    }

    public String getRawTextSummary() {
        return rawTextSummary;
    }

    public void setRawTextSummary(String rawTextSummary) {
        this.rawTextSummary = rawTextSummary;
    }
}
