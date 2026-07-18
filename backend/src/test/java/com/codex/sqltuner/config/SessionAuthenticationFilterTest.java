package com.codex.sqltuner.config;

import com.codex.sqltuner.auth.AuthService;
import com.codex.sqltuner.auth.UserAccount;
import com.codex.sqltuner.auth.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class SessionAuthenticationFilterTest {
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void replacesAnonymousAuthenticationWithAuthenticatedSessionUser() throws Exception {
        UserAccount account = new UserAccount(1L, "admin", "Admin", "", UserRole.ADMIN);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute(AuthService.SESSION_USER, account);
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "anonymous-key",
                "anonymousUser",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        new SessionAuthenticationFilter().doFilter(
                request,
                new MockHttpServletResponse(),
                new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isSameAs(account);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void preservesExistingAuthenticatedPrincipal() throws Exception {
        UserAccount account = new UserAccount(1L, "admin", "Admin", "", UserRole.ADMIN);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute(AuthService.SESSION_USER, account);
        UsernamePasswordAuthenticationToken existing = new UsernamePasswordAuthenticationToken(
                "upstream-user",
                null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(existing);

        new SessionAuthenticationFilter().doFilter(
                request,
                new MockHttpServletResponse(),
                new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(existing);
    }
}
