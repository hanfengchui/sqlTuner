package com.codex.sqltuner.auth;

import com.codex.sqltuner.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpSession;
import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {
    private final AuthService authService;

    public AdminUserController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping
    public ApiResponse<List<AdminUserView>> list(HttpSession session) {
        authService.requireAdminSession(currentUser(session));
        return ApiResponse.ok(authService.listUsers());
    }

    @PostMapping
    public ApiResponse<AdminUserView> create(@Valid @RequestBody AdminUserCreateRequest request,
                                             HttpSession session) {
        authService.requireAdminSession(currentUser(session));
        return ApiResponse.ok(authService.createUser(request));
    }

    @PutMapping("/{id}/enabled")
    public ApiResponse<AdminUserView> setEnabled(@PathVariable("id") Long id,
                                                 @Valid @RequestBody AdminUserEnabledRequest request,
                                                 HttpSession session) {
        UserAccount admin = currentUser(session);
        authService.requireAdminSession(admin);
        return ApiResponse.ok(authService.setUserEnabled(id, request.getEnabled().booleanValue(), admin));
    }

    @PostMapping("/{id}/password")
    public ApiResponse<AdminUserView> resetPassword(@PathVariable("id") Long id,
                                                    @Valid @RequestBody AdminPasswordResetRequest request,
                                                    HttpSession session) {
        authService.requireAdminSession(currentUser(session));
        return ApiResponse.ok(authService.resetPassword(id, request.getPassword()));
    }

    private UserAccount currentUser(HttpSession session) {
        return session == null ? null : (UserAccount) session.getAttribute(AuthService.SESSION_USER);
    }
}
