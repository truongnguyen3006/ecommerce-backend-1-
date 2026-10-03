package com.myexampleproject.apigateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.web.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.ClassUtils;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.profiles.active=prod", "management.server.port=0", "spring.cloud.discovery.enabled=false",
    "eureka.client.enabled=false", "KEYCLOAK_ISSUER_URI=http://127.0.0.1:9/realms/test",
    "KEYCLOAK_JWK_SET_URI=http://127.0.0.1:9/realms/test/protocol/openid-connect/certs",
    "KEYCLOAK_METADATA_URI=http://127.0.0.1:9/realms/test/.well-known/openid-configuration",
    "CORS_ALLOWED_ORIGINS=http://localhost:3001", "EUREKA_URL=http://127.0.0.1:9/eureka/",
    "DISCOVERY_BASE_URL=http://127.0.0.1:9", "ZIPKIN_ENDPOINT=http://127.0.0.1:9/api/v2/spans",
    "TRACING_SAMPLE_RATE=0"
})
class ProductionApplicationTests {
    @Autowired ApplicationContext context;
    @LocalManagementPort int managementPort;

    @Test void productionGatewayStaysReactiveWithPrivateLivenessAndFailClosedReadiness() {
        assertThat(context).isInstanceOf(ReactiveWebServerApplicationContext.class);
        assertThat(ClassUtils.isPresent("org.springframework.web.servlet.DispatcherServlet", getClass().getClassLoader())).isFalse();
        WebTestClient client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + managementPort).build();
        client.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
        client.get().uri("/actuator/health/readiness").exchange().expectStatus().isEqualTo(503);
    }
}
