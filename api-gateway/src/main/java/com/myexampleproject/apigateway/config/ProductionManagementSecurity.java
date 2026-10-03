package com.myexampleproject.apigateway.config;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
@Configuration
@Profile("prod")
public class ProductionManagementSecurity {
    @Bean @Order(0)
    SecurityWebFilterChain privateManagementSecurity(ServerHttpSecurity http) {
        return http.securityMatcher(ServerWebExchangeMatchers.pathMatchers("/actuator/**"))
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .authorizeExchange(auth -> auth.pathMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus")
                .permitAll().anyExchange().denyAll()).build();
    }
}
