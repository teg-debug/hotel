package com.hotel.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * Redis 配置：Key 用 String，Value 用 JSON（保留类型信息以便反序列化回原对象）。
 *
 * <p>类型信息使用白名单校验器：只有本项目包与常用 JDK 值类型允许参与多态反序列化。
 * 原先使用默认校验器（放行任意类型），一旦 Redis 实例可被写入，
 * 就存在通过构造 {@code @class} 字段触发任意类实例化的风险。</p>
 */
@Configuration
public class RedisConfig {

    /** 允许多态反序列化的类型白名单 */
    private static final String[] ALLOWED_TYPE_PREFIXES = {
            "com.hotel.",
            "java.util.",
            "java.lang.",
            "java.time.",
            "java.math."
    };

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        ObjectMapper objectMapper = new ObjectMapper();
        // 支持 LocalDate / LocalDateTime
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // 白名单多态校验器，替代默认的全放行校验器
        BasicPolymorphicTypeValidator.Builder builder = BasicPolymorphicTypeValidator.builder();
        for (String prefix : ALLOWED_TYPE_PREFIXES) {
            builder.allowIfSubType(prefix);
        }
        PolymorphicTypeValidator validator = builder.build();
        objectMapper.activateDefaultTyping(validator, ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);

        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(objectMapper);

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(RedisSerializer.string());
        template.setHashKeySerializer(RedisSerializer.string());
        template.setValueSerializer(serializer);
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();
        return template;
    }
}
