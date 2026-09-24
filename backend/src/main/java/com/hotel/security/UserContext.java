package com.hotel.security;

import java.util.function.Supplier;

/**
 * 当前登录用户上下文（ThreadLocal）：由 JwtInterceptor 写入，请求结束后清理。
 *
 * <p>异步与响应式线程不会继承 ThreadLocal，因此需要显式传递身份的场合
 * （SSE 流式输出、工具回调）改用 {@link #runAs} 还原上下文。</p>
 */
public class UserContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USERNAME = new ThreadLocal<>();
    private static final ThreadLocal<Integer> ROLE = new ThreadLocal<>();

    public static void set(Long userId, String username, Integer role) {
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

    public static Integer getRole() {
        return ROLE.get();
    }

    public static void clear() {
        USER_ID.remove();
        USERNAME.remove();
        ROLE.remove();
    }

    /**
     * 以指定用户身份执行一段逻辑，执行结束后恢复原有上下文。
     *
     * <p>用于异步线程与工具回调：这些位置的 ThreadLocal 是空的，
     * 而下游服务普遍通过 {@code UserContext.getUserId()} 做数据隔离，
     * 不还原上下文会导致查不到数据或越权判定失败。</p>
     */
    public static <T> T runAs(Long userId, String username, Integer role, Supplier<T> action) {
        Long previousUserId = USER_ID.get();
        String previousUsername = USERNAME.get();
        Integer previousRole = ROLE.get();
        set(userId, username, role);
        try {
            return action.get();
        } finally {
            USER_ID.set(previousUserId);
            USERNAME.set(previousUsername);
            ROLE.set(previousRole);
        }
    }

    /** {@link #runAs} 的无返回值版本 */
    public static void runAs(Long userId, String username, Integer role, Runnable action) {
        runAs(userId, username, role, () -> {
            action.run();
            return null;
        });
    }
}

