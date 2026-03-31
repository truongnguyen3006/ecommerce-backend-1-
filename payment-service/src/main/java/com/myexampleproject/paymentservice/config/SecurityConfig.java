package com.myexampleproject.paymentservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 1. Tắt CSRF (vì chúng ta dùng API)
                .csrf(csrf -> csrf.disable())

                // 2. Yêu cầu TẤT CẢ request phải được xác thực
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/api/payment/vnpay/return", "/api/payment/vnpay/ipn").permitAll()
                        .anyRequest().authenticated() // <-- BẮT BUỘC TẤT CẢ
                )

                // 3. Cấu hình để xác thực JWT (đọc token)
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))

                // 4. Bắt buộc stateless (không dùng session)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        return http.build();
    }
}