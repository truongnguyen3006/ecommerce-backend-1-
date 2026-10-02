package com.myexampleproject.common.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import java.util.*;

public final class KeycloakRoles {
    private KeycloakRoles() {}

    public static Collection<GrantedAuthority> authorities(Jwt jwt) {
        Object claim = jwt.getClaim("realm_access");
        if (!(claim instanceof Map<?, ?> realm) || !(realm.get("roles") instanceof Collection<?> roles)) return List.of();
        return roles.stream().filter(String.class::isInstance).map(String.class::cast)
                .map(r -> (GrantedAuthority)new SimpleGrantedAuthority("ROLE_" + r.toUpperCase(Locale.ROOT))).distinct().toList();
    }

    public static JwtAuthenticationConverter converter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(KeycloakRoles::authorities);
        return converter;
    }

    public static boolean isAdmin(Jwt jwt) {
        return jwt != null && authorities(jwt).stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
}
