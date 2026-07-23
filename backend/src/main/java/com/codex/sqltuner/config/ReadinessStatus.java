package com.codex.sqltuner.config;

public class ReadinessStatus {
    private final String status;
    private final String mysql;
    private final String scheduler;

    public ReadinessStatus(String status, String mysql, String scheduler) {
        this.status = status;
        this.mysql = mysql;
        this.scheduler = scheduler;
    }

    public String getStatus() {
        return status;
    }

    public String getMysql() {
        return mysql;
    }

    public String getScheduler() {
        return scheduler;
    }

    public boolean isUp() {
        return "UP".equals(status);
    }
}
