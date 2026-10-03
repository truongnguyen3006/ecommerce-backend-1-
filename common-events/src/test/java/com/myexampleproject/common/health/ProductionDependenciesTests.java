package com.myexampleproject.common.health;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;
class ProductionDependenciesTests {
    @Test void readinessRequiresReachableMetadataWithTheExactPublicIssuer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/metadata", exchange -> {
            byte[] body = "{\"issuer\":\"https://auth.test/realms/shop\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        var config = new ProductionDependenciesConfiguration();
        String url = "http://127.0.0.1:"+server.getAddress().getPort()+"/metadata";
        try {
            assertThat(config.identity(url,"https://auth.test/realms/shop").health().getStatus()).isEqualTo(Status.UP);
            assertThat(config.identity(url,"https://wrong.test/realms/shop").health().getStatus()).isEqualTo(Status.DOWN);
            assertThat(config.schemaRegistry(url.replace("/metadata", "/missing")).health().getStatus()).isEqualTo(Status.DOWN);
        } finally { server.stop(0); }
        assertThat(config.identity(url,"https://auth.test/realms/shop").health().getStatus()).isEqualTo(Status.DOWN);
    }
}
