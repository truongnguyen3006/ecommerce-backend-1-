package com.myexampleproject.productservice.config;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
@Configuration
@Profile("prod")
public class ProductionManagementSecurity {
    @Bean @Order(0)
    SecurityFilterChain privateManagementSecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher("/actuator/**").csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus")
                .permitAll().anyRequest().denyAll()).build();
    }
}
