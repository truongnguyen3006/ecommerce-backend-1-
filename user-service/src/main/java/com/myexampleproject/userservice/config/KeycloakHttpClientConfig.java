package com.myexampleproject.userservice.config;
import org.springframework.context.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;
import io.netty.channel.ChannelOption;
import java.time.Duration;
@Configuration
public class KeycloakHttpClientConfig {
    @Bean
    public WebClient.Builder keycloakWebClientBuilder() {
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000).responseTimeout(Duration.ofSeconds(5))));
    }
}
