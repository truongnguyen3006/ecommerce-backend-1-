package com.myexampleproject.cartservice;

import com.myexampleproject.cartservice.config.ProductionManagementSecurity;
import com.myexampleproject.cartservice.config.SecurityConfig;
import com.myexampleproject.cartservice.controller.CartController;
import com.myexampleproject.cartservice.model.CartEntity;
import com.myexampleproject.cartservice.service.CartService;
import com.myexampleproject.common.exception.GlobalExceptionHandler;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/** Uses the real HTTP security chain and a signed JWT/private JWKS fixture. */
@SpringBootTest(classes=CartSignedTokenBoundaryTests.Application.class,
    webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.config.name=cart-signed-token-fixture", "management.server.port=0",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "management.tracing.enabled=false"})
@ActiveProfiles("prod")
class CartSignedTokenBoundaryTests {
    private static final String ISSUER = "https://fixture.example.test/realms/fixture";
    private static final RSAKey KEY;
    private static final HttpServer JWKS;
    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("fixture-key").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            JWKS.createContext("/certs", exchange -> {
                byte[] json = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,json.length);
                try (var response=exchange.getResponseBody()) { response.write(json); }
            });
            JWKS.start();
        } catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
    }
    @DynamicPropertySource
    static void identity(DynamicPropertyRegistry properties) {
        properties.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",()->ISSUER);
        properties.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
            ()->"http://127.0.0.1:"+JWKS.getAddress().getPort()+"/certs");
    }
    @AfterAll static void stopFixture() { JWKS.stop(0); }
    @LocalServerPort int port;
    @MockitoBean CartService cart;

    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration(exclude={DataSourceAutoConfiguration.class,HibernateJpaAutoConfiguration.class})
    @Import({SecurityConfig.class,ProductionManagementSecurity.class,CartController.class,GlobalExceptionHandler.class})
    static class Application {}

    private String token(String issuer) throws Exception {
        return token(issuer,"fixture-user");
    }
    private String token(String issuer,String subject) throws Exception {
        Instant now=Instant.now();
        var claims=new JWTClaimsSet.Builder().issuer(issuer).subject(subject)
            .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(120)))
            .claim("realm_access",Map.of("roles",List.of("USER"))).build();
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),claims);
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }
    private HttpResponse<String> get(String path,String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET();
        if (token!=null) request.header("Authorization","Bearer "+token);
        try (var client=HttpClient.newHttpClient()) {
            return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
        }
    }
    @Test void signedUserRetainsOwnerBoundaryAndInvalidIssuerIsRejected() throws Exception {
        when(cart.viewCart("fixture-user")).thenReturn(CartEntity.builder()
            .userId("fixture-user").items(List.of()).build());
        assertEquals(401,get("/api/cart/me",null).statusCode());
        String signed=token(ISSUER);
        assertEquals(200,get("/api/cart/me",signed).statusCode());
        assertEquals(403,get("/api/cart/view/another-owner",signed).statusCode());
        assertEquals(401,get("/api/cart/me",token(ISSUER,null)).statusCode());
        assertEquals(401,get("/api/cart/me",token("https://wrong.example.test/realms/fixture")).statusCode());
        verify(cart).viewCart("fixture-user");
        verifyNoMoreInteractions(cart);
    }
}
