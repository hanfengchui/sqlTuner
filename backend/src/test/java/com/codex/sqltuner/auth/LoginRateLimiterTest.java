package com.codex.sqltuner.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRateLimiterTest {
    @Test
    void blocksUsernameAndIpAfterTenFailuresInWindow() {
        LoginRateLimiter limiter = new LoginRateLimiter();

        for (int i = 0; i < 10; i++) {
            assertThat(limiter.isBlocked("admin", "10.0.0.1")).isFalse();
            limiter.recordFailure("admin", "10.0.0.1");
        }

        assertThat(limiter.isBlocked("admin", "10.0.0.1")).isTrue();
        assertThat(limiter.isBlocked("admin", "10.0.0.2")).isFalse();
    }

    @Test
    void blocksIpAfterOneHundredFailuresAcrossUsernames() {
        LoginRateLimiter limiter = new LoginRateLimiter();

        for (int i = 0; i < 100; i++) {
            assertThat(limiter.isBlocked("user-" + i, "10.0.0.1")).isFalse();
            limiter.recordFailure("user-" + i, "10.0.0.1");
        }

        assertThat(limiter.isBlocked("another-user", "10.0.0.1")).isTrue();
        assertThat(limiter.isBlocked("another-user", "10.0.0.2")).isFalse();
    }

    @Test
    void blocksUsernameAfterFiftyFailuresAcrossIps() {
        LoginRateLimiter limiter = new LoginRateLimiter();

        for (int i = 0; i < 50; i++) {
            assertThat(limiter.isBlocked("admin", "10.0.0." + i)).isFalse();
            limiter.recordFailure("admin", "10.0.0." + i);
        }

        assertThat(limiter.isBlocked("admin", "10.0.1.1")).isTrue();
        assertThat(limiter.isBlocked("other", "10.0.1.1")).isFalse();
    }

    @Test
    void prunesKeysToBoundedCapacity() {
        LoginRateLimiter limiter = new LoginRateLimiter();

        for (int i = 0; i < 11000; i++) {
            limiter.recordFailure("user-" + i, "10.0." + (i / 255) + "." + (i % 255));
        }

        assertThat(limiter.keyCount()).isLessThanOrEqualTo(10000);
    }
}
