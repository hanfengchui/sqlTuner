package com.codex.sqltuner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.queue")
public class QueueProperties {
    private int workerCount = 10;
    private int maxRunning = 10;
    private int maxQueuedGlobal = 100;
    private int maxQueuedPerUser = 10;
    private int leaseSeconds = 90;
    private int heartbeatSeconds = 15;

    public int getWorkerCount() {
        return workerCount;
    }

    public void setWorkerCount(int workerCount) {
        this.workerCount = workerCount;
    }

    public int getMaxRunning() {
        return maxRunning;
    }

    public void setMaxRunning(int maxRunning) {
        this.maxRunning = maxRunning;
    }

    public int getMaxQueuedGlobal() {
        return maxQueuedGlobal;
    }

    public void setMaxQueuedGlobal(int maxQueuedGlobal) {
        this.maxQueuedGlobal = maxQueuedGlobal;
    }

    public int getMaxQueuedPerUser() {
        return maxQueuedPerUser;
    }

    public void setMaxQueuedPerUser(int maxQueuedPerUser) {
        this.maxQueuedPerUser = maxQueuedPerUser;
    }

    public int getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(int leaseSeconds) {
        this.leaseSeconds = leaseSeconds;
    }

    public int getHeartbeatSeconds() {
        return heartbeatSeconds;
    }

    public void setHeartbeatSeconds(int heartbeatSeconds) {
        this.heartbeatSeconds = heartbeatSeconds;
    }
}
