package com.codex.sqltuner.config;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class RequestIdFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.trim().isEmpty()) {
            requestId = fastRequestId();
        }
        request.setAttribute("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);
        filterChain.doFilter(request, response);
    }

    private String fastRequestId() {
        // requestId 只用于链路追踪，不需要密码学随机源；避免高并发下 SecureRandom 竞争。
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long mostSignificant = random.nextLong();
        long leastSignificant = random.nextLong();
        mostSignificant = (mostSignificant & 0xffffffffffff0fffL) | 0x0000000000004000L;
        leastSignificant = (leastSignificant & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(mostSignificant, leastSignificant).toString();
    }
}
