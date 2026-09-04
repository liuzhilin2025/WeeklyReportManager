package com.practice.weeklyreportmanager.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Slf4j
@Configuration
@EnableCaching  // 开启缓存支持
public class RedisConfig {
    /**
     * 自定义 RedisTemplate，使用 JSON 序列化
     * Redis 序列化器用于将 Java 对象转换为可存储在 Redis 中的字节序列，以及将字节序列转换回 Java 对象。
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        // 创建 RedisTemplate 实例
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        // 设置连接工厂
        template.setConnectionFactory(factory);

        // 用 String 序列化 Key，避免出现乱码
        StringRedisSerializer stringSerializer = new StringRedisSerializer();

        Jackson2JsonRedisSerializer<Object> jsonRedisSerializer = new Jackson2JsonRedisSerializer<>(Object.class);
        // 设置 key 的序列化器
        template.setKeySerializer(stringSerializer);
        // 设置 hash key 的序列化器
        template.setHashKeySerializer(stringSerializer);

        // Value 用 Jackson 序列化（可以存对象）
        template.setValueSerializer(jsonRedisSerializer);
        template.setHashValueSerializer(jsonRedisSerializer);

        // 初始化模板
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 自定义 RedisCacheManager
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // 1. 配置 ObjectMapper，支持 Java 8 日期
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // ★ 允许反序列化时忽略未知字段（防止版本不匹配）
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // ★ 开启默认类型信息（@class），保证缓存反序列化时能还原成 Page、WeekGroupVO 等原类型
        objectMapper.activateDefaultTyping(LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);

        // 2. ★ 使用 GenericJackson2JsonRedisSerializer（会保存类型信息）
        GenericJackson2JsonRedisSerializer serializer =
                new GenericJackson2JsonRedisSerializer(objectMapper);

        // 3. 全局默认缓存配置
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                // 默认过期时间5分钟
                .entryTtl(Duration.ofMinutes(5))
                // 设置 key 序列化器
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                // 设置 value 序列化器
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(serializer))
                .disableCachingNullValues();

        // 创建缓存管理器
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config) // 设置缓存配置
                .build();
    }


}
