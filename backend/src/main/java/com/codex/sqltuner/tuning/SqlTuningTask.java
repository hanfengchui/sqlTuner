package com.codex.sqltuner.tuning;

import com.codex.sqltuner.rule.RuleFinding;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class SqlTuningTask {
    private Long id;
    private Long userId;
    private Long conversationId;
    private String dbDialect;
    private String inputType;
    private String originalSql;
    private String sanitizedSql;
    private String sqlHash;
    private String schemaText;
    private String indexText;
    private String explainText;
    private String businessContext;
    private boolean deepAnalysis;
    private TaskStatus status;
    private String statusMessage;
    private Long skillId;
    private String skillName;
    private Integer skillVersion;
    private List<RuleFinding> ruleFindings = new ArrayList<RuleFinding>();
    private TuningResult result;
    private List<HarnessArtifact> artifacts = new ArrayList<HarnessArtifact>();
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

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

    public String getInputType() {
        return inputType;
    }

    public void setInputType(String inputType) {
        this.inputType = inputType;
    }

    public String getOriginalSql() {
        return originalSql;
    }

    public void setOriginalSql(String originalSql) {
        this.originalSql = originalSql;
    }

    public String getSanitizedSql() {
        return sanitizedSql;
    }

    public void setSanitizedSql(String sanitizedSql) {
        this.sanitizedSql = sanitizedSql;
    }

    public String getSqlHash() {
        return sqlHash;
    }

    public void setSqlHash(String sqlHash) {
        this.sqlHash = sqlHash;
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

    public boolean isDeepAnalysis() {
        return deepAnalysis;
    }

    public void setDeepAnalysis(boolean deepAnalysis) {
        this.deepAnalysis = deepAnalysis;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
    }

    public Long getSkillId() {
        return skillId;
    }

    public void setSkillId(Long skillId) {
        this.skillId = skillId;
    }

    public String getSkillName() {
        return skillName;
    }

    public void setSkillName(String skillName) {
        this.skillName = skillName;
    }

    public Integer getSkillVersion() {
        return skillVersion;
    }

    public void setSkillVersion(Integer skillVersion) {
        this.skillVersion = skillVersion;
    }

    public List<RuleFinding> getRuleFindings() {
        return ruleFindings;
    }

    public void setRuleFindings(List<RuleFinding> ruleFindings) {
        this.ruleFindings = ruleFindings;
    }

    public TuningResult getResult() {
        return result;
    }

    public void setResult(TuningResult result) {
        this.result = result;
    }

    public List<HarnessArtifact> getArtifacts() {
        return artifacts;
    }

    public void setArtifacts(List<HarnessArtifact> artifacts) {
        this.artifacts = artifacts;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
