package com.hotel.config;

import com.hotel.security.JwtHandshakeInterceptor;
import com.hotel.security.JwtUtil;
import com.hotel.security.WsHandshakeHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.security.Principal;
import java.util.List;

/**
 * WebSocket（STOMP）配置。
 *
 * <ul>
 *   <li>端点：/ws/chat（客户端经 STOMP 连接，带 ?token=xxx 或 Authorization 头）</li>
 *   <li>应用前缀：/app（客户端发送到 /app/chat.send）</li>
 *   <li>推送：只使用点对点队列 /user/queue/**，不再使用广播主题</li>
 * </ul>
 *
 * <p>原先推送目标是 {@code /topic/chat/{sessionId}}，而会话号是自增主键，任何登录用户
 * 订阅该主题即可持续收到他人会话的完整对话。广播主题无法按归属授权，因此改为
 * 用户点对点队列，并在入站通道拒绝一切非 /user/** 的订阅。</p>
 */
@Slf4j
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtUtil jwtUtil;
    private final CorsProperties corsProperties;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        List<String> origins = corsProperties.getAllowedOrigins();
        var registration = registry.addEndpoint("/ws/chat")
                .addInterceptors(new JwtHandshakeInterceptor(jwtUtil))
                .setHandshakeHandler(new WsHandshakeHandler());
        // 未配置来源白名单时不设置，交由容器按同源策略处理
        if (origins != null && !origins.isEmpty()) {
            registration.setAllowedOriginPatterns(origins.toArray(new String[0]));
        }
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 只启用点对点队列；不注册 /topic 广播，避免出现无法按归属授权的订阅目标
        registry.enableSimpleBroker("/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(subscriptionAuthorizationInterceptor());
    }

    /**
     * 订阅授权拦截器：仅放行 {@code /user/**} 点对点订阅。
     *
     * <p>Spring 会把 {@code /user/queue/x} 解析为当前会话独有的队列，
     * 因此该前缀天然带有归属隔离；其余目的地（含 /topic/**、裸 /queue/**）一律拒绝。</p>
     */
    private ChannelInterceptor subscriptionAuthorizationInterceptor() {
        return new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor =
                        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor == null || !StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                    return message;
                }
                Principal user = accessor.getUser();
                if (user == null) {
                    throw new MessagingException("未认证的连接不允许订阅");
                }
                String destination = accessor.getDestination();
                if (destination == null || !destination.startsWith("/user/")) {
                    log.warn("拒绝非法订阅 user={} destination={}", user.getName(), destination);
                    throw new MessagingException("不允许订阅非点对点目的地");
                }
                return message;
            }
        };
    }
}
