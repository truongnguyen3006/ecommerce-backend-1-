package com.myexampleproject.apigateway;

import com.myexampleproject.apigateway.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.*;
import static org.mockito.Mockito.when;

@WebFluxTest(GatewaySecurityTests.Endpoints.class)
@Import({SecurityConfig.class, GatewaySecurityTests.Endpoints.class})
class GatewaySecurityTests {
    @Autowired WebTestClient client;
    @MockitoBean ReactiveJwtDecoder decoder;

    @Test void productBrowsingIsPublicButCartRequiresAuthentication() {
        client.get().uri("/api/product/test").exchange().expectStatus().isOk();
        client.get().uri("/api/cart/me").exchange().expectStatus().isUnauthorized();
    }

    @Test void realmUserCannotMutateProductsAndRealmAdminCan() {
        token("user-token", "user");token("admin-token", "admin");
        client.post().uri("/api/product/test").headers(h -> h.setBearerAuth("user-token")).exchange().expectStatus().isForbidden();
        client.post().uri("/api/product/test").headers(h -> h.setBearerAuth("admin-token")).exchange().expectStatus().isOk();
    }

    @Test void internalOrderApiAndSensitiveActuatorAreNotPublic() {
        token("user-token", "user");
        client.get().uri("/api/order/internal/O/payment-context").headers(h -> h.setBearerAuth("user-token")).exchange().expectStatus().isForbidden();
        client.get().uri("/actuator/env").headers(h -> h.setBearerAuth("user-token")).exchange().expectStatus().isForbidden();
    }

    private void token(String value,String role) {
        Jwt jwt=Jwt.withTokenValue(value).header("alg","RS256").subject("A")
                .claim("realm_access",Map.of("roles",List.of(role))).build();
        when(decoder.decode(value)).thenReturn(Mono.just(jwt));
    }
    @RestController static class Endpoints {
        @GetMapping({"/api/product/test","/api/cart/me","/api/order/internal/O/payment-context","/actuator/env"}) String get() {return "ok";}
        @PostMapping("/api/product/test") String post() {return "ok";}
    }
}
