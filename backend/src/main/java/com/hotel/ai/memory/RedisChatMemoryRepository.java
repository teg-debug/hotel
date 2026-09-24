package com.hotel.ai.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Redis 的多轮对话记忆仓库：把原本存在 JVM 堆里的模型上下文外置，
 * 使任意实例都能读到同一个会话的完整上下文。
 *
 * <p>存储结构：一个会话一个 String 值，内容是消息数组的 JSON。
 * {@code MessageWindowChatMemory} 每次 {@code saveAll} 传进来的都是「截断后的完整窗口」，
 * 语义是覆盖而非追加，所以单键覆盖写正好对应它的调用约定，
 * 也避免了列表追加模式下需要用 Lua/事务保证原子性的问题。</p>
 *
 * <p>TTL 与会话闲置回收阈值一致：会话一旦被 {@code ChatSessionTimeoutTask} 回收，
 * 上下文也没有继续保留的价值，两者同时过期不会出现「记忆还在但会话已结束」的中间态。</p>
 *
 * <p>读写都做了降级：Redis 不可用时读返回空列表（该轮失去多轮上下文，但仍能正常回答），
 * 写只记日志（下一轮可能丢失上下文）。相比直接抛出异常导致整个客服链路不可用，
 * 这种降级把故障影响限制在「上下文能力」这一层。</p>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    /** 键前缀：chat:mem:{会话ID} */
    public static final String KEY_PREFIX = "chat:mem:";

    /** SCAN 单批游标数量，避免一次拉取过多键 */
    private static final long SCAN_COUNT = 500;

    private final StringRedisTemplate stringRedisTemplate;
    private final ChatMemoryMessageCodec codec;

    @Value("${app.chat.session-timeout-minutes:30}")
    private int sessionTimeoutMinutes;

    @Override
    public List<String> findConversationIds() {
        List<String> conversationIds = new ArrayList<>();
        try {
            stringRedisTemplate.execute((RedisCallback<Void>) connection -> {
                ScanOptions options = ScanOptions.scanOptions()
                        .match(KEY_PREFIX + "*")
                        .count(SCAN_COUNT)
                        .build();
                try (Cursor<byte[]> cursor = connection.scan(options)) {
                    while (cursor.hasNext()) {
                        String key = new String(cursor.next(), StandardCharsets.UTF_8);
                        conversationIds.add(key.substring(KEY_PREFIX.length()));
                    }
                }
                return null;
            });
        } catch (Exception e) {
            // 与 RedisCacheHelper 一致：用 SCAN 而非 KEYS，避免 O(N) 阻塞 Redis 单线程
            log.error("枚举会话记忆键失败", e);
        }
        return conversationIds;
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        try {
            String json = stringRedisTemplate.opsForValue().get(key(conversationId));
            return codec.decode(json);
        } catch (Exception e) {
            log.error("读取会话记忆失败 conversationId={}，本轮按无历史上下文处理", conversationId, e);
            return List.of();
        }
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        if (messages == null || messages.isEmpty()) {
            // 窗口为空表示该会话已无上下文，直接删除键，避免留下空值
            deleteByConversationId(conversationId);
            return;
        }
        try {
            stringRedisTemplate.opsForValue()
                    .set(key(conversationId), codec.encode(messages), memoryTtl());
        } catch (Exception e) {
            log.error("写入会话记忆失败 conversationId={}，后续轮次可能丢失上下文", conversationId, e);
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        try {
            stringRedisTemplate.delete(key(conversationId));
        } catch (Exception e) {
            log.error("清除会话记忆失败 conversationId={}", conversationId, e);
        }
    }

    private String key(String conversationId) {
        return KEY_PREFIX + conversationId;
    }

    private Duration memoryTtl() {
        return Duration.ofMinutes(Math.max(1, sessionTimeoutMinutes));
    }
}
