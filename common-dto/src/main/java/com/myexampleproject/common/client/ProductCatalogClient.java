package com.myexampleproject.common.client;

import org.springframework.http.HttpStatus;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;

/** Read the authoritative catalog rather than accepting prices or stale cache entries. */
public class ProductCatalogClient {
    public record CatalogItem(String skuCode, String name, BigDecimal price, String imageUrl, String color, String size, Boolean isActive) {}
    private final RestTemplate restTemplate;
    private final String baseUrl;
    public ProductCatalogClient(RestTemplate restTemplate, String baseUrl) { this.restTemplate = restTemplate;this.baseUrl = baseUrl; }
    public CatalogItem find(String sku) {
        try {
            CatalogItem item = restTemplate.getForObject(baseUrl + "/api/product/sku/{sku}", CatalogItem.class, sku);
            if (item == null || Boolean.FALSE.equals(item.isActive()) || item.price() == null || item.price().signum() < 0)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product variant is unavailable");
            return item;
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product variant not found");
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Product service unavailable");
        }
    }
}
