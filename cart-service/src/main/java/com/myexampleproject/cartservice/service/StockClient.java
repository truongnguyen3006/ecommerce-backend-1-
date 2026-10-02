package com.myexampleproject.cartservice.service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
@Component
public class StockClient {
    private final RestTemplate client;
    private final String baseUrl;
    public StockClient(RestTemplate client, @Value("${inventory.service.base-url}") String baseUrl) { this.client = client;this.baseUrl = baseUrl; }
    public int quantity(String sku) {
        try {
            Map<?, ?> data = client.getForObject(baseUrl + "/api/inventory/{sku}", Map.class, sku);
            if (data == null || !(data.get("quantity") instanceof Number quantity)) throw new RestClientException("Missing inventory");
            return quantity.intValue();
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Inventory is not initialized for this SKU");
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Inventory service unavailable");
        }
    }
}
