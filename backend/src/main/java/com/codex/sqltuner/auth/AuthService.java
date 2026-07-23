package com.codex.sqltuner.auth;

import com.codex.sqltuner.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpSession;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class AuthService {
    public static final String SESSION_USER = "SQL_TUNER_USER";
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final LoginRateLimiter loginRateLimiter = new LoginRateLimiter();
    private final ConcurrentMap<Long, ConcurrentMap<String, HttpSession>> activeSessions =
            new ConcurrentHashMap<Long, ConcurrentMap<String, HttpSession>>();

    public AuthService(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    public UserAccount login(String username, String password, String clientIp) {
        String safeUsername = username == null ? "" : username.trim();
        String safeClientIp = clientIp == null || clientIp.trim().isEmpty() ? "unknown" : clientIp.trim();
        log.info("login param 入参: username: {}, clientIp: {}", safeUsername, safeClientIp);
        if (loginRateLimiter.isBlocked(safeUsername, safeClientIp)) {
            log.warn("login result 结果: 登录限速, username: {}", safeUsername);
            throw new ApiException(429, "LOGIN_RATE_LIMITED", "登录失败次数过多，请稍后再试");
        }
        UserAccount account = findByUsername(safeUsername);
        if (account == null || !passwordEncoder.matches(password == null ? "" : password, account.getPassword())) {
            loginRateLimiter.recordFailure(safeUsername, safeClientIp);
            log.warn("login result 结果: 用户名或密码错误, username: {}", safeUsername);
            throw new ApiException(401, "AUTH_FAILED", "用户名或密码错误");
        }
        loginRateLimiter.recordSuccess(safeUsername, safeClientIp);
        account.setPassword(null);
        log.info("login result 结果: 登录成功, userId: {}, role: {}", account.getId(), account.getRole());
        return account;
    }

    public void registerSession(UserAccount account, HttpSession session) {
        if (account == null || account.getId() == null || session == null) {
            return;
        }
        ConcurrentMap<String, HttpSession> sessions = activeSessions.get(account.getId());
        if (sessions == null) {
            sessions = new ConcurrentHashMap<String, HttpSession>();
            ConcurrentMap<String, HttpSession> existing = activeSessions.putIfAbsent(account.getId(), sessions);
            if (existing != null) {
                sessions = existing;
            }
        }
        sessions.put(session.getId(), session);
    }

    public void unregisterSession(UserAccount account, HttpSession session) {
        if (account == null || account.getId() == null || session == null) {
            return;
        }
        ConcurrentMap<String, HttpSession> sessions = activeSessions.get(account.getId());
        if (sessions != null) {
            sessions.remove(session.getId());
        }
    }

    public UserAccount requireActiveAccount(Long userId) {
        if (userId == null) {
            return null;
        }
        List<UserAccount> accounts = jdbcTemplate.query(
                "SELECT * FROM users WHERE id = ? AND enabled = TRUE",
                userMapper(), userId);
        UserAccount account = accounts.isEmpty() ? null : accounts.get(0);
        if (account != null) {
            account.setPassword(null);
        }
        return account;
    }

    public List<AdminUserView> listUsers() {
        return jdbcTemplate.query(
                "SELECT id, username, display_name, role, enabled FROM users ORDER BY id",
                (rs, rowNum) -> new AdminUserView(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        UserRole.valueOf(rs.getString("role")),
                        rs.getBoolean("enabled")));
    }

    @Transactional
    public AdminUserView createUser(AdminUserCreateRequest request) {
        String username = normalizeUsername(request.getUsername());
        String displayName = normalizeDisplayName(request.getDisplayName(), username);
        requireStrongPassword(request.getPassword());
        if (request.getRole() == null) {
            throw new ApiException(400, "VALIDATION_FAILED", "角色不能为空");
        }
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = ?",
                Integer.class, username);
        if (existing != null && existing > 0) {
            throw new ApiException(409, "USER_EXISTS", "用户名已存在");
        }
        Long id = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM users", Long.class);
        LocalDateTime now = LocalDateTime.now();
        boolean enabled = request.getEnabled() == null || request.getEnabled().booleanValue();
        jdbcTemplate.update(
                "INSERT INTO users(id, username, display_name, password_hash, role, enabled, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                username,
                displayName,
                passwordEncoder.encode(request.getPassword()),
                request.getRole().name(),
                enabled,
                Timestamp.valueOf(now),
                Timestamp.valueOf(now));
        log.info("adminCreateUser result 结果: userId: {}, role: {}, enabled: {}", id, request.getRole(), enabled);
        return getAdminUser(id);
    }

    @Transactional
    public AdminUserView setUserEnabled(Long targetUserId, boolean enabled, UserAccount currentAdmin) {
        AdminUserView target = getAdminUser(targetUserId);
        if (target == null) {
            throw new ApiException(404, "USER_NOT_FOUND", "用户不存在");
        }
        if (!enabled && currentAdmin != null && targetUserId != null && targetUserId.equals(currentAdmin.getId())) {
            throw new ApiException(400, "CANNOT_DISABLE_SELF", "不能禁用当前管理员账号");
        }
        jdbcTemplate.update(
                "UPDATE users SET enabled = ?, updated_at = ? WHERE id = ?",
                enabled,
                Timestamp.valueOf(LocalDateTime.now()),
                targetUserId);
        if (!enabled) {
            invalidateUserSessions(targetUserId);
        }
        log.info("adminSetUserEnabled result 结果: userId: {}, enabled: {}", targetUserId, enabled);
        return getAdminUser(targetUserId);
    }

    @Transactional
    public AdminUserView resetPassword(Long targetUserId, String password) {
        requireStrongPassword(password);
        AdminUserView target = getAdminUser(targetUserId);
        if (target == null) {
            throw new ApiException(404, "USER_NOT_FOUND", "用户不存在");
        }
        jdbcTemplate.update(
                "UPDATE users SET password_hash = ?, updated_at = ? WHERE id = ?",
                passwordEncoder.encode(password),
                Timestamp.valueOf(LocalDateTime.now()),
                targetUserId);
        invalidateUserSessions(targetUserId);
        log.info("adminResetPassword result 结果: userId: {}", targetUserId);
        return getAdminUser(targetUserId);
    }

    private void invalidateUserSessions(Long userId) {
        ConcurrentMap<String, HttpSession> sessions = activeSessions.remove(userId);
        if (sessions == null) {
            return;
        }
        List<String> sessionIds = new ArrayList<String>(sessions.keySet());
        for (String sessionId : sessionIds) {
            HttpSession session = sessions.remove(sessionId);
            if (session != null) {
                try {
                    session.invalidate();
                } catch (IllegalStateException ignored) {
                    // Already invalidated by logout or container expiry.
                }
            }
        }
    }

    private AdminUserView getAdminUser(Long id) {
        List<AdminUserView> users = jdbcTemplate.query(
                "SELECT id, username, display_name, role, enabled FROM users WHERE id = ?",
                (rs, rowNum) -> new AdminUserView(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        UserRole.valueOf(rs.getString("role")),
                        rs.getBoolean("enabled")),
                id);
        return users.isEmpty() ? null : users.get(0);
    }

    private void requireStrongPassword(String password) {
        if (password == null || password.length() < 12) {
            throw new ApiException(400, "PASSWORD_TOO_SHORT", "密码长度不能少于 12 位");
        }
    }

    private String normalizeUsername(String username) {
        String value = username == null ? "" : username.trim();
        if (value.isEmpty()) {
            throw new ApiException(400, "VALIDATION_FAILED", "用户名不能为空");
        }
        if (value.length() > 64) {
            throw new ApiException(400, "VALIDATION_FAILED", "用户名不能超过 64 位");
        }
        return value;
    }

    private String normalizeDisplayName(String displayName, String username) {
        String value = displayName == null ? "" : displayName.trim();
        if (value.isEmpty()) {
            value = username;
        }
        if (value.length() > 128) {
            throw new ApiException(400, "VALIDATION_FAILED", "显示名称不能超过 128 位");
        }
        return value;
    }

    private void requireAdmin(UserAccount account) {
        if (account == null || account.getRole() != UserRole.ADMIN) {
            throw new AccessDeniedException("需要管理员权限");
        }
    }

    private UserAccount findByUsername(String username) {
        java.util.List<UserAccount> accounts = jdbcTemplate.query(
                "SELECT * FROM users WHERE username = ? AND enabled = TRUE",
                userMapper(), username);
        return accounts.isEmpty() ? null : accounts.get(0);
    }

    private org.springframework.jdbc.core.RowMapper<UserAccount> userMapper() {
        return new org.springframework.jdbc.core.RowMapper<UserAccount>() {
            @Override
            public UserAccount mapRow(ResultSet rs, int rowNum) throws java.sql.SQLException {
                return new UserAccount(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        rs.getString("password_hash"),
                        UserRole.valueOf(rs.getString("role")));
            }
        };
    }

    public void requireAdminSession(UserAccount account) {
        requireAdmin(account);
    }

    @Scheduled(fixedDelay = 60000L)
    public void purgeExpiredLoginRateLimitEntries() {
        loginRateLimiter.purgeExpired();
    }
}
