package com.codex.sqltuner.auth;

import com.codex.sqltuner.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {
    public static final String SESSION_USER = "SQL_TUNER_USER";
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private static final long FAILURE_WINDOW_MS = 5L * 60L * 1000L;
    private final Map<String, AttemptWindow> failures = new ConcurrentHashMap<String, AttemptWindow>();

    public AuthService(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    public UserAccount login(String username, String password) {
        String safeUsername = username == null ? "" : username.trim();
        log.info("login param 入参: username: {}", safeUsername);
        long now = System.currentTimeMillis();
        AttemptWindow failureWindow = failuresFor(safeUsername);
        if (failureWindow.isBlocked(now)) {
            log.warn("login result 结果: 登录限速, username: {}", safeUsername);
            throw new ApiException(429, "LOGIN_RATE_LIMITED", "登录失败次数过多，请稍后再试");
        }
        UserAccount account = findByUsername(safeUsername);
        if (account == null || !passwordEncoder.matches(password == null ? "" : password, account.getPassword())) {
            failureWindow.recordFailure(now);
            log.warn("login result 结果: 用户名或密码错误, username: {}", safeUsername);
            throw new ApiException(401, "AUTH_FAILED", "用户名或密码错误");
        }
        failures.remove(safeUsername);
        account.setPassword(null);
        log.info("login result 结果: 登录成功, userId: {}, role: {}", account.getId(), account.getRole());
        return account;
    }

    private AttemptWindow failuresFor(String username) {
        AttemptWindow counter = failures.get(username);
        if (counter == null) {
            counter = new AttemptWindow();
            AttemptWindow existing = failures.putIfAbsent(username, counter);
            if (existing != null) {
                counter = existing;
            }
        }
        return counter;
    }

    private static final class AttemptWindow {
        private int count;
        private long windowStartedAt;

        synchronized void recordFailure(long now) {
            resetIfExpired(now);
            if (windowStartedAt == 0L) {
                windowStartedAt = now;
            }
            count++;
        }

        synchronized boolean isBlocked(long now) {
            resetIfExpired(now);
            return count >= 10;
        }

        private void resetIfExpired(long now) {
            if (windowStartedAt > 0L && now - windowStartedAt >= FAILURE_WINDOW_MS) {
                count = 0;
                windowStartedAt = 0L;
            }
        }
    }

    private UserAccount findByUsername(String username) {
        java.util.List<UserAccount> accounts = jdbcTemplate.query(
                "SELECT * FROM users WHERE username = ? AND enabled = TRUE",
                new org.springframework.jdbc.core.RowMapper<UserAccount>() {
                    @Override
                    public UserAccount mapRow(ResultSet rs, int rowNum) throws java.sql.SQLException {
                        return new UserAccount(
                                rs.getLong("id"),
                                rs.getString("username"),
                                rs.getString("display_name"),
                                rs.getString("password_hash"),
                                UserRole.valueOf(rs.getString("role")));
                    }
                }, username);
        return accounts.isEmpty() ? null : accounts.get(0);
    }
}
