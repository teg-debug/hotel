package com.hotel.ai;

import org.springframework.ai.chat.model.ToolContext;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具调用记录器：记录一次模型调用中实际触发了哪些工具。
 *
 * <p>Spring AI 的最终 {@code ChatResponse} 只包含自然语言回答，不含工具调用信息，
 * 因此无法从响应反查；这里在工具回调外层做包装，把工具名记下来，
 * 供对话服务写入 {@code chat_message.tool_name}。</p>
 *
 * <p>记录维度用「调用标识（会话ID）」而不是线程：流式调用中工具执行在响应式线程上，
 * 而调用方在业务线程上，ThreadLocal 跨不过去，只能记到业务线程自己的集合里，
 * 结果永远是空的。调用标识随 ToolContext 一起传给工具，因此哪个线程执行都能记上。
 * 仅在工具没有收到 ToolContext 时才回退到线程维度。</p>
 *
 * <p>同一会话的多次提问是串行的（前端在一次回答结束前不允许再次发送），
 * 因此按会话累积不会出现相互覆盖。</p>
 */
public final class ToolInvocationRecorder {

    /** tool_name 字段长度上限 */
    private static final int MAX_TOOL_NAME_LENGTH = 50;

    /** 调用标识 -> 本次已触发的工具名 */
    private static final Map<String, Set<String>> BY_CALL_KEY = new ConcurrentHashMap<>();

    /** 无 ToolContext 时的回退维度 */
    private static final ThreadLocal<Set<String>> BY_THREAD = new ThreadLocal<>();

    private ToolInvocationRecorder() {
    }

    /** 开始记录：每次模型调用前调用，同一调用标识的旧记录会被丢弃 */
    public static void begin(String callKey) {
        BY_CALL_KEY.put(callKey, Collections.synchronizedSet(new LinkedHashSet<>()));
        BY_THREAD.set(new LinkedHashSet<>());
    }

    /** 记录一次工具调用 */
    public static void record(String toolName, ToolContext toolContext) {
        if (toolName == null || toolName.isBlank()) {
            return;
        }
        String callKey = callKeyOf(toolContext);
        if (callKey != null) {
            Set<String> recorded = BY_CALL_KEY.get(callKey);
            if (recorded != null) {
                recorded.add(toolName);
                return;
            }
        }
        Set<String> threadRecorded = BY_THREAD.get();
        if (threadRecorded != null) {
            threadRecorded.add(toolName);
        }
    }

    /** 取出并清理本次记录的工具名，用逗号连接；没有调用工具时返回 null */
    public static String finish(String callKey) {
        BY_THREAD.remove();
        Set<String> recorded = BY_CALL_KEY.remove(callKey);
        if (recorded == null || recorded.isEmpty()) {
            return null;
        }
        String joined;
        synchronized (recorded) {
            joined = String.join(",", recorded);
        }
        return joined.length() <= MAX_TOOL_NAME_LENGTH
                ? joined : joined.substring(0, MAX_TOOL_NAME_LENGTH);
    }

    private static String callKeyOf(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object sessionId = toolContext.getContext().get("sessionId");
        return sessionId == null ? null : sessionId.toString();
    }
}
