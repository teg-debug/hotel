package com.hotel.common;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Redis 缓存维护工具。
 *
 * <p>统一用 {@code SCAN} 游标遍历替代 {@code KEYS}：后者是 O(N) 且会独占 Redis 单线程，
 * 在键数量增长后会直接阻塞所有其他命令。</p>
 */
@Component
@RequiredArgsConstructor
public class RedisCacheHelper {

    private static final long SCAN_COUNT = 500;

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 按通配模式批量删除键。
     *
     * @return 实际删除的键数量
     */
    public long deleteByPattern(String pattern) {
        Set<String> keys = new HashSet<>();
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(SCAN_COUNT).build();
        stringRedisTemplate.execute((RedisCallback<Void>) connection -> {
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            }
            return null;
        });
        if (keys.isEmpty()) {
            return 0;
        }
        Long removed = stringRedisTemplate.delete(keys);
        return removed == null ? 0 : removed;
    }
}
