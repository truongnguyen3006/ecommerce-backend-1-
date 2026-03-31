package com.myexampleproject.discoveryserver.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity

public class SecurityConfig { // <<--- KHÔNG KẾ THỪA TỪ LỚP NÀO CẢ

    @Value("${eureka.username}")
    private String username;
    @Value("${eureka.password}")
    private String password;

    // Bước 1: Định nghĩa một Bean để mã hóa mật khẩu
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // Bước 2: Định nghĩa Bean chứa thông tin người dùng (thay cho AuthenticationManagerBuilder)
    @Bean
    public InMemoryUserDetailsManager userDetailsService(PasswordEncoder passwordEncoder) {
        UserDetails user = User.builder()
                .username(username)
                .password(passwordEncoder.encode(password)) // Mật khẩu được mã hóa
                .authorities("USER")
                .build();
        return new InMemoryUserDetailsManager(user);
    }

    // Bước 3: Định nghĩa Bean SecurityFilterChain để cấu hình các quy tắc bảo vệ (thay cho configure(HttpSecurity))
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().authenticated() // Tất cả request đều phải xác thực
                )
                .httpBasic(Customizer.withDefaults()); // Bật xác thực HTTP Basic

        return http.build();
    }
}