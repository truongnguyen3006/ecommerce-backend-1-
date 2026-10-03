package com.myexampleproject.common.health;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Dependency readiness only: liveness deliberately excludes remote services. */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class ProductionDependenciesConfiguration {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json = new ObjectMapper();

    @Bean("identity")
    HealthIndicator identity(@Value("${app.health.identity.metadata-url}") String metadata,
                            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
        return () -> {
            try {
                HttpResponse<String> response = get(metadata);
                return response.statusCode() == 200 && issuer.equals(json.readTree(response.body()).path("issuer").asText())
                    ? Health.up().build() : Health.down().build();
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                return Health.down().build();
            }
        };
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "app.health.kafka.enabled", havingValue = "true")
    AdminClient readinessKafkaClient(@Value("${spring.kafka.bootstrap-servers}") String servers) {
        return AdminClient.create(Map.of("bootstrap.servers", servers, "request.timeout.ms", 2500,
            "default.api.timeout.ms", 2500, "client.id", "readiness-probe"));
    }

    @Bean("broker")
    @ConditionalOnProperty(name = "app.health.kafka.enabled", havingValue = "true")
    HealthIndicator broker(AdminClient readinessKafkaClient) {
        return () -> {
            try {
                return readinessKafkaClient.describeCluster().nodes().get(3, TimeUnit.SECONDS).isEmpty()
                    ? Health.down().build() : Health.up().build();
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                return Health.down().build();
            }
        };
    }

    @Bean("schemaRegistry")
    @ConditionalOnProperty(name = "app.health.kafka.enabled", havingValue = "true")
    HealthIndicator schemaRegistry(@Value("${app.health.schema-registry.url}") String url) {
        return () -> {
            try { return get(url).statusCode() == 200 ? Health.up().build() : Health.down().build(); }
            catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                return Health.down().build();
            }
        };
    }

    private HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }
}
