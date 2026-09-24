package com.hotel.ai.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 用户点对点推送的统一出口。
 *
 * <p>WebSocket 用的是内置 SimpleBroker，它只在当前 JVM 内维护「用户 → 连接」注册表，
 * {@code convertAndSendToUser} 因此只能投给连在本实例上的会话。坐席连在实例 B、
 * 会话在实例 A 触发转人工时，A 在自己的注册表里找不到收件人，消息被静默丢弃——
 * 这正是多实例部署下「转人工没反应」的原因。</p>
 *
 * <p>这里改成先发到 Redis 频道，所有实例（包括发送方自己）都订阅该频道并各自在本机投递：
 * 谁持有连接谁就投出去，连接自然收敛。发送方也走订阅路径，保证每条消息只投递一次，
 * 不需要实例标识或去重逻辑。</p>
 *
 * <p>Redis 不可用时退回本机投递：单实例部署仍完全可用，多实例下退化为改造前的行为。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserPushPublisher {

    /** 跨实例推送频道 */
    public static final String CHANNEL = "chat:push";

    private static final String FIELD_USER_ID = "userId";
    private static final String FIELD_DESTINATION = "destination";
    private static final String FIELD_PAYLOAD = "payload";

    private final StringRedisTemplate stringRedisTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 推送给指定用户的点对点队列。
     *
     * <p>载荷以 JSON 树的形式随频道传递，接收端再序列化给 STOMP 客户端，
     * 因此跨实例前后的报文体与改造前逐字节一致，前端无需任何改动。</p>
     *
     * @param userId      收件人用户标识
     * @param destination 点对点目的地，如 {@code /queue/chat}
     * @param payload     消息体（VO 或 Map 均可）
     */
    public void push(String userId, String destination, Object payload) {
        if (userId == null || userId.isBlank() || destination == null || destination.isBlank()
                || payload == null) {
            return;
        }
        try {
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put(FIELD_USER_ID, userId);
            envelope.put(FIELD_DESTINATION, destination);
            envelope.set(FIELD_PAYLOAD, objectMapper.valueToTree(payload));
            stringRedisTemplate.convertAndSend(CHANNEL, objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            log.warn("跨实例推送不可用，退回本机投递 userId={} destination={}", userId, destination, e);
            deliverLocally(userId, destination, payload);
        }
    }

    /** 在本机投递：只有收件人的连接恰好在本实例上时才会真正送出，否则静默无接收方 */
    void deliverLocally(String userId, String destination, Object payload) {
        try {
            messagingTemplate.convertAndSendToUser(userId, destination, payload);
        } catch (Exception e) {
            log.error("本机推送失败 userId={} destination={}", userId, destination, e);
        }
    }
}
