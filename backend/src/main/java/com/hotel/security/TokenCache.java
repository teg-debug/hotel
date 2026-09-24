package com.hotel.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录态的本地（单 JVM）缓存，用于免掉「每个请求都去 Redis 比一次 token」的往返。
 *
 * <p>鉴权的权威来源仍是 Redis：登录时写入 {@code auth:token:{userId}}，拦截器与之比对，
 * 从而支持登出立即失效与单端登录互踢。这个缓存只是把「刚刚校验过的那对
 * (userId, token)」在极短时间里记在本进程，属于用一致性换延迟：</p>
 *
 * <ul>
 *   <li>默认<b>关闭</b>（{@code app.security.token-cache-millis=0}），语义与原先完全一致；</li>
 *   <li>开启后，本实例内不再逐请求访问 Redis，代价是<b>跨实例</b>的登出/互踢最多滞后 TTL；
 *       同实例的登录与登出会立即清除本地条目，因此单实例部署下行为不变；</li>
 *   <li>TTL 建议取百毫秒到秒级；取值越大省得越多，滞后也越明显。</li>
 * </ul>
 */
@Slf4j
@Component
public class TokenCache {

    /** 条目上限：超过后先清过期项，仍超限则整体清空（缓存丢了只是退化成查 Redis） */
    private static final int MAX_ENTRIES = 10_000;

    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();

    private final long ttlMillis;

    public TokenCache(@Value("${app.security.token-cache-millis:0}") long ttlMillis) {
        this.ttlMillis = Math.max(0, ttlMillis);
        if (this.ttlMillis > 0) {
            log.info("登录态本地缓存已开启：{} ms（本实例内免去逐请求 Redis 校验；"
                    + "跨实例的登出/互踢最多滞后该时长）", this.ttlMillis);
        }
    }

    /** 本地是否已能确认该 token 有效（关闭时恒为 false，调用方继续查 Redis） */
    public boolean matches(Long userId, String token) {
        if (ttlMillis == 0) {
            return false;
        }
        Entry entry = entries.get(userId);
        if (entry == null) {
            return false;
        }
        if (entry.expiresAt() < System.currentTimeMillis()) {
            entries.remove(userId, entry);
            return false;
        }
        return entry.token().equals(token);
    }

    /** Redis 校验通过后记住这对凭据 */
    public void remember(Long userId, String token) {
        if (ttlMillis == 0) {
            return;
        }
        if (entries.size() >= MAX_ENTRIES) {
            long now = System.currentTimeMillis();
            entries.values().removeIf(entry -> entry.expiresAt() < now);
            if (entries.size() >= MAX_ENTRIES) {
                entries.clear();
            }
        }
        entries.put(userId, new Entry(token, System.currentTimeMillis() + ttlMillis));
    }

    /** 登录、登出等会改变凭据的操作立即清除本地条目 */
    public void forget(Long userId) {
        if (userId != null) {
            entries.remove(userId);
        }
    }

    private record Entry(String token, long expiresAt) {
    }
}
