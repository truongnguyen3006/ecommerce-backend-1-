package com.myexampleproject.cartservice.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule; // <-- Thêm import cho Java Time
import com.fasterxml.jackson.databind.SerializationFeature; // <-- Thêm import

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.*;

@Configuration
public class  RedisConfig {

    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        return new LettuceConnectionFactory();
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(LettuceConnectionFactory connFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connFactory);

        // --- SỬA LỖI TẠI ĐÂY ---

        // 1. Tạo một ObjectMapper được cấu hình
        ObjectMapper om = new ObjectMapper();

        // Kích hoạt tính năng lưu trữ Type Info (thông tin class)
        // Bằng cách này, Redis sẽ lưu: "@class": "com.myexample.CartEntity", ...
        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType(Object.class) // Cho phép bất kỳ class nào
                .build();
        om.activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);

        // Hỗ trợ các kiểu thời gian của Java 8 (ví dụ: Duration)
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // 2. Tạo Serializer với ObjectMapper đã cấu hình
        Jackson2JsonRedisSerializer<Object> valSer = new Jackson2JsonRedisSerializer<>(om, Object.class);

        // --- HẾT SỬA LỖI ---

        // Key luôn là String
        StringRedisSerializer keySer = new StringRedisSerializer();

        template.setKeySerializer(keySer);
        template.setValueSerializer(valSer);
        template.setHashKeySerializer(keySer);
        template.setHashValueSerializer(valSer);

        template.afterPropertiesSet();
        return template;
    }
}