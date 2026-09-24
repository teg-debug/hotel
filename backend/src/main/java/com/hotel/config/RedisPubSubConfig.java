package com.hotel.config;

import com.hotel.ai.push.UserPushPublisher;
import com.hotel.ai.push.UserPushSubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis 发布订阅配置：承载跨实例的用户点对点推送。
 *
 * <p>Spring Boot 的 {@code RedisAutoConfiguration} 只提供 {@code RedisTemplate} 与
 * {@code StringRedisTemplate}，不会自动创建监听容器，必须在这里显式声明，
 * 否则频道没有任何订阅者，推送会发出去却无人接收。</p>
 */
@Configuration
public class RedisPubSubConfig {

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            UserPushSubscriber userPushSubscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(userPushSubscriber, new ChannelTopic(UserPushPublisher.CHANNEL));
        return container;
    }
}
