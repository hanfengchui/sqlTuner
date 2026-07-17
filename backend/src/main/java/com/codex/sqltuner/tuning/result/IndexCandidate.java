package com.codex.sqltuner.tuning.result;

import java.util.ArrayList;
import java.util.List;

public class IndexCandidate {
    private String tableName;
    private List<String> columnOrder = new ArrayList<String>();
    private String ddl;
    private String benefit;
    private String writeCost;
    private String risk;
    private String validation;
    private String confidence;
    private List<String> evidenceRefs = new ArrayList<String>();

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public List<String> getColumnOrder() {
        return columnOrder;
    }

    public void setColumnOrder(List<String> columnOrder) {
        this.columnOrder = columnOrder == null ? new ArrayList<String>() : columnOrder;
    }

    public String getDdl() {
        return ddl;
    }

    public void setDdl(String ddl) {
        this.ddl = ddl;
    }

    public String getBenefit() {
        return benefit;
    }

    public void setBenefit(String benefit) {
        this.benefit = benefit;
    }

    public String getWriteCost() {
        return writeCost;
    }

    public void setWriteCost(String writeCost) {
        this.writeCost = writeCost;
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

    public String getConfidence() {
        return confidence;
    }

    public void setConfidence(String confidence) {
        this.confidence = confidence;
    }

    public List<String> getEvidenceRefs() {
        return evidenceRefs;
    }

    public void setEvidenceRefs(List<String> evidenceRefs) {
        this.evidenceRefs = evidenceRefs == null ? new ArrayList<String>() : evidenceRefs;
    }
}
