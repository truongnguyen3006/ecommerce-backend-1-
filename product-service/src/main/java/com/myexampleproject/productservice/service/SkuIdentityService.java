package com.myexampleproject.productservice.service;

import com.myexampleproject.common.exception.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;
import java.time.Duration;

/** A SKU is a permanent stock identity, including after catalog deletion. */
@Service
public class SkuIdentityService {
    private final JdbcTemplate jdbc;
    private final RestTemplate inventory;
    private final String baseUrl;
    @org.springframework.beans.factory.annotation.Autowired
    public SkuIdentityService(JdbcTemplate jdbc, RestTemplateBuilder builder,
            @Value("${inventory.service.base-url:http://localhost:8082}") String baseUrl) {
        this(jdbc,builder.connectTimeout(Duration.ofSeconds(2)).readTimeout(Duration.ofSeconds(12)).build(),baseUrl);
    }
    public SkuIdentityService(JdbcTemplate jdbc, RestTemplate inventory, String baseUrl) {
        this.jdbc=jdbc;this.inventory=inventory;this.baseUrl=baseUrl;
    }
    public void reserveNew(String sku) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM sku_identity WHERE sku_code=?",Integer.class,sku)>0) conflict();
        // Protect pre-outbox deleted identities still present in the authoritative Streams store.
        // An outage is uncertainty, never proof of absence. The existing read endpoint is public.
        try {
            inventory.getForObject(baseUrl+"/api/inventory/{sku}",java.util.Map.class,sku);
            conflict();
        } catch (HttpClientErrorException.NotFound absent) {
            // Only a ready inventory store's 404 proves absence.
        } catch (RestClientException unavailable) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE,"SKU_IDENTITY_UNAVAILABLE","Inventory identity check unavailable");
        }
        try {jdbc.update("INSERT INTO sku_identity(sku_code) VALUES (?)",sku);}
        catch (org.springframework.dao.DuplicateKeyException collision) {conflict();}
    }
    public void attach(String sku, Long productId) {
        jdbc.update("UPDATE sku_identity SET product_id=? WHERE sku_code=? AND retired=FALSE",productId,sku);
    }
    public void retire(String sku) {
        jdbc.update("UPDATE sku_identity SET retired=TRUE,retired_at=CURRENT_TIMESTAMP WHERE sku_code=?",sku);
    }
    private void conflict() {
        throw new DomainException(HttpStatus.CONFLICT,"SKU_RESERVED","SKU already used or retired; choose a new SKU code");
    }
}
