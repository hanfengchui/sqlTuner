package com.codex.sqltuner.auth;

import com.codex.sqltuner.common.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<UserView> login(@Valid @RequestBody LoginRequest request,
                                       HttpSession session,
                                       HttpServletRequest servletRequest) {
        log.info("login param 入参: username: {}", request.getUsername());
        UserAccount account = authService.login(request.getUsername(), request.getPassword(), clientIp(servletRequest));
        servletRequest.changeSessionId();
        session.setAttribute(AuthService.SESSION_USER, account);
        authService.registerSession(account, session);
        return ApiResponse.ok(UserView.from(account));
    }

    @PostMapping("/logout")
    public ApiResponse<Boolean> logout(HttpSession session) {
        Object account = session.getAttribute(AuthService.SESSION_USER);
        log.info("logout param 入参: user: {}", account == null ? "anonymous" : ((UserAccount) account).getUsername());
        if (account instanceof UserAccount) {
            authService.unregisterSession((UserAccount) account, session);
        }
        session.invalidate();
        return ApiResponse.ok(Boolean.TRUE);
    }

    @GetMapping("/me")
    public ApiResponse<UserView> me(HttpSession session) {
        UserAccount account = (UserAccount) session.getAttribute(AuthService.SESSION_USER);
        if (account == null) {
            return ApiResponse.ok(null);
        }
        return ApiResponse.ok(UserView.from(account));
    }

    @GetMapping("/csrf")
    public ApiResponse<Map<String, String>> csrf(javax.servlet.http.HttpServletRequest request) {
        org.springframework.security.web.csrf.CsrfToken token =
                (org.springframework.security.web.csrf.CsrfToken) request.getAttribute(org.springframework.security.web.csrf.CsrfToken.class.getName());
        Map<String, String> body = new HashMap<String, String>();
        if (token != null) {
            body.put("headerName", token.getHeaderName());
            body.put("parameterName", token.getParameterName());
            body.put("token", token.getToken());
        }
        return ApiResponse.ok(body);
    }

    private String clientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        return remoteAddr == null || remoteAddr.trim().isEmpty() ? "unknown" : remoteAddr.trim();
    }
}
