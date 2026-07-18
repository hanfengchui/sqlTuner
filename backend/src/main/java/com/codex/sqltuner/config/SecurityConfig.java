package com.codex.sqltuner.config;

import com.codex.sqltuner.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import javax.servlet.http.HttpServletResponse;

@Configuration
public class SecurityConfig {
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public WebSecurityCustomizer healthSecurityBypass() {
        // 健康检查无用户数据且由负载均衡器高频访问，直接绕过会话与 CSRF 过滤链。
        return web -> web.ignoring().antMatchers("/api/health/live", "/api/health/ready");
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public FilterRegistrationBean<SessionAuthenticationFilter> sessionAuthenticationFilterRegistration(
            SessionAuthenticationFilter filter) {
        FilterRegistrationBean<SessionAuthenticationFilter> registration =
                new FilterRegistrationBean<SessionAuthenticationFilter>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   final ObjectMapper objectMapper,
                                                   SessionAuthenticationFilter sessionAuthenticationFilter) throws Exception {
        http
                .httpBasic().disable()
                .formLogin().disable()
                .logout().disable()
                .authorizeRequests()
                .antMatchers("/api/auth/csrf", "/assets/**", "/", "/index.html").permitAll()
                .antMatchers("/api/auth/login", "/api/auth/logout", "/api/auth/me").permitAll()
                .antMatchers("/api/admin/**").hasRole("ADMIN")
                .antMatchers("/api/**").authenticated()
                .anyRequest().permitAll()
                .and()
                .csrf()
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .and()
                .exceptionHandling()
                .authenticationEntryPoint((request, response, exception) -> {
                    String requestId = (String) request.getAttribute("requestId");
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    objectMapper.writeValue(response.getWriter(), ApiResponse.fail(
                            "UNAUTHORIZED", "请先登录", requestId, null));
                })
                .accessDeniedHandler((request, response, exception) -> {
                    boolean csrf = exception instanceof MissingCsrfTokenException
                            || exception instanceof InvalidCsrfTokenException;
                    String requestId = (String) request.getAttribute("requestId");
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json;charset=UTF-8");
                    objectMapper.writeValue(response.getWriter(), ApiResponse.fail(
                            csrf ? "CSRF_INVALID" : "FORBIDDEN",
                            csrf ? "CSRF Token 无效或缺失" : "禁止访问",
                            requestId,
                            null));
                })
                .and()
                .addFilterBefore(sessionAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
