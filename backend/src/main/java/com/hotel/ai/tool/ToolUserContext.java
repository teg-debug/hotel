package com.hotel.ai.tool;

import com.hotel.security.UserContext;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 工具内的用户身份解析。
 *
 * <p>同步调用时 {@code UserContext} 的 ThreadLocal 已由拦截器写入，直接读取即可；
 * 但流式调用中工具可能执行在响应式线程上，那里 ThreadLocal 是空的，
 * 因此对话服务会通过 ToolContext 显式传入用户身份，这里优先取它、再回退到 ThreadLocal。</p>
 */
final class ToolUserContext {

    private ToolUserContext() {
    }

    /** 当前用户ID，取不到时返回 null */
    static Long userId(ToolContext toolContext) {
        Object value = value(toolContext, "userId");
        if (value != null) {
            try {
                return Long.valueOf(value.toString());
            } catch (NumberFormatException ignored) {
                // 值不合法时回退到 ThreadLocal
            }
        }
        return UserContext.getUserId();
    }

    /** 当前用户角色，取不到时返回 null */
    static Integer role(ToolContext toolContext) {
        Object value = value(toolContext, "role");
        if (value != null) {
            try {
                return Integer.valueOf(value.toString());
            } catch (NumberFormatException ignored) {
                // 值不合法时回退到 ThreadLocal
            }
        }
        return UserContext.getRole();
    }

    private static Object value(ToolContext toolContext, String key) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        return toolContext.getContext().get(key);
    }
}
