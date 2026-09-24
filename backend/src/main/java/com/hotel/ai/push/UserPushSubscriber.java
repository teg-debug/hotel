package com.hotel.ai.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 跨实例推送的消费端：订阅 Redis 频道，把报文投递给本实例持有的连接。
 *
 * <p>每个实例都会收到全量报文，但只有真正持有收件人连接的那个实例能把消息送出去，
 * 其余实例的投递是无接收方的空操作，因此不需要实例标识，也不会重复投递。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserPushSubscriber implements MessageListener {

    private static final String FIELD_USER_ID = "userId";
    private static final String FIELD_DESTINATION = "destination";
    private static final String FIELD_PAYLOAD = "payload";

    private final UserPushPublisher userPushPublisher;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            JsonNode envelope = objectMapper.readTree(body);
            String userId = envelope.path(FIELD_USER_ID).asText("");
            String destination = envelope.path(FIELD_DESTINATION).asText("");
            JsonNode payload = envelope.path(FIELD_PAYLOAD);
            if (userId.isBlank() || destination.isBlank() || payload.isMissingNode() || payload.isNull()) {
                log.warn("忽略结构非法的跨实例推送报文 body={}", body);
                return;
            }
            // 载荷以 JsonNode 传递，交给消息转换器后与直接投递的对象序列化结果一致
            userPushPublisher.deliverLocally(userId, destination, payload);
        } catch (Exception e) {
            log.error("处理跨实例推送失败 body={}", body, e);
        }
    }
}
