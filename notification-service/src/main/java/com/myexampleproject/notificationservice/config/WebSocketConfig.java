package com.myexampleproject.notificationservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Frontend sẽ kết nối vào đường dẫn này: http://localhost:8087/ws
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("http://localhost:3001", "*")
                .withSockJS(); // Hỗ trợ fallback nếu trình duyệt không có WebSocket
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Prefix cho các topic mà Client sẽ subscribe (đăng ký nghe)
        registry.enableSimpleBroker("/topic");
        // Prefix cho các message từ Client gửi lên Server (nếu có)
        registry.setApplicationDestinationPrefixes("/app");
    }
}