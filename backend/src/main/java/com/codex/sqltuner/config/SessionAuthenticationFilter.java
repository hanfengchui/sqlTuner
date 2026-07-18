package com.codex.sqltuner.config;

import com.codex.sqltuner.auth.AuthService;
import com.codex.sqltuner.auth.UserAccount;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Collections;

/** 将现有 HttpSession 登录态桥接到 Spring Security 的角色校验。 */
@Component
public class SessionAuthenticationFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (session != null && (current == null || current instanceof AnonymousAuthenticationToken)) {
            Object value = session.getAttribute(AuthService.SESSION_USER);
            if (value instanceof UserAccount) {
                UserAccount account = (UserAccount) value;
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        account,
                        null,
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + account.getRole().name())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        filterChain.doFilter(request, response);
    }
}
