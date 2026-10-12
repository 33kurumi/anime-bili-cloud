package com.abc.common.utils;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Extract user info from request headers set by gateway filter.
 */
public final class UserContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USERNAME = new ThreadLocal<>();
    private static final ThreadLocal<String> ROLE = new ThreadLocal<>();

    private UserContext() {}

    public static void set(Long userId, String username, String role) {
        USER_ID.set(userId);
        USERNAME.set(username);
        ROLE.set(role);
    }

    public static Long getUserId() {
        return USER_ID.get();
    }

    public static String getUsername() {
        return USERNAME.get();
    }

    public static String getRole() {
        return ROLE.get();
    }

    public static void clear() {
        USER_ID.remove();
        USERNAME.remove();
        ROLE.remove();
    }

    /**
     * Set context from request headers added by gateway JWT filter.
     */
    public static void setFromRequest(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        String username = request.getHeader("X-Username");
        String role = request.getHeader("X-Role");
        if (userId != null) {
            set(Long.parseLong(userId), username, role);
        }
    }
}
