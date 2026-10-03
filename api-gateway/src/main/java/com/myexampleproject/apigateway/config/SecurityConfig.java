package com.myexampleproject.apigateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.*;
import reactor.core.publisher.Mono;
import java.util.*;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {
    @Bean
    public ReactiveJwtDecoder reactiveJwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") String jwkSetUri) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri.isBlank() ? issuerUri + "/protocol/openid-connect/certs" : jwkSetUri).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuerUri));
        // Cache signing keys in Nimbus, never cache a successful token validation.
        return decoder;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins}") String origins) {
        List<String> allowed = Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
        if (allowed.contains("*")) throw new IllegalArgumentException("CORS requires explicit origins");
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowed);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http.csrf(ServerHttpSecurity.CsrfSpec::disable).cors(cors -> {})
                .authorizeExchange(ex -> ex
                        .pathMatchers("/api/order/internal/**", "/eureka/**").denyAll()
                        .pathMatchers("/auth/**", "/ws/**").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/user").permitAll()
                        .pathMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .pathMatchers("/actuator/**", "/api/product/admin/**", "/api/order/admin", "/api/order/admin/**", "/api/admin/**", "/api/user/admin/**").hasRole("ADMIN")
                        .pathMatchers("/api/inventory/operations/**").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api/product/**", "/api/inventory/**", "/api/payment/vnpay/return", "/api/payment/vnpay/ipn").permitAll()
                        .pathMatchers("/api/product/**", "/api/inventory/**").hasRole("ADMIN")
                        .pathMatchers("/api/cart/**", "/api/order/**", "/api/payment/**", "/api/user/me", "/api/user/addresses", "/api/user/addresses/**").hasAnyRole("USER", "ADMIN")
                        .pathMatchers("/api/user/**").hasRole("ADMIN")
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(token -> {
                    Object claim = token.getClaim("realm_access");
                    List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                    if (claim instanceof Map<?, ?> realm && realm.get("roles") instanceof Collection<?> roles) {
                        roles.stream().filter(String.class::isInstance).map(String.class::cast)
                                .map(r -> new SimpleGrantedAuthority("ROLE_" + r.toUpperCase(Locale.ROOT))).forEach(authorities::add);
                    }
                    return Mono.just(new JwtAuthenticationToken(token, authorities));
                })));
        return http.build();
    }
}
