package com.codex.sqltuner.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Service
public class AuthService {
    public static final String SESSION_USER = "SQL_TUNER_USER";
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private final Map<String, UserAccount> users;

    public AuthService() {
        Map<String, UserAccount> seed = new HashMap<String, UserAccount>();
        seed.put("admin", new UserAccount(1L, "admin", "管理员", resolvePassword("SQL_TUNER_ADMIN_PASSWORD", "admin123"), UserRole.ADMIN));
        seed.put("user", new UserAccount(2L, "user", "业务用户", resolvePassword("SQL_TUNER_USER_PASSWORD", "user123"), UserRole.USER));
        this.users = Collections.unmodifiableMap(seed);
    }

    public UserAccount login(String username, String password) {
        log.info("login param 入参: username: {}", username);
        UserAccount account = users.get(username);
        if (account == null || !account.getPassword().equals(password)) {
            log.warn("login result 结果: 用户名或密码错误, username: {}", username);
            throw new IllegalArgumentException("用户名或密码错误");
        }
        log.info("login result 结果: 登录成功, userId: {}, role: {}", account.getId(), account.getRole());
        return account;
    }

    private String resolvePassword(String envName, String defaultPassword) {
        String configured = System.getenv(envName);
        return configured == null || configured.trim().isEmpty() ? defaultPassword : configured.trim();
    }
}
