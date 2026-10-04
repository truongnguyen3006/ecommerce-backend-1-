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
    private final PurchasedOrderClient orders;
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
        redis.call('HSET', KEYS[4], ARGV[1], ARGV[6])
        redis.call('EXPIRE', KEYS[1], 86400);redis.call('EXPIRE', KEYS[2], 86400);redis.call('EXPIRE', KEYS[4], 86400)
        return next
        """, Long.class);
    private static final DefaultRedisScript<Long> REMOVE = new DefaultRedisScript<>("""
        if redis.call('EXISTS', KEYS[3]) == 1 then return -2 end
        if ARGV[1] == '*' then redis.call('DEL', KEYS[1], KEYS[2], KEYS[4])
        else redis.call('HDEL', KEYS[1], ARGV[1]);redis.call('HDEL', KEYS[2], ARGV[1]);redis.call('HDEL', KEYS[4], ARGV[1]) end
        return 1
        """, Long.class);
    // One Redis execution snapshots quantity and revision together. Legacy lines get a stable token.
    static final DefaultRedisScript<String> SNAPSHOT = new DefaultRedisScript<>("""
        local pairs = redis.call('HGETALL', KEYS[1]); local result = {}
        for i=1,#pairs,2 do
          local revision = redis.call('HGET', KEYS[4], pairs[i])
          if not revision then
            revision = ARGV[1] .. ':' .. pairs[i]
            redis.call('HSET', KEYS[4], pairs[i], revision)
          end
          result[#result+1] = {skuCode=pairs[i],quantity=tonumber(pairs[i+1]),revision=revision}
        end
        local ttl = redis.call('TTL', KEYS[1]); if ttl > 0 then redis.call('EXPIRE', KEYS[4], ttl) end
        return cjson.encode(result)
        """, String.class);
    static final DefaultRedisScript<Long> PURCHASED = new DefaultRedisScript<>("""
        if redis.call('EXISTS', KEYS[5]) == 1 then return 0 end
        local removed = 0
        local items = cjson.decode(ARGV[2])
        for _,item in ipairs(items) do
          if item.revision and item.revision ~= cjson.null
             and redis.call('HGET', KEYS[1], item.skuCode) == tostring(item.quantity)
             and redis.call('HGET', KEYS[4], item.skuCode) == item.revision then
            redis.call('HDEL', KEYS[1], item.skuCode)
            redis.call('HDEL', KEYS[2], item.skuCode)
            redis.call('HDEL', KEYS[4], item.skuCode)
            removed = removed + 1
          end
        end
        redis.call('SET', KEYS[5], 'done', 'EX', 604800)
        if redis.call('GET', KEYS[3]) == ARGV[1] then redis.call('DEL', KEYS[3]) end
        return removed
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
        Long result = strings.execute(MUTATE, keys(userId), item.getSkuCode(), item.getQuantity().toString(), Integer.toString(available), operation, json(product), UUID.randomUUID().toString());
        if (result == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Cart store unavailable");
        if (result == -2) throw new com.myexampleproject.common.exception.DomainException(HttpStatus.CONFLICT,"CHECKOUT_IN_PROGRESS","Checkout is in progress");
        if (result == -1) throw new com.myexampleproject.common.exception.DomainException(HttpStatus.CONFLICT,"INSUFFICIENT_STOCK","Requested quantity exceeds available stock");
    }
    public void removeItem(String userId, String sku) { remove(userId, sku); }
    public void clearCart(String userId) { remove(userId, "*"); }
    private void remove(String userId, String sku) {
        Long result = strings.execute(REMOVE, keys(userId), sku);
        if (result != null && result == -2) throw new com.myexampleproject.common.exception.DomainException(HttpStatus.CONFLICT,"CHECKOUT_IN_PROGRESS","Checkout is in progress");
    }
    private List<String> keys(String userId) { return List.of("cart:qty:" + userId, "cart:data:" + userId, "cart:checkout:" + userId, "cart:revision:" + userId); }

    public CartEntity viewCart(String userId) {
        String snapshot;
        try {
            snapshot = strings.execute(SNAPSHOT, keys(userId), UUID.randomUUID().toString());
        } catch (org.springframework.dao.QueryTimeoutException | org.springframework.data.redis.RedisConnectionFailureException ex) {
            throw new com.myexampleproject.common.exception.DomainException(HttpStatus.SERVICE_UNAVAILABLE,
                    "CART_STORE_UNAVAILABLE", "Cart store unavailable");
        }
        if (snapshot == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Cart store unavailable");
        List<CartItemEntity> items = new ArrayList<>();
        try {
            var lines = objectMapper.readTree(snapshot);
            if (!lines.isArray() && lines.size()!=0) throw new IllegalStateException("Invalid cart snapshot");
            for (var line : lines) {
                String sku = line.path("skuCode").asText();
                var product = displayProduct(userId, sku);
                items.add(CartItemEntity.builder().skuCode(sku).quantity(line.path("quantity").asInt())
                    .revision(line.path("revision").asText(null)).productName(product.name())
                    .price(product.price()).imageUrl(product.imageUrl()).build());
            }
        } catch (ResponseStatusException ex) {throw ex;}
        catch (Exception ex) {throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Cart snapshot unavailable");}
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
                if (cart.getItems().isEmpty()) throw new com.myexampleproject.common.exception.DomainException(HttpStatus.CONFLICT,"CART_EMPTY","Cart is empty");
                for (CartItemEntity item : cart.getItems()) {
                    catalog.find(item.getSkuCode());
                    if (item.getQuantity() <= 0 || item.getQuantity() > stock.quantity(item.getSkuCode()))
                        throw new com.myexampleproject.common.exception.DomainException(HttpStatus.CONFLICT,"INSUFFICIENT_STOCK","Insufficient inventory");
                }
                event = new CartCheckoutEvent(userId, cart.getItems().stream().map(i -> new CartLineItem(i.getSkuCode(), i.getQuantity(), i.getPrice(), i.getRevision())).toList(), id);
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

    public long cleanupPurchased(String owner, String token, PurchasedCartRequest request) {
        orders.verify(owner, token, request);
        return cleanup(owner, request.orderNumber(), request.items().stream()
            .map(i -> new CartLineItem(i.skuCode(), i.quantity(), null, i.revision())).toList());
    }
    private long cleanup(String owner, String orderNumber, List<CartLineItem> items) {
        var cleanupKeys = new ArrayList<>(keys(owner));
        cleanupKeys.add("cart:purchased:" + owner + ":" + orderNumber);
        Long removed = strings.execute(PURCHASED, cleanupKeys, orderNumber, json(items));
        if (removed == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Cart cleanup unavailable");
        return removed;
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
                cleanup(userId, status.getOrderNumber(), event.getItems());
            } else if (Set.of("FAILED", "CANCELLED", "PAYMENT_FAILED").contains(status.getStatus())) {
                strings.execute(RELEASE, List.of("cart:checkout:" + userId), status.getOrderNumber());
            }
        } catch (Exception ex) { throw new BatchListenerFailedException("Cart cleanup failed", ex, record); }
    }
}
