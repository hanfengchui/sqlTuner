package com.codex.sqltuner.auth;

import javax.servlet.http.HttpSession;

public final class CurrentUser {
    private CurrentUser() {
    }

    public static UserAccount require(HttpSession session) {
        UserAccount account = (UserAccount) session.getAttribute(AuthService.SESSION_USER);
        if (account == null) {
            throw new IllegalArgumentException("请先登录");
        }
        return account;
    }

    public static void requireAdmin(HttpSession session) {
        UserAccount account = require(session);
        if (account.getRole() != UserRole.ADMIN) {
            throw new IllegalArgumentException("需要管理员权限");
        }
    }
}
