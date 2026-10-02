package com.myexampleproject.orderservice.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myexampleproject.common.dto.OrderLineItemRequest;
import com.myexampleproject.common.event.*;
import com.myexampleproject.orderservice.config.CartMapper;
import com.myexampleproject.orderservice.dto.OrderPaymentContextResponse;
import com.myexampleproject.orderservice.dto.OrderResponse;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.myexampleproject.common.event.InventoryCheckRequest;
import com.myexampleproject.common.event.InventoryCheckResult;
import org.springframework.data.redis.core.RedisTemplate; // <-- Bạn sẽ cần Redis
import java.time.Duration;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.kafka.listener.BatchListenerFailedException;
import com.myexampleproject.common.client.ProductCatalogClient;

import com.myexampleproject.common.dto.OrderLineItemsDto;
import com.myexampleproject.orderservice.dto.OrderRequest;
import com.myexampleproject.orderservice.model.Order;
import com.myexampleproject.orderservice.model.OrderLineItems;
import com.myexampleproject.orderservice.repository.OrderRepository;

import lombok.RequiredArgsConstructor;
import io.micrometer.core.instrument.Counter; // <-- THÊM IMPORT NÀY
import io.micrometer.core.instrument.MeterRegistry; // <-- THÊM IMPORT NÀY

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private  Counter ordersCompletedCounter;
    private  Counter ordersFailedCounter;
    // Inject thêm cái này để dùng trong @PostConstruct
    private final MeterRegistry meterRegistry;

    private final OrderRepository orderRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ProductCatalogClient catalog;
    private final TransactionTemplate transactions;

    // THÊM: Cần Redis để quản lý state của Saga
    private final RedisTemplate<String, Object> redisTemplate;
    private static final String SAGA_PREFIX = "saga:order:";
    private static final String PRODUCT_CACHE_KEY = "products:cache";

    //Viết hàm này vì dùng @RequiredArgsConstructor với biến không có final , Counter
    @PostConstruct
    public void initMetrics() {
        this.ordersCompletedCounter = Counter.builder("orders_processed_total")
                .tag("status", "completed")
                .description("Total successful orders")
                .register(meterRegistry);

        this.ordersFailedCounter = Counter.builder("orders_processed_total")
                .tag("status", "failed")
                .description("Total failed orders")
                .register(meterRegistry);
    }

    public String placeOrder(OrderRequest request, String userId) { return placeOrder(request, userId, null); }

    public String placeOrder(OrderRequest request, String userId, String idempotencyKey) {
        validateItems(request.getItems());
        String method = normalizePaymentMethod(request.getPaymentMethod());
        if (!Set.of("COD", "VNPAY").contains(method)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported payment method");
        if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > 128))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Idempotency-Key");
        try {
            String fingerprint = digest(objectMapper.writeValueAsString(request));
            String key = "checkout:order:" + userId + ":" + digest(idempotencyKey == null ? fingerprint : idempotencyKey);
            String candidate = UUID.randomUUID().toString();
            String value = candidate + "|" + fingerprint;
            boolean claimed = Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, value, idempotencyKey == null ? Duration.ofSeconds(10) : Duration.ofDays(1)));
            String existing = claimed ? value : (String)redisTemplate.opsForValue().get(key);
            if (existing == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkout changed; retry");
            String[] parts = existing.split("\\|", 2);
            if (parts.length != 2 || !fingerprint.equals(parts[1])) throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key was used with a different request");
            placeWithNumber(request, userId, parts[0]);
            return parts[0];
        } catch (ResponseStatusException ex) { throw ex; }
        catch (Exception ex) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Checkout unavailable"); }
    }

    private String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private void validateItems(List<OrderLineItemRequest> items) {
        if (items == null || items.isEmpty() || items.size() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order items are required");
        Set<String> seen = new HashSet<>();
        for (OrderLineItemRequest item : items) if (item == null || item.getSkuCode() == null || item.getSkuCode().isBlank()
                || item.getQuantity() == null || item.getQuantity() <= 0 || !seen.add(item.getSkuCode()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or duplicate order item");
    }

    private void placeWithNumber(OrderRequest request, String userId, String orderNumber) {
        validateItems(request.getItems());
        request.getItems().forEach(item -> catalog.find(item.getSkuCode()));
        publish("order-placed-topic", orderNumber, new OrderPlacedEvent(orderNumber, userId, request.getItems(), normalizePaymentMethod(request.getPaymentMethod()),
                safeText(request.getShippingAddressLabel(),128), safeText(request.getShippingRecipientName(),128),
                safeText(request.getShippingRecipientPhone(),32), safeText(request.getShippingAddressLine(),512)));
    }

    private void publish(String topic, String key, Object event) {
        try { kafkaTemplate.send(topic, key, event).get(10, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt();throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Event publication interrupted"); }
        catch (Exception ex) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Event publication failed"); }
    }

    // ==========================================================
    // SAGA LISTENER: Xử lý kết quả kiểm kê (ĐÃ SỬA LỖI 2 ITEMS)
    // ==========================================================
    @KafkaListener(topics = "inventory-check-result-topic", groupId = "order-saga-group")
    public void handleInventoryCheckResult(List<ConsumerRecord<String,Object>> records) {
        for (var record : records) try {
            transactions.executeWithoutResult(tx -> processInventoryResult(objectMapper.convertValue(record.value(), InventoryCheckResult.class)));
        } catch (Exception ex) { throw new BatchListenerFailedException("Inventory result processing failed", ex, record); }
    }

    private void processInventoryResult(InventoryCheckResult result) {
        Order order = orderRepository.findByOrderNumberForUpdate(result.getOrderNumber()).orElseThrow();
        if (!"PENDING".equals(order.getStatus())) return;
        String sagaKey = SAGA_PREFIX + order.getOrderNumber();
        OrderLineItems expected = order.getOrderLineItemsList().stream().filter(i -> i.getSkuCode().equals(result.getItem().getSkuCode())).findFirst().orElseThrow();
        if (!expected.getQuantity().equals(result.getItem().getQuantity())) throw new IllegalArgumentException("Inventory result quantity mismatch");
        redisTemplate.opsForHash().putIfAbsent(sagaKey, "result:" + expected.getSkuCode(), result.isSuccess());
        redisTemplate.expire(sagaKey, Duration.ofDays(1));
        Map<Object,Object> state = redisTemplate.opsForHash().entries(sagaKey);
        if (order.getOrderLineItemsList().stream().anyMatch(i -> !state.containsKey("result:" + i.getSkuCode()))) return;
        boolean success = order.getOrderLineItemsList().stream().allMatch(i -> Boolean.TRUE.equals(state.get("result:" + i.getSkuCode())));
        if (!success) {
            // Only successful deductions are restored, after every SKU result has arrived.
            for (OrderLineItems item : order.getOrderLineItemsList()) if (Boolean.TRUE.equals(state.get("result:" + item.getSkuCode())))
                publish("inventory-adjustment-topic", item.getSkuCode(), new InventoryAdjustmentEvent(item.getSkuCode(), item.getQuantity(), "INVENTORY_FAILED:" + order.getOrderNumber() + ":" + item.getSkuCode()));
            order.setStatus("FAILED");orderRepository.save(order);ordersFailedCounter.increment();
            publish("order-failed-topic", order.getOrderNumber(), new OrderFailedEvent(order.getOrderNumber(), "Insufficient inventory"));
        } else {
            order.setStatus("VALIDATED");orderRepository.save(order);
            if (!"VNPAY".equals(normalizePaymentMethod(order.getPaymentMethod())))
                publish("order-validated-topic", order.getOrderNumber(), new OrderValidatedEvent(order.getOrderNumber(), order.getOrderLineItemsList().stream()
                        .map(i -> new OrderLineItemRequest(i.getSkuCode(), i.getQuantity())).toList()));
        }
        publish("order-status-topic", order.getOrderNumber(), new OrderStatusEvent(order.getOrderNumber(), order.getStatus()));
    }
    // --- HELPER METHOD AN TOÀN ---
    private int parseIntegerSafely(Object obj) {
        if (obj instanceof Integer) {
            return (Integer) obj;
        } else if (obj instanceof Long) {
            return ((Long) obj).intValue();
        } else if (obj instanceof String) {
            return Integer.parseInt((String) obj);
        }
        throw new IllegalArgumentException("Cannot cast " + obj.getClass() + " to int");
    }

    // Dùng 1 group-id riêng cho việc xây dựng cache
    @KafkaListener(topics = "product-cache-update-topic", groupId = "order-product-cacher")
    public void handleProductCacheUpdate(List<ConsumerRecord<String,Object>> records) {
        for (var record : records) try {
            if (record.value() == null) redisTemplate.opsForHash().delete(PRODUCT_CACHE_KEY, record.key());
            else {
                ProductCacheEvent event = objectMapper.convertValue(record.value(), ProductCacheEvent.class);
                redisTemplate.opsForHash().put(PRODUCT_CACHE_KEY, event.getSkuCode(), event);
            }
        } catch (Exception ex) { throw new BatchListenerFailedException("Catalog cache processing failed", ex, record); }
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderDetails(String orderNumber, String requesterUserId, boolean admin) {
        log.info("Fetching order details for: {}", orderNumber);

        Order order = orderRepository.findByOrderNumberWithItems(orderNumber)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));

        validateOrderAccess(order, requesterUserId, admin);
        return mapToOrderResponse(order);
    }

    /**
     * Helper: Chuyển đổi Entity Order -> DTO OrderResponse.
     */
    private OrderResponse mapToOrderResponse(Order order) {
        return OrderResponse.builder()
                .id(order.getId())
                .orderNumber(order.getOrderNumber())
                .status(order.getStatus())
                .orderLineItemsList(order.getOrderLineItemsList()
                        .stream()
                        .map(this::mapToOrderLineItemsDto)
                        .toList())
                .totalPrice(order.getTotalPrice())
                .orderDate(order.getOrderDate())
                .userId(order.getUserId())
                .paymentMethod(order.getPaymentMethod())
                .shippingAddressLabel(order.getShippingAddressLabel())
                .shippingRecipientName(order.getShippingRecipientName())
                .shippingRecipientPhone(order.getShippingRecipientPhone())
                .shippingAddressLine(order.getShippingAddressLine())
                .cancelReason(order.getCancelReason())
                .cancelledAt(order.getCancelledAt())
                .build();
    }

    /**
     * Helper: Chuyển đổi Entity OrderLineItems -> DTO OrderLineItemsDto.
     * (Đây là logic ngược lại với hàm mapToDto bạn đã có)
     */
    // Hàm này được gọi trong getOrderDetails
    private OrderLineItemsDto mapToOrderLineItemsDto(OrderLineItems entity) {
        return OrderLineItemsDto.builder()
                .id(entity.getId())
                .skuCode(entity.getSkuCode())
                .price(entity.getPrice())
                .quantity(entity.getQuantity())

                // ✅ TRẢ VỀ CHO FRONTEND
                .productName(entity.getProductName())
                .color(entity.getColor())
                .size(entity.getSize())
                .build();
    }

    private void validateOrderAccess(Order order, String requesterUserId, boolean admin) {
        if (admin) {
            return;
        }

        if (requesterUserId == null || requesterUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Không xác định được người dùng hiện tại");
        }

        if (!requesterUserId.equals(order.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền xem đơn hàng này");
        }
    }

    // ==========================================================
    // SỬA LỖI 1 TẠI ĐÂY
    // ==========================================================
    @KafkaListener(topics = "cart-checkout-topic", groupId = "order-updater-group")
    public void handleCartCheckout(List<ConsumerRecord<String,Object>> records) {
        for (var record : records) try {
            CartCheckoutEvent event = objectMapper.convertValue(record.value(), CartCheckoutEvent.class);
            String id = event.getCheckoutId() == null ? UUID.nameUUIDFromBytes((record.topic() + ":" + record.partition() + ":" + record.offset()).getBytes(StandardCharsets.UTF_8)).toString() : event.getCheckoutId();
            placeWithNumber(CartMapper.fromCart(event), event.getUserId(), id);
        } catch (Exception ex) { throw new BatchListenerFailedException("Cart checkout processing failed", ex, record); }
    }


    @KafkaListener(
            topics = {
                    "order-placed-topic",
                    "order-failed-topic",
                    "payment-processed-topic",
                    "payment-failed-topic"
            },
            containerFactory = "kafkaListenerContainerFactory" // <-- Dùng factory chung
    )
    public void handleOrderEvents(List<ConsumerRecord<String,Object>> records) {
        for (var record : records) try {
            transactions.executeWithoutResult(tx -> {
                switch (record.topic()) {
                    case "order-placed-topic" -> handleOrderPlacement(objectMapper.convertValue(record.value(), OrderPlacedEvent.class));
                    case "order-failed-topic" -> handleOrderFailure(objectMapper.convertValue(record.value(), OrderFailedEvent.class));
                    case "payment-processed-topic" -> handlePaymentSuccess(objectMapper.convertValue(record.value(), PaymentProcessedEvent.class));
                    case "payment-failed-topic" -> handlePaymentFailure(objectMapper.convertValue(record.value(), PaymentFailedEvent.class));
                    default -> throw new IllegalArgumentException("Unexpected order event topic");
                }
            });
        } catch (Exception ex) { throw new BatchListenerFailedException("Order event processing failed", ex, record); }
    }

    public <T> T toEvent(Object payload, Class<T> clazz) {
        return objectMapper.convertValue(payload, clazz);
    }


    @Transactional
    protected void handleOrderPlacement(OrderPlacedEvent event) {
        validateItems(event.getOrderLineItemsDtoList());
        Order order = orderRepository.findByOrderNumberForUpdate(event.getOrderNumber()).orElse(null);
        if (order == null) {
            order = new Order();order.setOrderNumber(event.getOrderNumber());order.setUserId(event.getUserId());order.setStatus("PENDING");
            order.setPaymentMethod(normalizePaymentMethod(event.getPaymentMethod()));
            order.setShippingAddressLabel(safeText(event.getShippingAddressLabel(),128));order.setShippingRecipientName(safeText(event.getShippingRecipientName(),128));
            order.setShippingRecipientPhone(safeText(event.getShippingRecipientPhone(),32));order.setShippingAddressLine(safeText(event.getShippingAddressLine(),512));
            List<OrderLineItems> items = new ArrayList<>();
            for (OrderLineItemRequest request : event.getOrderLineItemsDtoList()) {
                var product = catalog.find(request.getSkuCode());
                OrderLineItems item = new OrderLineItems();item.setOrder(order);item.setSkuCode(request.getSkuCode());item.setQuantity(request.getQuantity());
                item.setPrice(product.price());item.setProductName(product.name());item.setColor(product.color());item.setSize(product.size());items.add(item);
            }
            order.setOrderLineItemsList(items);
            order.setTotalPrice(items.stream().map(i -> i.getPrice().multiply(BigDecimal.valueOf(i.getQuantity()))).reduce(BigDecimal.ZERO, BigDecimal::add));
            orderRepository.saveAndFlush(order);
        }
        if (!"PENDING".equals(order.getStatus())) return;
        // Replays retain any already received results and resend idempotent per-order/SKU checks.
        String key = SAGA_PREFIX + order.getOrderNumber();
        redisTemplate.expire(key, Duration.ofDays(1));
        for (OrderLineItems item : order.getOrderLineItemsList())
            publish("inventory-check-request-topic", item.getSkuCode(), new InventoryCheckRequest(order.getOrderNumber(), new OrderLineItemRequest(item.getSkuCode(), item.getQuantity())));
        publish("order-status-topic", order.getOrderNumber(), new OrderStatusEvent(order.getOrderNumber(), "PENDING"));
    }

    // Hàm này được gọi trong handleOrderPlacement
    private OrderLineItems mapToDtoWithPrice(OrderLineItemRequest itemRequest, ProductCacheEvent productInfo) {
        OrderLineItems orderLineItems = new OrderLineItems();
        orderLineItems.setQuantity(itemRequest.getQuantity());
        orderLineItems.setSkuCode(itemRequest.getSkuCode());

        // Lấy từ Cache (ProductCacheEvent)
        orderLineItems.setPrice(productInfo.getPrice());
        orderLineItems.setProductName(productInfo.getName());

        // GÁN GIÁ TRỊ MỚI TỪ CACHE VÀO ENTITY
        orderLineItems.setColor(productInfo.getColor());
        orderLineItems.setSize(productInfo.getSize());

        return orderLineItems;
    }

    @Transactional
    protected void handleOrderFailure(OrderFailedEvent failedEvent) {
        log.info("Using OrderFailedEvent class: {}", failedEvent.getClass().getName());
        log.warn("INVENTORY FAILED: Received feedback for Order {}. Reason: {}",
                failedEvent.getOrderNumber(), failedEvent.getReason());

        Order order = orderRepository.findByOrderNumberForUpdate(failedEvent.getOrderNumber())
                .orElseThrow(() -> new RuntimeException("Order not found: " + failedEvent.getOrderNumber()));
        if (order.getStatus().equals("PENDING")) {
            order.setStatus("FAILED");
            orderRepository.save(order);
            log.warn("Order {} status updated to FAILED due to inventory issue.", order.getOrderNumber());
            publish("order-status-topic", order.getOrderNumber(),
                    new OrderStatusEvent(order.getOrderNumber(), order.getStatus()));
            this.ordersFailedCounter.increment();
        } else {
            log.warn("Received failure event for order {} but status was not PENDING (Status: {}).",
                    order.getOrderNumber(), order.getStatus());
        }
    }

    @Transactional
    protected void handlePaymentSuccess(PaymentProcessedEvent paymentProcessedEvent) {
        log.info("SUCCESS: Received PaymentProcessedEvent for Order {}. Payment ID: {}. Updating status...",
                paymentProcessedEvent.getOrderNumber(), paymentProcessedEvent.getPaymentId());

        // Không cần try-catch ở đây nữa vì đã có ở hàm listener chính
        Order order = orderRepository.findByOrderNumberForUpdate(paymentProcessedEvent.getOrderNumber())
                .orElseThrow(() -> new RuntimeException("Order not found: " + paymentProcessedEvent.getOrderNumber()));

        if ("VALIDATED".equals(order.getStatus())) {
            order.setStatus("COMPLETED");
            order.setCancelReason(null);
            order.setCancelledAt(null);
            orderRepository.save(order);
            log.info("Order {} status updated to COMPLETED.", order.getOrderNumber());
            publish("order-status-topic", order.getOrderNumber(),
                    new OrderStatusEvent(order.getOrderNumber(), order.getStatus()));
            this.ordersCompletedCounter.increment();
        } else {
            log.warn("Received payment success for order {} but status was not PENDING (Status: {}).",
                    order.getOrderNumber(), order.getStatus());
        }
    }

    @Transactional
    protected void handlePaymentFailure(PaymentFailedEvent paymentFailedEvent) {
        log.warn("FAILED: Received PaymentFailedEvent for Order {}. Reason: {}. Updating status...",
                paymentFailedEvent.getOrderNumber(), paymentFailedEvent.getReason());
        Order order = orderRepository.findByOrderNumberForUpdate(paymentFailedEvent.getOrderNumber())
                .orElseThrow(() -> new RuntimeException("Order not found: " + paymentFailedEvent.getOrderNumber()));
        if ("VALIDATED".equals(order.getStatus())) {
            order.setStatus("PAYMENT_FAILED");
            orderRepository.save(order);
            restockOrderItems(order, "COMPENSATION: Payment Failed for Order " + order.getOrderNumber());
            log.warn("Order {} status updated to PAYMENT_FAILED.", order.getOrderNumber());
            publish("order-status-topic", order.getOrderNumber(),
                    new OrderStatusEvent(order.getOrderNumber(), order.getStatus()));
        } else {
            log.warn("Received payment failure for order {} but status was not PENDING (Status: {}).",
                    order.getOrderNumber(), order.getStatus());
        }
    }

    @KafkaListener(topics = "order-validated-topic", groupId = "order-group")
    public void handleValidated(List<ConsumerRecord<String,Object>> records) {
        for (var record : records) try {
            transactions.executeWithoutResult(tx -> {
                OrderValidatedEvent event = objectMapper.convertValue(record.value(), OrderValidatedEvent.class);
                Order order = orderRepository.findByOrderNumberForUpdate(event.getOrderNumber()).orElseThrow();
                if ("PENDING".equals(order.getStatus())) {
                    order.setStatus("VALIDATED");orderRepository.save(order);
                    publish("order-status-topic", order.getOrderNumber(), new OrderStatusEvent(order.getOrderNumber(), "VALIDATED"));
                }
            });
        } catch (Exception ex) { throw new BatchListenerFailedException("Validated order processing failed", ex, record); }
    }

    @Transactional(readOnly = true)
    public OrderPaymentContextResponse getPaymentContext(String orderNumber, String requesterUserId, boolean admin) {
        Order order = orderRepository.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));

        validateOrderAccess(order, requesterUserId, admin);
        return OrderPaymentContextResponse.builder()
                .orderNumber(order.getOrderNumber())
                .userId(order.getUserId())
                .status(order.getStatus())
                .paymentMethod(normalizePaymentMethod(order.getPaymentMethod()))
                .totalPrice(order.getTotalPrice())
                .shippingRecipientName(order.getShippingRecipientName())
                .shippingRecipientPhone(order.getShippingRecipientPhone())
                .shippingAddressLine(order.getShippingAddressLine())
                .build();
    }

    @Transactional
    public OrderResponse cancelOrder(String orderNumber, String requesterUserId, boolean admin, String reason) {
        Order order = orderRepository.findByOrderNumberForUpdate(orderNumber)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));

        validateOrderAccess(order, requesterUserId, admin);

        if (!("VALIDATED".equals(order.getStatus()) || "PAYMENT_FAILED".equals(order.getStatus()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Chỉ có thể hủy đơn đã xác nhận hoặc thanh toán thất bại. Đơn đang ở trạng thái: " + order.getStatus());
        }

        if ("VALIDATED".equals(order.getStatus())) restockOrderItems(order, "CANCELLED: " + orderNumber);
        order.setStatus("CANCELLED");
        order.setCancelReason(safeText(reason, 255));
        order.setCancelledAt(java.time.LocalDateTime.now());
        orderRepository.save(order);
        publish("order-status-topic", order.getOrderNumber(),
                new OrderStatusEvent(order.getOrderNumber(), order.getStatus()));

        return mapToOrderResponse(order);
    }

    private void restockOrderItems(Order order, String reasonPrefix) {
        List<OrderLineItems> items = order.getOrderLineItemsList();
        for (OrderLineItems item : items) {
            InventoryAdjustmentEvent adjustmentEvent = InventoryAdjustmentEvent.builder()
                    .skuCode(item.getSkuCode())
                    .adjustmentQuantity(item.getQuantity())
                    .reason(reasonPrefix + " | SKU=" + item.getSkuCode())
                    .build();
            publish("inventory-adjustment-topic", item.getSkuCode(), adjustmentEvent);
            log.info("RESTOCK: Sent inventory adjustment for SKU {} (+{})", item.getSkuCode(), item.getQuantity());
        }
    }

    private String normalizePaymentMethod(String paymentMethod) {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            return "COD";
        }
        return paymentMethod.trim().toUpperCase();
    }

    private String safeText(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getOrdersForUser(String userId) {
        return orderRepository.findAllByUserIdOrderByOrderDateDesc(userId).stream()
                .map(this::mapToOrderResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getAllOrdersForAdmin() {
        return orderRepository.findAllByOrderByOrderDateDesc().stream()
                .map(this::mapToOrderResponse)
                .toList();
    }

    private OrderLineItems mapToDto(OrderLineItemsDto orderLineItemsDto) {
        OrderLineItems orderLineItems = new OrderLineItems();
        orderLineItems.setPrice(orderLineItemsDto.getPrice());
        orderLineItems.setQuantity(orderLineItemsDto.getQuantity());
        orderLineItems.setSkuCode(orderLineItemsDto.getSkuCode());
        return orderLineItems;
    }
}