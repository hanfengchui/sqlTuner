package com.codex.sqltuner.tuning;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

public class CreateTuningTaskRequest {
    private Long conversationId;

    @NotBlank(message = "数据库方言不能为空")
    private String dbDialect;

    @NotBlank(message = "SQL 不能为空")
    private String sqlText;

    private String inputType;
    private String schemaText;
    private String indexText;
    private String explainText;
    private String businessContext;

    @NotNull(message = "deepAnalysis 不能为空")
    private Boolean deepAnalysis = Boolean.FALSE;

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }

    public String getDbDialect() {
        return dbDialect;
    }

    public void setDbDialect(String dbDialect) {
        this.dbDialect = dbDialect;
    }

    public String getSqlText() {
        return sqlText;
    }

    public void setSqlText(String sqlText) {
        this.sqlText = sqlText;
    }

    public String getInputType() {
        return inputType;
    }

    public void setInputType(String inputType) {
        this.inputType = inputType;
    }

    public String getSchemaText() {
        return schemaText;
    }

    public void setSchemaText(String schemaText) {
        this.schemaText = schemaText;
    }

    public String getIndexText() {
        return indexText;
    }

    public void setIndexText(String indexText) {
        this.indexText = indexText;
    }

    public String getExplainText() {
        return explainText;
    }

    public void setExplainText(String explainText) {
        this.explainText = explainText;
    }

    public String getBusinessContext() {
        return businessContext;
    }

    public void setBusinessContext(String businessContext) {
        this.businessContext = businessContext;
    }

    public Boolean getDeepAnalysis() {
        return deepAnalysis;
    }

    public void setDeepAnalysis(Boolean deepAnalysis) {
        this.deepAnalysis = deepAnalysis;
    }
}
