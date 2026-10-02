package com.myexampleproject.cartservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import java.time.Duration;

@Configuration
public class HttpClientConfig {
    @Bean
    public RestTemplate serviceRestTemplate(@Value("${app.http.connect-timeout:2s}") Duration connect,
                                            @Value("${app.http.read-timeout:5s}") Duration read) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connect);factory.setReadTimeout(read);
        return new RestTemplate(factory);
    }
}
