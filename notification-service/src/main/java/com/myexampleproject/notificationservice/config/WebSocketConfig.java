package com.myexampleproject.notificationservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.config.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import java.util.Arrays;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final JwtDecoder decoder;
    private final RestTemplate client;
    private final String origins;
    private final String orderUrl;
    public WebSocketConfig(JwtDecoder decoder, RestTemplate client, @Value("${app.cors.allowed-origins}") String origins,
                           @Value("${order.service.base-url:http://localhost:8086}") String orderUrl) {
        this.decoder = decoder;this.client = client;this.origins = origins;this.orderUrl = orderUrl;
    }
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] allowed = Arrays.stream(origins.split(",")).map(String::trim).toArray(String[]::new);
        if (Arrays.asList(allowed).contains("*")) throw new IllegalArgumentException("WebSocket requires explicit origins");
        registry.addEndpoint("/ws").setAllowedOrigins(allowed).withSockJS();
    }
    public void configureMessageBroker(MessageBrokerRegistry registry) { registry.enableSimpleBroker("/topic");registry.setApplicationDestinationPrefixes("/app"); }
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (headers == null || headers.getCommand() == null) return message;
                if (headers.getCommand() == StompCommand.CONNECT) {
                    String authorization = headers.getFirstNativeHeader("Authorization");
                    if (authorization == null || !authorization.startsWith("Bearer ")) throw new MessagingException("Authentication required");
                    Jwt jwt = decoder.decode(authorization.substring(7));headers.setUser(new JwtAuthenticationToken(jwt));
                } else if (headers.getCommand() == StompCommand.SUBSCRIBE) {
                    if (!(headers.getUser() instanceof JwtAuthenticationToken auth)) throw new MessagingException("Authentication required");
                    decoder.decode(auth.getToken().getTokenValue());
                    String destination = headers.getDestination();
                    if (destination == null || !destination.matches("/topic/order/[A-Za-z0-9-]{1,64}")) throw new MessagingException("Subscription denied");
                    String order = destination.substring("/topic/order/".length());
                    HttpHeaders request = new HttpHeaders();request.setBearerAuth(auth.getToken().getTokenValue());
                    try { client.exchange(orderUrl + "/api/order/internal/{order}/payment-context", HttpMethod.GET, new HttpEntity<>(request), String.class, order); }
                    catch (Exception ex) { throw new MessagingException("Order subscription denied"); }
                } else if (headers.getCommand() == StompCommand.SEND) throw new MessagingException("Client publishing is disabled");
                return message;
            }
        });
    }
}
