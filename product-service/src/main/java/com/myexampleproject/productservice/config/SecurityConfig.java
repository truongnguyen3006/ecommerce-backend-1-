package com.myexampleproject.productservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
                .csrf(csrf -> csrf.disable())

                // 2. Cấu hình tùy chỉnh
                .authorizeHttpRequests(auth -> auth
                        // Cho phép bất kỳ ai cũng có thể XEM (GET) sản phẩm
                        .requestMatchers(HttpMethod.GET, "/api/product/**").permitAll()
                        // Bắt buộc tất cả các request khác (POST, DELETE) phải xác thực
                        // Các API khác (Tạo/Sửa/Xóa) vẫn yêu cầu đăng nhập (ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/product").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/product/**").authenticated()
                        .anyRequest().authenticated()
                )

                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        return http.build();
    }
}
