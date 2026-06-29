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
import javax.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<UserView> login(@Valid @RequestBody LoginRequest request, HttpSession session) {
        log.info("login param 入参: username: {}", request.getUsername());
        UserAccount account = authService.login(request.getUsername(), request.getPassword());
        session.setAttribute(AuthService.SESSION_USER, account);
        return ApiResponse.ok(UserView.from(account));
    }

    @PostMapping("/logout")
    public ApiResponse<Boolean> logout(HttpSession session) {
        Object account = session.getAttribute(AuthService.SESSION_USER);
        log.info("logout param 入参: user: {}", account == null ? "anonymous" : ((UserAccount) account).getUsername());
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
}
