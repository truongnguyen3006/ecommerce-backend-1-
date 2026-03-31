package com.myexampleproject.cartservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myexampleproject.common.dto.CartItemRequest;
import com.myexampleproject.common.event.*;
import com.myexampleproject.cartservice.model.CartEntity;
import com.myexampleproject.cartservice.model.CartItemEntity;
import com.myexampleproject.cartservice.repository.CartRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional; // <-- Giữ import này cho listener
import org.springframework.kafka.annotation.KafkaListener; // <-- Thêm import này

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CartService {

    private final CartRepository cartRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    // SỬA: Đổi kiểu của KafkaTemplate để khớp với listener
    private final KafkaTemplate<String, CartCheckoutEvent> kafkaTemplate;

    private static final String REDIS_CART_PREFIX = "cart:";
    private static final String REDIS_QTY_HASH_PREFIX = "cart:qty:";   // MỚI: Hash chứa số lượng
    private static final String REDIS_DATA_HASH_PREFIX = "cart:data:"; // MỚI: Hash chứa thông tin (tên, giá)
    private static final Duration REDIS_TTL = Duration.ofHours(24);
    private static final String CHECKOUT_TOPIC = "cart-checkout-topic";
    private static final String CART_CLEANER_GROUP_ID = "cart-cleaner-group";
    private final ObjectMapper objectMapper; // 1. Inject ObjectMapper
    private static final String PRODUCT_CACHE_KEY = "products:cache"; // 2. Key cache giống OrderService

    // THÊM HÀM MỚI: Listener để xây dựng Product Cache
    // ==========================================================
    @KafkaListener(topics = "product-cache-update-topic", groupId = "cart-product-cacher")
    // SỬA 1: Quay lại dùng List<ConsumerRecord<String, Object>>
    public void handleProductCacheUpdate(List<ConsumerRecord<String, Object>> records) {
        log.info("Receiving {} product cache updates for CartService...", records.size());

        for (ConsumerRecord<String, Object> record : records) {
            try {
                // SỬA 2: Dùng ObjectMapper để convert từ LinkedHashMap
                ProductCacheEvent event = objectMapper.convertValue(record.value(), ProductCacheEvent.class);
                String sku = event.getSkuCode();

                redisTemplate.opsForHash().put(PRODUCT_CACHE_KEY, sku, event);
                log.debug("CartService cached product info for SKU: {}", sku);

            } catch (Exception e) {
                log.error("CART-CACHE: LỖI KHI CACHING PRODUCT {}: {}", record.key(), e.getMessage());
            }
        }
    }

    public CompletableFuture<Void> checkoutAsync(String userId) {
        return CompletableFuture.runAsync(() -> {
            CartEntity cart = this.viewCart(userId);

            if (cart == null || cart.getItems().isEmpty()) {
                // Ném lỗi để .exceptionally() trong Controller bắt được
                throw new IllegalStateException("Cart empty");
            }

            List<CartLineItem> items = cart.getItems().stream()
                    .map(i -> new CartLineItem(i.getSkuCode(), i.getQuantity(), i.getPrice()))
                    .collect(Collectors.toList());

            CartCheckoutEvent event = new CartCheckoutEvent(userId, items);

            // Gọi hàm gửi Kafka (đã được đánh dấu @Async)
            sendKafkaEvent(event);

            // Hàm này trả về ngay, không chờ Kafka
        });
    }

    @Async
    public void sendKafkaEvent(CartCheckoutEvent event) {
        String userId = event.getUserId();
        try {
            log.info("ASYNC SEND: Gửi checkout event cho user {}", userId);
            kafkaTemplate.send(CHECKOUT_TOPIC, userId, event).whenComplete((md,
                                                                            ex) -> {
                if (ex != null) {
                    log.error("ASYNC SEND FAILED for user {}: {}", userId, ex.getMessage());
                } else {
                    log.info("ASYNC SEND SUCCESS for user {}", userId);
                }
            });
        } catch (Exception e) {
            log.error("Lỗi khi gửi Kafka bất đồng bộ: {}", e.getMessage(), e);
        }
    }
    
    
    // ==========================================================
    // CẢI THIỆN HIỆU NĂNG: Sửa hàm addItem (Redis-only)
    // ==========================================================

    /**
     * SỬA LẠI HOÀN TOÀN: Dùng HINCRBY để thêm item (Atomic)
     */
    public void addItem(String userId, CartItemRequest line) {
        // 1. Tra cứu giá
        ProductCacheEvent productInfo = getProductFromCache(line.getSkuCode());
        if (productInfo == null) {
            throw new RuntimeException("Product not found: " + line.getSkuCode());
        }
        String qtyKey = REDIS_QTY_HASH_PREFIX + userId;
        String dataKey = REDIS_DATA_HASH_PREFIX + userId;
        String sku = line.getSkuCode();
        Long qty = (long) line.getQuantity(); // HINCRBY dùng Long
        // 2. LỆNH NGUYÊN TỬ (ATOMIC)
        // Tăng số lượng trong hash "cart:qty:{userId}"
        redisTemplate.opsForHash().increment(qtyKey, sku, qty);
        // 3. Cập nhật snapshot (Tên, Giá, Ảnh) vào hash "cart:data:{userId}"
        // (Chúng ta lưu cả object ProductCacheEvent làm value)
        redisTemplate.opsForHash().put(dataKey, sku, productInfo);
        // 4. Đặt thời gian hết hạn (TTL) cho cả 2 key
        redisTemplate.expire(qtyKey, REDIS_TTL);
        redisTemplate.expire(dataKey, REDIS_TTL);
    }

    // THÊM HÀM HELPER MỚI:
    private ProductCacheEvent getProductFromCache(String skuCode) {
        try {
            Object cachedData = redisTemplate.opsForHash().get(PRODUCT_CACHE_KEY, skuCode);
            if (cachedData == null) return null;
            return objectMapper.convertValue(cachedData, ProductCacheEvent.class);
        } catch (Exception e) {
            log.error("Lỗi khi đọc Product cache: {}", e.getMessage());
            return null;
        }
    }

    // ==========================================================
    /**
     * SỬA LẠI HOÀN TOÀN: Dùng HDEL để xóa item (Atomic)
     */
    public void removeItem(String userId, String sku) {
        String qtyKey = REDIS_QTY_HASH_PREFIX + userId;
        String dataKey = REDIS_DATA_HASH_PREFIX + userId;

        // Xóa field (sku) khỏi cả 2 hash
        redisTemplate.opsForHash().delete(qtyKey, sku);
        redisTemplate.opsForHash().delete(dataKey, sku);
    }

    /**
     * Hàm xem giỏ hàng (giữ nguyên)
     */
    /**
     * SỬA LẠI HOÀN TOÀN: Tái tạo CartEntity từ 2 Hash
     */
    public CartEntity viewCart(String userId) {
        String qtyKey = REDIS_QTY_HASH_PREFIX + userId;
        String dataKey = REDIS_DATA_HASH_PREFIX + userId;

        // 1. Đọc cả 2 hash
        Map<Object, Object> qtyMap = redisTemplate.opsForHash().entries(qtyKey);
        Map<Object, Object> dataMap = redisTemplate.opsForHash().entries(dataKey);

        if (qtyMap.isEmpty()) {
            return CartEntity.builder().userId(userId).items(new ArrayList<>()).build();
        }

        List<CartItemEntity> items = new ArrayList<>();

        // 2. Tái tạo lại danh sách items
        for (Map.Entry<Object, Object> entry : qtyMap.entrySet()) {
            String sku = (String) entry.getKey();
            Integer quantity = ((Number) entry.getValue()).intValue(); // HINCRBY trả về Long, cast an toàn

            Object data = dataMap.get(sku);
            ProductCacheEvent productInfo = null;
            if (data != null) {
                // Convert (LinkedHashMap) -> ProductCacheEvent
                productInfo = objectMapper.convertValue(data, ProductCacheEvent.class);
            }

            CartItemEntity item = CartItemEntity.builder()
                    .skuCode(sku)
                    .quantity(quantity)
                    .productName(productInfo != null ? productInfo.getName() : "Sản phẩm không rõ")
                    .price(productInfo != null ? productInfo.getPrice() : BigDecimal.ZERO)
                    .imageUrl(productInfo != null ? productInfo.getImageUrl() : null) // Giả sử ProductCacheEvent có imageUrl
                    .build();
            items.add(item);
        }

        return CartEntity.builder().userId(userId).items(items).build();
        // Lưu ý: 'version' và 'id' của item sẽ là null, điều này OK
    }

    // ==========================================================
    // HÀM CHECKOUT VÀ LISTENER (Giữ nguyên như đã sửa)
    // ==========================================================

    /**
     * Hàm checkout: Chỉ đọc (từ cache) và gửi Kafka.
     * (Hàm này giữ nguyên như chúng ta đã sửa trước đó)
     */
    public void checkout(String userId) {
        CartEntity cart = this.viewCart(userId);

        if (cart == null || cart.getItems().isEmpty()) {
            throw new IllegalStateException("Cart empty");
        }

        List<CartLineItem> items = cart.getItems().stream()
                .map(i -> new CartLineItem(i.getSkuCode(), i.getQuantity(), i.getPrice()))
                .collect(Collectors.toList());

        CartCheckoutEvent event = new CartCheckoutEvent(userId, items);

        kafkaTemplate.send(CHECKOUT_TOPIC, userId, event).whenComplete((md, ex) -> {
            if (ex != null) {
                log.error("Failed to send checkout event for user {}: {}", userId, ex.getMessage());
            } else {
                log.info("Checkout event sent for user {} partition={}", userId, md.getRecordMetadata().partition());
            }
        });
    }

    /**
     * Kafka Listener: Dọn dẹp CSDL và Redis bất đồng bộ.
     * (Hàm này giữ nguyên như chúng ta đã sửa trước đó)
     */
    // --- SỬA LẠI HÀM CLEANUP ---
    // Trong file CartService.java

    @KafkaListener(
            topics = CHECKOUT_TOPIC,
            groupId = CART_CLEANER_GROUP_ID,
            containerFactory = "kafkaListenerContainerFactory" // <-- THÊM DÒNG NÀY
    )
    @Transactional
    // SỬA 1: Đổi chữ ký từ List<Object> -> List<ConsumerRecord<String, Object>>
    public void handleCheckoutCleanup(List<ConsumerRecord<String, Object>> records) {
        log.info("CLEANUP: Bắt đầu dọn dẹp {} giỏ hàng", records.size());

        // SỬA 2: Lặp qua ConsumerRecord
        for (ConsumerRecord<String, Object> record : records) {
            try {
                // SỬA 3: PHẢI DÙNG record.value()
                CartCheckoutEvent event = objectMapper.convertValue(record.value(), CartCheckoutEvent.class);

                String userId = event.getUserId();
                log.info("CLEANUP: Dọn dẹp giỏ hàng cho user {}", userId);

                // ... (Logic dọn dẹp của bạn giữ nguyên) ...
                cartRepository.deleteById(userId);
                String qtyKey = REDIS_QTY_HASH_PREFIX + userId;
                String dataKey = REDIS_DATA_HASH_PREFIX + userId;
                redisTemplate.delete(List.of(qtyKey, dataKey)); // (Xóa 2 key hash)

                log.info("CLEANUP: Đã dọn dẹp (DB & 2 Hashes) cho user {}", userId);

            } catch (Exception e) {
                log.error("CLEANUP FAILED: Lỗi khi dọn dẹp giỏ hàng (record key {}): {}", record.key(), e.getMessage(), e);
            }
        }
    }
}