package com.codex.sqltuner.auth;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class LoginRateLimiter {
    private static final long WINDOW_MS = 5L * 60L * 1000L;
    private static final int MAX_KEYS = 10000;
    private static final int USERNAME_IP_LIMIT = 10;
    private static final int IP_LIMIT = 100;
    private static final int USERNAME_LIMIT = 50;

    private final ConcurrentMap<String, AttemptWindow> failures =
            new ConcurrentHashMap<String, AttemptWindow>();

    boolean isBlocked(String username, String clientIp) {
        long now = System.currentTimeMillis();
        return isBlocked(key("user-ip", username, clientIp), USERNAME_IP_LIMIT, now)
                || isBlocked(key("ip", clientIp, ""), IP_LIMIT, now)
                || isBlocked(key("user", username, ""), USERNAME_LIMIT, now);
    }

    void recordFailure(String username, String clientIp) {
        long now = System.currentTimeMillis();
        record(key("user-ip", username, clientIp), now);
        record(key("ip", clientIp, ""), now);
        record(key("user", username, ""), now);
        if (failures.size() > MAX_KEYS) {
            prune(now);
        }
    }

    void recordSuccess(String username, String clientIp) {
        failures.remove(key("user-ip", username, clientIp));
        failures.remove(key("user", username, ""));
    }

    int keyCount() {
        prune(System.currentTimeMillis());
        return failures.size();
    }

    void purgeExpired() {
        pruneExpired(System.currentTimeMillis());
    }

    private boolean isBlocked(String key, int limit, long now) {
        AttemptWindow window = failures.get(key);
        return window != null && window.isBlocked(limit, now);
    }

    private void record(String key, long now) {
        AttemptWindow window = failures.get(key);
        if (window == null) {
            window = new AttemptWindow(now);
            AttemptWindow existing = failures.putIfAbsent(key, window);
            if (existing != null) {
                window = existing;
            }
        }
        window.recordFailure(now);
    }

    private void prune(long now) {
        pruneExpired(now);
        if (failures.size() <= MAX_KEYS) {
            return;
        }
        evictToCapacity();
    }

    private void pruneExpired(long now) {
        Iterator<Map.Entry<String, AttemptWindow>> iterator = failures.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, AttemptWindow> entry = iterator.next();
            if (entry.getValue().isExpired(now)) {
                failures.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private void evictToCapacity() {
        while (failures.size() > MAX_KEYS) {
            Iterator<String> keys = failures.keySet().iterator();
            if (!keys.hasNext()) {
                return;
            }
            failures.remove(keys.next());
        }
    }

    private String key(String scope, String first, String second) {
        return scope + ":" + normalize(first) + ":" + normalize(second);
    }

    private String normalize(String value) {
        return value == null || value.trim().isEmpty() ? "unknown" : value.trim().toLowerCase();
    }

    private static final class AttemptWindow {
        private int count;
        private long windowStartedAt;

        private AttemptWindow(long now) {
            this.windowStartedAt = now;
        }

        synchronized void recordFailure(long now) {
            resetIfExpired(now);
            if (windowStartedAt == 0L) {
                windowStartedAt = now;
            }
            count++;
        }

        synchronized boolean isBlocked(int limit, long now) {
            resetIfExpired(now);
            return count >= limit;
        }

        synchronized boolean isExpired(long now) {
            resetIfExpired(now);
            return count == 0;
        }

        private void resetIfExpired(long now) {
            if (windowStartedAt > 0L && now - windowStartedAt >= WINDOW_MS) {
                count = 0;
                windowStartedAt = 0L;
            }
        }
    }
}
