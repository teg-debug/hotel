package com.hotel.security;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;

/**
 * 将握手属性中的 userId 构造为 WebSocket Principal，
 * 使 STOMP 消息处理器可通过 Principal 获取当前用户。
 */
public class WsHandshakeHandler extends DefaultHandshakeHandler {

    @Override
    protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler,
                                      Map<String, Object> attributes) {
        Object userId = attributes.get("userId");
        if (userId == null) {
            return null;
        }
        return new WsPrincipal(String.valueOf(userId));
    }

    /** 简单 Principal：name=userId */
    public record WsPrincipal(String name) implements Principal {
        @Override
        public String getName() {
            return name();
        }
    }
}
