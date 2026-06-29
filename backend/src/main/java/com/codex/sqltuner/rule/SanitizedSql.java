package com.codex.sqltuner.rule;

public class SanitizedSql {
    private String originalSql;
    private String sanitizedSql;
    private int originalLength;
    private String sqlHash;

    public SanitizedSql() {
    }

    public SanitizedSql(String originalSql, String sanitizedSql, int originalLength, String sqlHash) {
        this.originalSql = originalSql;
        this.sanitizedSql = sanitizedSql;
        this.originalLength = originalLength;
        this.sqlHash = sqlHash;
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

    public int getOriginalLength() {
        return originalLength;
    }

    public void setOriginalLength(int originalLength) {
        this.originalLength = originalLength;
    }

    public String getSqlHash() {
        return sqlHash;
    }

    public void setSqlHash(String sqlHash) {
        this.sqlHash = sqlHash;
    }
}
