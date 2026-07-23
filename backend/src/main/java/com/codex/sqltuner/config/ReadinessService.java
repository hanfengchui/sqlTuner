package com.codex.sqltuner.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ReadinessService {
    private static final long DEFAULT_CACHE_MS = 1000L;
    private final JdbcTemplate jdbcTemplate;
    private final SchedulerHealth schedulerHealth;
    private final long cacheMs;
    private volatile CachedStatus cached;

    public ReadinessService(JdbcTemplate jdbcTemplate, SchedulerHealth schedulerHealth) {
        this(jdbcTemplate, schedulerHealth, DEFAULT_CACHE_MS);
    }

    ReadinessService(JdbcTemplate jdbcTemplate, SchedulerHealth schedulerHealth, long cacheMs) {
        this.jdbcTemplate = jdbcTemplate;
        this.schedulerHealth = schedulerHealth;
        this.cacheMs = Math.max(100L, cacheMs);
    }

    public ReadinessStatus current() {
        long now = System.currentTimeMillis();
        CachedStatus existing = cached;
        if (existing != null && now - existing.checkedAtMs < cacheMs) {
            return existing.status;
        }
        synchronized (this) {
            existing = cached;
            if (existing != null && now - existing.checkedAtMs < cacheMs) {
                return existing.status;
            }
            String mysql = "UP";
            try {
                jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            } catch (Exception ignored) {
                mysql = "DOWN";
            }
            String scheduler = schedulerHealth.isHealthy() ? "UP" : "DOWN";
            ReadinessStatus status = new ReadinessStatus(
                    "UP".equals(mysql) && "UP".equals(scheduler) ? "UP" : "DOWN",
                    mysql,
                    scheduler);
            cached = new CachedStatus(now, status);
            return status;
        }
    }

    private static final class CachedStatus {
        private final long checkedAtMs;
        private final ReadinessStatus status;

        private CachedStatus(long checkedAtMs, ReadinessStatus status) {
            this.checkedAtMs = checkedAtMs;
            this.status = status;
        }
    }
}
