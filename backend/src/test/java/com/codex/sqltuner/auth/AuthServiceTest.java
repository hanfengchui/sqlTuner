package com.codex.sqltuner.auth;

import com.codex.sqltuner.common.ApiException;
import com.codex.sqltuner.storage.JdbcTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthServiceTest {
    @Test
    void loginUsesUsernameIpRateLimitAndClearsFailureOnSuccess() {
        AuthService authService = authService();

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> authService.login("admin", "wrong-password", "10.0.0.1"))
                    .isInstanceOf(ApiException.class)
                    .hasMessage("用户名或密码错误");
        }

        assertThatThrownBy(() -> authService.login("admin", "admin-test-password", "10.0.0.1"))
                .isInstanceOf(ApiException.class)
                .hasMessage("登录失败次数过多，请稍后再试");

        UserAccount account = authService.login("admin", "admin-test-password", "10.0.0.2");
        assertThat(account.getUsername()).isEqualTo("admin");
        assertThat(account.getPassword()).isNull();
    }

    @Test
    void adminCanListCreateDisableAndResetUsersWithoutReturningPasswords() {
        JdbcTemplate jdbcTemplate = JdbcTestSupport.jdbcTemplate();
        AuthService authService = new AuthService(jdbcTemplate, new BCryptPasswordEncoder(4));

        AdminUserCreateRequest request = new AdminUserCreateRequest();
        request.setUsername("reviewer");
        request.setDisplayName("Reviewer");
        request.setPassword("reviewer-password");
        request.setRole(UserRole.USER);
        AdminUserView created = authService.createUser(request);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getUsername()).isEqualTo("reviewer");
        assertThat(created.getRole()).isEqualTo(UserRole.USER);
        assertThat(created.isEnabled()).isTrue();
        List<AdminUserView> users = authService.listUsers();
        assertThat(users).extracting(AdminUserView::getUsername).contains("admin", "user", "reviewer");

        authService.resetPassword(created.getId(), "replacement-password");
        assertThat(authService.login("reviewer", "replacement-password", "10.0.0.5").getPassword()).isNull();

        authService.setUserEnabled(created.getId(), false,
                new UserAccount(1L, "admin", "Admin", null, UserRole.ADMIN));
        assertThat(authService.requireActiveAccount(created.getId())).isNull();
        assertThatThrownBy(() -> authService.login("reviewer", "replacement-password", "10.0.0.5"))
                .isInstanceOf(ApiException.class)
                .hasMessage("用户名或密码错误");
    }

    @Test
    void disablingAccountInvalidatesRegisteredSessionsImmediately() {
        AuthService authService = authService();
        UserAccount user = authService.login("user", "user-test-password", "10.0.0.3");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AuthService.SESSION_USER, user);
        authService.registerSession(user, session);

        authService.setUserEnabled(user.getId(), false,
                new UserAccount(1L, "admin", "Admin", null, UserRole.ADMIN));

        assertThatThrownBy(() -> session.getAttribute(AuthService.SESSION_USER))
                .isInstanceOf(IllegalStateException.class);
        assertThat(authService.requireActiveAccount(user.getId())).isNull();
    }

    @Test
    void passwordPolicyRequiresAtLeastTwelveCharacters() {
        AuthService authService = authService();
        AdminUserCreateRequest request = new AdminUserCreateRequest();
        request.setUsername("short-pass");
        request.setPassword("short");
        request.setRole(UserRole.USER);

        assertThatThrownBy(() -> authService.createUser(request))
                .isInstanceOf(ApiException.class)
                .hasMessage("密码长度不能少于 12 位");

        assertThatThrownBy(() -> authService.resetPassword(2L, "short"))
                .isInstanceOf(ApiException.class)
                .hasMessage("密码长度不能少于 12 位");
    }

    @Test
    void adminCannotDisableOwnAccount() {
        AuthService authService = authService();

        assertThatThrownBy(() -> authService.setUserEnabled(1L, false,
                new UserAccount(1L, "admin", "Admin", null, UserRole.ADMIN)))
                .isInstanceOf(ApiException.class)
                .hasMessage("不能禁用当前管理员账号");
    }

    private AuthService authService() {
        return new AuthService(JdbcTestSupport.jdbcTemplate(), new BCryptPasswordEncoder(4));
    }
}
