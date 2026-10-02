package com.myexampleproject.cartservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myexampleproject.common.dto.CartItemRequest;
import com.myexampleproject.common.event.*;
import com.myexampleproject.common.client.ProductCatalogClient;
import com.myexampleproject.cartservice.model.*;
import com.myexampleproject.cartservice.repository.CartRepository;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
public class CartService {
    private final CartRepository cartRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate strings;
    private final KafkaTemplate<String, CartCheckoutEvent> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ProductCatalogClient catalog;
    private final StockClient stock;
    private static final Duration CHECKOUT_TTL = Duration.ofMinutes(15);
    private static final String PRODUCT_CACHE_KEY = "products:cache";
    private static final DefaultRedisScript<Long> MUTATE = new DefaultRedisScript<>("""
        if redis.call('EXISTS', KEYS[3]) == 1 then return -2 end
        local current = tonumber(redis.call('HGET', KEYS[1], ARGV[1]) or '0')
        local next = tonumber(ARGV[2])
        if ARGV[4] == 'add' then next = next + current end
        if next < 1 or next > tonumber(ARGV[3]) then return -1 end
        redis.call('HSET', KEYS[1], ARGV[1], tostring(next))
        redis.call('HSET', KEYS[2], ARGV[1], ARGV[5])
        redis.call('EXPIRE', KEYS[1], 86400);redis.call('EXPIRE', KEYS[2], 86400)
        return next
        """, Long.class);
    private static final DefaultRedisScript<Long> REMOVE = new DefaultRedisScript<>("""
        if redis.call('EXISTS', KEYS[3]) == 1 then return -2 end
        if ARGV[1] == '*' then redis.call('DEL', KEYS[1], KEYS[2])
        else redis.call('HDEL', KEYS[1], ARGV[1]);redis.call('HDEL', KEYS[2], ARGV[1]) end
        return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> CLEANUP = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[3]) ~= ARGV[1] then return 0 end
        if ARGV[2] == 'clear' then redis.call('DEL', KEYS[1], KEYS[2]) end
        redis.call('DEL', KEYS[3]);return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
        return 0
        """, Long.class);

    public void addItem(String userId, CartItemRequest item) { mutate(userId, item, "add"); }
    public void updateQuantity(String userId, CartItemRequest item) { mutate(userId, item, "set"); }
    private void mutate(String userId, CartItemRequest item, String operation) {
        if (item == null || item.getSkuCode() == null || item.getSkuCode().isBlank() || item.getQuantity() == null || item.getQuantity() <= 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SKU and positive quantity are required");
        var product = catalog.find(item.getSkuCode());
        int available = stock.quantity(item.getSkuCode());
        Long result = strings.execute(MUTATE, keys(userId), item.getSkuCode(), item.getQuantity().toString(), Integer.toString(available), operation, json(product));
        if (result == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Cart store unavailable");
        if (result == -2) throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkout is in progress");
        if (result == -1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Requested quantity exceeds available stock");
    }
    public void removeItem(String userId, String sku) { remove(userId, sku); }
    public void clearCart(String userId) { remove(userId, "*"); }
    private void remove(String userId, String sku) {
        Long result = strings.execute(REMOVE, keys(userId), sku);
        if (result != null && result == -2) throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkout is in progress");
    }
    private List<String> keys(String userId) { return List.of("cart:qty:" + userId, "cart:data:" + userId, "cart:checkout:" + userId); }

    public CartEntity viewCart(String userId) {
        Map<Object,Object> quantities = strings.opsForHash().entries("cart:qty:" + userId);
        List<CartItemEntity> items = new ArrayList<>();
        for (Map.Entry<Object,Object> entry : quantities.entrySet()) {
            String sku = entry.getKey().toString();
            var product = displayProduct(userId, sku);
            int quantity = Integer.parseInt(entry.getValue().toString());
            items.add(CartItemEntity.builder().skuCode(sku).quantity(quantity).productName(product.name())
                    .price(product.price()).imageUrl(product.imageUrl()).build());
        }
        items.sort(Comparator.comparing(CartItemEntity::getSkuCode));
        return CartEntity.builder().userId(userId).items(items).build();
    }

    private ProductCatalogClient.CatalogItem displayProduct(String userId, String sku) {
        try { return catalog.find(sku); }
        catch (ResponseStatusException ex) {
            if (ex.getStatusCode().value() != 404) throw ex;
            // Keep deleted products visible so the owner can remove them. Checkout revalidates every SKU.
            Object snapshot = strings.opsForHash().get("cart:data:" + userId, sku);
            try {
                var saved = snapshot == null ? objectMapper.createObjectNode() : objectMapper.readTree(snapshot.toString());
                return new ProductCatalogClient.CatalogItem(sku, saved.path("name").asText("Unavailable product"),
                        saved.hasNonNull("price") ? new BigDecimal(saved.get("price").asText()) : BigDecimal.ZERO,
                        saved.path("imageUrl").asText(null), null, null, false);
            } catch (Exception invalidSnapshot) {
                return new ProductCatalogClient.CatalogItem(sku,"Unavailable product",BigDecimal.ZERO,null,null,null,false);
            }
        }
    }

    public CompletableFuture<String> checkoutAsync(String userId) {
        String candidate = UUID.randomUUID().toString();
        String lock = "cart:checkout:" + userId;
        boolean claimed = Boolean.TRUE.equals(strings.opsForValue().setIfAbsent(lock, candidate, CHECKOUT_TTL));
        String id = claimed ? candidate : strings.opsForValue().get(lock);
        if (id == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkout changed; retry");
        String saved = strings.opsForValue().get("cart:checkout:event:" + id);
        if (!claimed && saved == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkout snapshot is being prepared; retry shortly");
        CartCheckoutEvent event;
        try {
            if (saved != null) event = objectMapper.readValue(saved, CartCheckoutEvent.class);
            else {
                CartEntity cart = viewCart(userId);
                if (cart.getItems().isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Cart is empty");
                for (CartItemEntity item : cart.getItems()) {
                    catalog.find(item.getSkuCode());
                    if (item.getQuantity() <= 0 || item.getQuantity() > stock.quantity(item.getSkuCode()))
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient inventory");
                }
                event = new CartCheckoutEvent(userId, cart.getItems().stream().map(i -> new CartLineItem(i.getSkuCode(), i.getQuantity(), i.getPrice())).toList(), id);
                strings.opsForValue().set("cart:checkout:event:" + id, json(event), Duration.ofDays(1));
            }
        } catch (ResponseStatusException ex) {
            strings.execute(RELEASE, List.of(lock), id);throw ex;
        } catch (Exception ex) {
            strings.execute(RELEASE, List.of(lock), id);throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Checkout snapshot unavailable");
        }
        // A retry reuses the same order number, even after an ambiguous publish timeout.
        return kafkaTemplate.send("cart-checkout-topic", userId, event).orTimeout(10, TimeUnit.SECONDS)
                .handle((metadata, error) -> {
                    if (error != null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Checkout could not be queued; retry with the same cart");
                    return id;
                });
    }
    public void checkout(String userId) { checkoutAsync(userId).join(); }
    private String json(Object object) {
        try { return objectMapper.writeValueAsString(object); }
        catch (Exception ex) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Cart serialization failed"); }
    }

    @KafkaListener(topics = "product-cache-update-topic", groupId = "cart-product-cacher")
    public void handleProductCacheUpdate(List<ConsumerRecord<String,Object>> records) {
        for (var record : records) try {
            if (record.value() == null) redisTemplate.opsForHash().delete(PRODUCT_CACHE_KEY, record.key());
            else {
                ProductCacheEvent event = objectMapper.convertValue(record.value(), ProductCacheEvent.class);
                redisTemplate.opsForHash().put(PRODUCT_CACHE_KEY, event.getSkuCode(), event);
            }
        } catch (Exception ex) { throw new BatchListenerFailedException("Product cache update failed", ex, record); }
    }

    @KafkaListener(topics = "order-status-topic", groupId = "cart-cleaner-group")
    public void handleCheckoutCleanup(List<ConsumerRecord<String,Object>> records) {
        for (var record : records) try {
            OrderStatusEvent status = objectMapper.convertValue(record.value(), OrderStatusEvent.class);
            String saved = strings.opsForValue().get("cart:checkout:event:" + status.getOrderNumber());
            if (saved == null) continue;
            CartCheckoutEvent event = objectMapper.readValue(saved, CartCheckoutEvent.class);
            String userId = event.getUserId();
            // Do not discard a cart merely because checkout was queued or inventory rejected it.
            if (Set.of("VALIDATED", "COMPLETED").contains(status.getStatus())) {
                strings.execute(CLEANUP, keys(userId), status.getOrderNumber(), "clear");
            } else if (Set.of("FAILED", "CANCELLED", "PAYMENT_FAILED").contains(status.getStatus())) {
                strings.execute(RELEASE, List.of("cart:checkout:" + userId), status.getOrderNumber());
            }
        } catch (Exception ex) { throw new BatchListenerFailedException("Cart cleanup failed", ex, record); }
    }
}
