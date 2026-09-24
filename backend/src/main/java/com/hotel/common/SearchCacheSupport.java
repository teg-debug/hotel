package com.hotel.common;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 房源搜索缓存的版本管理。
 *
 * <p>用版本号替代「按前缀删除键」：失效只需把版本号加一，缓存键里带上版本，
 * 旧版本的键会在各自 TTL 到期后自然消失。这样每次失效都是 O(1)，
 * 既不会像 {@code KEYS} 那样阻塞 Redis，也不会随缓存键数量增长而变慢。</p>
 */
@Component
@RequiredArgsConstructor
public class SearchCacheSupport {

    private static final String VERSION_KEY = "hotel:search:ver";

    private final StringRedisTemplate stringRedisTemplate;

    /** 当前缓存版本号；键空间尚未初始化时返回 0 */
    public int currentVersion() {
        String value = stringRedisTemplate.opsForValue().get(VERSION_KEY);
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 使现有房源搜索结果全部失效 */
    public void invalidate() {
        stringRedisTemplate.opsForValue().increment(VERSION_KEY);
    }
}
