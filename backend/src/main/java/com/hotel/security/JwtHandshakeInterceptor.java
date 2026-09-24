package com.hotel.security;

import io.jsonwebtoken.Claims;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket 握手鉴权：从查询参数 token 或 Authorization 头解析 JWT，
 * 校验通过后将 userId/username/role 写入握手属性（供 WsHandshakeHandler 构造 Principal）。
 */
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtUtil jwtUtil;

    public JwtHandshakeInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = extractToken(request);
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            Claims claims = jwtUtil.parseToken(token);
            attributes.put("userId", Long.valueOf(claims.getSubject()));
            attributes.put("username", claims.get("username", String.class));
            attributes.put("role", claims.get("role", Integer.class));
            return true;
        } catch (Exception e) {
            return false; // Token 无效/过期 → 拒绝握手
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需处理
    }

    private String extractToken(ServerHttpRequest request) {
        String query = request.getURI().getQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                if (pair.startsWith("token=")) {
                    return pair.substring("token=".length());
                }
            }
        }
        String auth = request.getHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7);
        }
        return null;
    }
}
