package com.myexampleproject.orderservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Tạo serializer
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();

        // 1. Cấu hình Key (Key chính: "saga:order:123")
        template.setKeySerializer(stringSerializer);

        // 2. Cấu hình Value (Nếu dùng opsForValue)
        template.setValueSerializer(jsonSerializer);

        // ==========================================================
        // SỬA LỖI Ở ĐÂY
        // ==========================================================

        // 3. Cấu hình Hash Key (Key bên trong Hash: "totalItems", "request"...)
        template.setHashKeySerializer(stringSerializer);

        // 4. Cấu hình Hash Value (Value bên trong Hash: object OrderRequest)
        template.setHashValueSerializer(jsonSerializer);

        // ==========================================================

        template.afterPropertiesSet(); // <-- Thêm dòng này để áp dụng
        return template;
    }
}