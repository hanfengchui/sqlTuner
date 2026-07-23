package com.codex.sqltuner.config;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 以独立心跳确认 Spring 调度器仍在运行，避免仅因 Bean 已创建就报告 ready。 */
@Component
public class SchedulerHealth {
    private static final long STALE_AFTER_MS = 15000L;
    private volatile long lastTickAt;

    @Scheduled(fixedDelay = 1000L)
    public void tick() {
        lastTickAt = System.currentTimeMillis();
    }

    public boolean isHealthy() {
        long lastTick = lastTickAt;
        return lastTick > 0L && System.currentTimeMillis() - lastTick <= STALE_AFTER_MS;
    }
}
