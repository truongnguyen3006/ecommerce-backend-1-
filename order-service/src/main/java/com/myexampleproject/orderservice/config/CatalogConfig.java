package com.myexampleproject.orderservice.config;
import com.myexampleproject.common.client.ProductCatalogClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.web.client.RestTemplate;
@Configuration
public class CatalogConfig {
    @Bean
    public ProductCatalogClient productCatalogClient(RestTemplate client, @Value("${product.service.base-url:http://localhost:8083}") String baseUrl) {
        return new ProductCatalogClient(client, baseUrl);
    }
}
