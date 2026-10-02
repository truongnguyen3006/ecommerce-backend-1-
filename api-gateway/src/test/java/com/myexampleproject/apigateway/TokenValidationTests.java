package com.myexampleproject.apigateway;

import com.myexampleproject.apigateway.config.SecurityConfig;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Date;
import static org.assertj.core.api.Assertions.*;

class TokenValidationTests {
    @Test void previouslyAcceptedTokenIsRevalidatedAfterExpiry() throws Exception {
        RSAKey key=new RSAKeyGenerator(2048).keyID("test-key").generate();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String issuer="http://127.0.0.1:"+server.getAddress().getPort()+"/realm/test";
        server.createContext("/realm/test/protocol/openid-connect/certs",exchange -> {
            byte[] bytes=new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);
            try(var stream=exchange.getResponseBody()) {stream.write(bytes);}
        });
        server.start();
        try {
            Instant now=Instant.now();
            SignedJWT signed=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                    new JWTClaimsSet.Builder().issuer(issuer).subject("A").issueTime(Date.from(now.minusSeconds(5)))
                            .expirationTime(Date.from(now.plusSeconds(300))).build());
            signed.sign(new RSASSASigner(key));String token=signed.serialize();
            ReactiveJwtDecoder decoder=new SecurityConfig().reactiveJwtDecoder(issuer);
            assertThat(decoder.decode(token).block(Duration.ofSeconds(5)).getSubject()).isEqualTo("A");
            JwtTimestampValidator time=new JwtTimestampValidator(Duration.ZERO);
            time.setClock(Clock.fixed(now.plusSeconds(600),ZoneOffset.UTC));
            ((NimbusReactiveJwtDecoder)decoder).setJwtValidator(new DelegatingOAuth2TokenValidator<>(time,new JwtIssuerValidator(issuer)));
            assertThatThrownBy(() -> decoder.decode(token).block(Duration.ofSeconds(5))).isInstanceOf(JwtValidationException.class);
        } finally {server.stop(0);}
    }
}
