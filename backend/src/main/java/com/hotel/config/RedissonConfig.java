package com.hotel.config;

import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * Redisson 分布式锁客户端。
 *
 * <p>配置统一从 Spring Boot 的 {@link RedisProperties} 读取，而不是各自写 {@code @Value}
 * 逐个取。原先只取了 host 与 port，其余项 Redisson 会静默使用自己的默认值：
 * 本地 Redis 通常没开 requirepass，密码漏配完全看不出来；一旦部署到开启 {@code requirepass}
 * 的生产环境就直接连不上，分布式锁以及依赖它的会话超时回收会整体失效，
 * 而报错发生的地点离根因很远，排查成本很高。库号漏配同理——两边读写不同的库，
 * 表现为「锁没生效」而不是「连不上」，更难定位。</p>
 *
 * <p>这里把 Spring Data Redis 实际使用的那份配置映射过来：地址、库号、用户名、密码、
 * 客户端名、命令超时与连接超时。两边读同一个来源，不会再出现配置漂移。</p>
 *
 * <p>SSL 只把地址协议头换成 {@code rediss://}，其余沿用 Redisson 的安全默认值
 * （JDK SSL 实现 + 开启端点校验）；使用自签证书时需另行配置信任库。</p>
 */
@Slf4j
@Configuration
public class RedissonConfig {

    /** 未显式配置超时时的兜底值（毫秒），与 Redisson 自身默认值保持一致 */
    private static final int FALLBACK_TIMEOUT_MILLIS = 3000;
    private static final int FALLBACK_CONNECT_TIMEOUT_MILLIS = 10000;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(RedisProperties properties) {
        // 只打印"是否配置了密码"，不打印密码本身
        log.info("Redisson 连接 Redis {} 库={} 用户名={} 密码={} SSL={}",
                address(properties),
                properties.getDatabase(),
                StringUtils.hasText(properties.getUsername()) ? properties.getUsername() : "未配置",
                StringUtils.hasText(properties.getPassword()) ? "已配置" : "未配置",
                isSslEnabled(properties) ? "开启" : "关闭");
        return Redisson.create(buildConfig(properties));
    }

    /** 把 Spring Boot 的 Redis 配置映射成 Redisson 配置 */
    static Config buildConfig(RedisProperties properties) {
        if (StringUtils.hasText(properties.getUrl())) {
            log.warn("检测到 spring.data.redis.url，Redisson 只读取 host/port 配置；"
                    + "两者指向不同实例时会出现不一致，建议只保留一种配置方式");
        }

        Config config = new Config();
        SingleServerConfig server = config.useSingleServer()
                .setAddress(address(properties))
                .setDatabase(properties.getDatabase());

        // 空字符串不能下传：Redis 未开启鉴权时，AUTH "" 会被服务端当成错误命令拒绝
        if (StringUtils.hasText(properties.getUsername())) {
            server.setUsername(properties.getUsername());
        }
        if (StringUtils.hasText(properties.getPassword())) {
            server.setPassword(properties.getPassword());
        }
        if (StringUtils.hasText(properties.getClientName())) {
            server.setClientName(properties.getClientName());
        }

        int timeout = toMillis(properties.getTimeout(), FALLBACK_TIMEOUT_MILLIS);
        if (timeout > 0) {
            server.setTimeout(timeout);
        }
        int connectTimeout = toMillis(properties.getConnectTimeout(), FALLBACK_CONNECT_TIMEOUT_MILLIS);
        if (connectTimeout > 0) {
            server.setConnectTimeout(connectTimeout);
        }
        return config;
    }

    private static String address(RedisProperties properties) {
        return (isSslEnabled(properties) ? "rediss://" : "redis://")
                + properties.getHost() + ":" + properties.getPort();
    }

    private static boolean isSslEnabled(RedisProperties properties) {
        RedisProperties.Ssl ssl = properties.getSsl();
        return ssl != null && ssl.isEnabled();
    }

    /** 超时统一换算成毫秒；未配置或配置为 0 时返回兜底值 */
    private static int toMillis(Duration duration, int fallback) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return fallback;
        }
        long millis = duration.toMillis();
        return millis > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) millis;
    }
}
