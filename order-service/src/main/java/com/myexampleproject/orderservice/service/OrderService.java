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

    public String placeOrder(OrderRequest orderRequest, String userId) {
        String orderNumber = UUID.randomUUID().toString();
        log.info("Order {} received. Starting Inventory SAGA...", orderNumber);

        List<OrderLineItemRequest> items = orderRequest.getItems();
        String paymentMethod = normalizePaymentMethod(orderRequest.getPaymentMethod());

        OrderPlacedEvent placedEvent = new OrderPlacedEvent(
                orderNumber,
                userId,
                items,
                paymentMethod,
                safeText(orderRequest.getShippingAddressLabel(), 128),
                safeText(orderRequest.getShippingRecipientName(), 128),
                safeText(orderRequest.getShippingRecipientPhone(), 32),
                safeText(orderRequest.getShippingAddressLine(), 512)
        );
        kafkaTemplate.send("order-placed-topic", orderNumber, placedEvent);
        return orderNumber;
    }

    // ==========================================================
    // SAGA LISTENER: Xử lý kết quả kiểm kê (ĐÃ SỬA LỖI 2 ITEMS)
    // ==========================================================
    @KafkaListener(topics = "inventory-check-result-topic", groupId = "order-saga-group")
    public void handleInventoryCheckResult(List<ConsumerRecord<String, Object>> records) {
        log.info("SAGA: Received batch of {} inventory results", records.size());

        for (ConsumerRecord<String, Object> record : records) {
            try {
                Object payload = record.value();
                InventoryCheckResult result = objectMapper.convertValue(payload, InventoryCheckResult.class);

                String orderNumber = result.getOrderNumber();
                String sagaKey = SAGA_PREFIX + orderNumber;

                log.info("SAGA: Result for Order {}, SKU {}: Success={}",
                        orderNumber, result.getItem().getSkuCode(), result.isSuccess());

                // 1. Tăng biến đếm (Atomic Increment)
                // Lệnh này an toàn kể cả khi key chưa có (nó sẽ tạo mới và set = 1)
                Long receivedCount = redisTemplate.opsForHash().increment(sagaKey, "receivedItems", 1);

                // 2. Kiểm tra xem đã fail trước đó chưa
                Object failedState = redisTemplate.opsForHash().get(sagaKey, "failed");
                boolean alreadyFailed = (failedState != null) && (Boolean) failedState;

                if (alreadyFailed) {
                    log.info("SAGA: Order {} already marked failed. Ignoring.", orderNumber);
                    continue;
                }

                // 3. Nếu item này thất bại
                if (!result.isSuccess()) {
                    log.warn("SAGA: Inventory check FAILED for Order {}, SKU {}. Reason: {}",
                            orderNumber, result.getItem().getSkuCode(), result.getReason());

                    redisTemplate.opsForHash().put(sagaKey, "failed", true);
                    kafkaTemplate.send("order-failed-topic", orderNumber, new OrderFailedEvent(orderNumber, result.getReason()));
                    continue;
                }

                // 4. Kiểm tra tổng số items (SỬA LỖI CASTING TẠI ĐÂY)
                Object totalObj = redisTemplate.opsForHash().get(sagaKey, "totalItems");
                if (totalObj == null) {
                    // Có thể do Redis hết hạn hoặc race condition cực hiếm
                    log.warn("SAGA: State missing for order {}. Waiting...", orderNumber);
                    continue;
                }

                // Helper để chuyển đổi số an toàn (tránh ClassCastException Long vs Integer)
                int totalItems = parseIntegerSafely(totalObj);

                log.debug("SAGA: Order {} progress: {}/{}", orderNumber, receivedCount, totalItems);

                if (receivedCount == totalItems) {
                    log.info("SAGA COMPLETE: Order {} passed all inventory checks.", orderNumber);

                    Object requestObj = redisTemplate.opsForHash().get(sagaKey, "request");
                    OrderRequest originalRequest = objectMapper.convertValue(requestObj, OrderRequest.class);
                    String paymentMethod = normalizePaymentMethod(originalRequest.getPaymentMethod());

                    if ("VNPAY".equals(paymentMethod)) {
                        Order order = orderRepository.findByOrderNumber(orderNumber)
                                .orElseThrow(() -> new RuntimeException("Order not found: " + orderNumber));
                        if ("PENDING".equals(order.getStatus())) {
                            order.setStatus("VALIDATED");
                            orderRepository.save(order);
                            kafkaTemplate.send("order-status-topic", orderNumber,
                                    new OrderStatusEvent(orderNumber, "VALIDATED"));
                        }
                    } else {
                        kafkaTemplate.send("order-validated-topic", orderNumber,
                                new OrderValidatedEvent(orderNumber, originalRequest.getItems()));
                    }

                    redisTemplate.delete(sagaKey);
                }

            } catch (Exception e) {
                log.error("SAGA ERROR: Key: {}", record.key(), e);
            }
        }
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
    public void handleProductCacheUpdate(List<ConsumerRecord<String, Object>> records) {
        log.info("Receiving {} product cache updates...", records.size());
        for (ConsumerRecord<String, Object> record : records) {
            try {
                // Deserialize
                ProductCacheEvent event = objectMapper.convertValue(record.value(), ProductCacheEvent.class);
                String sku = event.getSkuCode();

                // Lưu vào REDIS HASH
                // Key: "products:cache"
                // HashKey: "SKU_CODE"
                // Value: Toàn bộ object 'event' (chứa giá + tên)
                redisTemplate.opsForHash().put(PRODUCT_CACHE_KEY, sku, event);

                log.debug("Cached product info for SKU: {}", sku);

            } catch (Exception e) {
                log.error("LỖI KHI CACHING PRODUCT {}: {}", record.key(), e.getMessage());
            }
        }
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderDetails(String orderNumber, String requesterUserId, boolean admin) {
        log.info("Fetching order details for: {}", orderNumber);

        Order order = orderRepository.findByOrderNumberWithItems(orderNumber)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderNumber));

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
    public void handleCartCheckout(List<ConsumerRecord<String, Object>> records) {
        log.info("Received a batch of {} cart-checkout events", records.size());

        for (ConsumerRecord<String, Object> record : records) {
            try {
                Object payload = record.value();
                log.info("Processing cart checkout for user: {}", record.key());

                CartCheckoutEvent event = objectMapper.convertValue(payload, CartCheckoutEvent.class);

                // Convert CartCheckoutEvent -> OrderRequest
                OrderRequest req = CartMapper.fromCart(event);

                placeOrder(req, event.getUserId()); // Gọi trực tiếp

            } catch (Exception e) {
                log.error("LỖI KHI XỬ LÝ CartCheckoutEvent: {}. Sẽ KHÔNG retry.", record.key(), e);
            }
        }
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
    public void handleOrderEvents(List<ConsumerRecord<String, Object>> records) {
        log.info("Received a batch of {} events", records.size());

        // Loop qua danh sách
        for (ConsumerRecord<String, Object> record : records) {
            String topic = record.topic();
            Object payload = record.value();
            log.debug("Processing event from topic [{}], key [{}]", topic, record.key());

            // Logic switch-case của bạn giữ nguyên
            try {
                switch (topic) {
                    case "order-placed-topic":
                        OrderPlacedEvent placedEvent = objectMapper.convertValue(payload, OrderPlacedEvent.class);
                        handleOrderPlacement(placedEvent); // Hàm private này giữ nguyên
                        break;

                    case "order-failed-topic":
                        OrderFailedEvent failedEvent = objectMapper.convertValue(payload, OrderFailedEvent.class);
                        handleOrderFailure(failedEvent); // Hàm private này giữ nguyên
                        break;

                    case "payment-processed-topic":
                        PaymentProcessedEvent processedEvent = objectMapper.convertValue(payload, PaymentProcessedEvent.class);
                        handlePaymentSuccess(processedEvent);
                        break;

                    case "payment-failed-topic":
                        PaymentFailedEvent paymentFailedEvent = objectMapper.convertValue(payload, PaymentFailedEvent.class);
                        handlePaymentFailure(paymentFailedEvent);
                        break;

                    default:
                        log.warn("Received message on unhandled topic: {}", topic);
                }
            } catch (Exception e) {
                log.error("LỖI KHI XỬ LÝ MESSAGE: {}. Sẽ KHÔNG retry.", record.key(), e);
            }
        }
    }

    public <T> T toEvent(Object payload, Class<T> clazz) {
        return objectMapper.convertValue(payload, clazz);
    }


    @Transactional
    protected void handleOrderPlacement(OrderPlacedEvent event) {
        log.info("Async Save: Saving Order {} to database...", event.getOrderNumber());

        Order order = new Order();
        order.setOrderNumber(event.getOrderNumber());
        order.setUserId(event.getUserId());
        order.setStatus("PENDING");
        order.setPaymentMethod(normalizePaymentMethod(event.getPaymentMethod()));
        order.setShippingAddressLabel(safeText(event.getShippingAddressLabel(), 128));
        order.setShippingRecipientName(safeText(event.getShippingRecipientName(), 128));
        order.setShippingRecipientPhone(safeText(event.getShippingRecipientPhone(), 32));
        order.setShippingAddressLine(safeText(event.getShippingAddressLine(), 512));

        List<OrderLineItemRequest> itemRequests = event.getOrderLineItemsDtoList();

        // Tạo List<OrderLineItems> (Entity) mới
        List<OrderLineItems> orderLineItemsEntities = new ArrayList<>();

        for (OrderLineItemRequest itemReq : itemRequests) {

            // --- LOGIC SỬA ĐỔI BẮT ĐẦU TỪ ĐÂY ---

            // 1. Lấy thông tin sản phẩm từ Cache
            Object cachedData = redisTemplate.opsForHash().get(PRODUCT_CACHE_KEY, itemReq.getSkuCode());

            if (cachedData == null) {
                // Lỗi nghiêm trọng: Sản phẩm không có trong cache
                // (Trong thực tế, bạn có thể gọi API dự phòng, hoặc FAILED đơn hàng)
                log.error("KHÔNG TÌM THẤY CACHE cho SKU: {}", itemReq.getSkuCode());
                // Tạm thời FAILED đơn hàng này
                throw new RuntimeException("Product not in cache: " + itemReq.getSkuCode());
            }

            // 2. Convert cache (là ProductCacheEvent)
            ProductCacheEvent productInfo = objectMapper.convertValue(cachedData, ProductCacheEvent.class);

            // 3. Gọi hàm mapToDto (đã sửa) với giá
            OrderLineItems entity = mapToDtoWithPrice(itemReq, productInfo);

            // 4. Thiết lập quan hệ
            entity.setOrder(order);
            orderLineItemsEntities.add(entity);

            // --- LOGIC SỬA ĐỔI KẾT THÚC ---
        }

        order.setOrderLineItemsList(orderLineItemsEntities);

        BigDecimal totalPrice = orderLineItemsEntities.stream()
                // Nhân giá (price) với số lượng (quantity) của từng món
                .map(item -> item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                // Cộng tất cả kết quả lại
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        order.setTotalPrice(totalPrice);


        orderRepository.save(order);
        log.info("Async Save: Order {} saved to database.", event.getOrderNumber());

        List<OrderLineItemRequest> items = event.getOrderLineItemsDtoList(); // Lấy từ event
        String orderNumber = event.getOrderNumber();

        // A. Lưu state vào Redis
        Map<String, Object> sagaState = Map.of(
                "totalItems", items.size(),
                "receivedItems", 0,
                "failed", false,
                "request", OrderRequest.builder()
                        .items(items)
                        .paymentMethod(normalizePaymentMethod(event.getPaymentMethod()))
                        .shippingAddressLabel(event.getShippingAddressLabel())
                        .shippingRecipientName(event.getShippingRecipientName())
                        .shippingRecipientPhone(event.getShippingRecipientPhone())
                        .shippingAddressLine(event.getShippingAddressLine())
                        .build()
        );
        redisTemplate.opsForHash().putAll(SAGA_PREFIX + orderNumber, sagaState);
        redisTemplate.expire(SAGA_PREFIX + orderNumber, Duration.ofMinutes(10));

        // B. Gửi yêu cầu kiểm tra kho
        for (OrderLineItemRequest item : items) {
            InventoryCheckRequest checkRequest = new InventoryCheckRequest(orderNumber, item);
            kafkaTemplate.send("inventory-check-request-topic", item.getSkuCode(), checkRequest);
        }

        log.info("SAGA started for persisted Order {}. Check requests sent.", orderNumber);

        OrderStatusEvent statusEvent = new OrderStatusEvent(event.getOrderNumber(), "PENDING");
        kafkaTemplate.send("order-status-topic", event.getOrderNumber(), statusEvent);
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

        Order order = orderRepository.findByOrderNumber(failedEvent.getOrderNumber())
                .orElseThrow(() -> new RuntimeException("Order not found: " + failedEvent.getOrderNumber()));
        if (order.getStatus().equals("PENDING")) {
            order.setStatus("FAILED");
            orderRepository.save(order);
            log.warn("Order {} status updated to FAILED due to inventory issue.", order.getOrderNumber());
            kafkaTemplate.send("order-status-topic", order.getOrderNumber(),
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
        Order order = orderRepository.findByOrderNumber(paymentProcessedEvent.getOrderNumber())
                .orElseThrow(() -> new RuntimeException("Order not found: " + paymentProcessedEvent.getOrderNumber()));

        if ("PENDING".equals(order.getStatus()) || "VALIDATED".equals(order.getStatus())) {
            order.setStatus("COMPLETED");
            order.setCancelReason(null);
            order.setCancelledAt(null);
            orderRepository.save(order);
            log.info("Order {} status updated to COMPLETED.", order.getOrderNumber());
            kafkaTemplate.send("order-status-topic", order.getOrderNumber(),
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
        Order order = orderRepository.findByOrderNumberWithItems(paymentFailedEvent.getOrderNumber())
                .orElseThrow(() -> new RuntimeException("Order not found: " + paymentFailedEvent.getOrderNumber()));
        if ("PENDING".equals(order.getStatus()) || "VALIDATED".equals(order.getStatus())) {
            order.setStatus("PAYMENT_FAILED");
            orderRepository.save(order);
            restockOrderItems(order, "COMPENSATION: Payment Failed for Order " + order.getOrderNumber());
            log.warn("Order {} status updated to PAYMENT_FAILED.", order.getOrderNumber());
            kafkaTemplate.send("order-status-topic", order.getOrderNumber(),
                    new OrderStatusEvent(order.getOrderNumber(), order.getStatus()));
        } else {
            log.warn("Received payment failure for order {} but status was not PENDING (Status: {}).",
                    order.getOrderNumber(), order.getStatus());
        }
    }

    @KafkaListener(topics = "order-validated-topic", groupId = "order-group")
    public void handleValidated(List<ConsumerRecord<String, Object>> records) { // <-- SỬA 1: Nhận List
        log.info("SAGA SUCCESS: Received batch of {} validated events", records.size());

        for (ConsumerRecord<String, Object> record : records) { // <-- SỬA 2: Thêm vòng lặp
            try {
                // SỬA 3: Deserialization thủ công
                Object payload = record.value();
                OrderValidatedEvent event = objectMapper.convertValue(payload, OrderValidatedEvent.class);

                // --- (Logic cũ của bạn bắt đầu từ đây) ---
                log.info("SAGA SUCCESS: Order {} validated, updating status.", event.getOrderNumber());
                Order order = orderRepository.findByOrderNumber(event.getOrderNumber())
                        .orElseThrow(() -> new RuntimeException("Order not found: " + event.getOrderNumber()));

                if ("PENDING".equals(order.getStatus())) {
                    order.setStatus("VALIDATED"); // Trạng thái "đã xác thực kho"
                    orderRepository.save(order);
                    kafkaTemplate.send("order-status-topic",
                            order.getOrderNumber(),
                            new OrderStatusEvent(order.getOrderNumber(), "VALIDATED")
                    );

                    // (Bạn có thể kích hoạt payment-service từ đây nếu muốn)

                } else {
                    log.warn("Received validated event for order {} but status was not PENDING (Status: {}).",
                            order.getOrderNumber(), order.getStatus());
                }
                // --- (Logic cũ kết thúc) ---

            } catch (Exception e) {
                log.error("SAGA: LỖI KHI XỬ LÝ OrderValidatedEvent: {}. Sẽ KHÔNG retry.", record.key(), e);
            }
        }
    }

    @Transactional(readOnly = true)
    public OrderPaymentContextResponse getPaymentContext(String orderNumber) {
        Order order = orderRepository.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderNumber));

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
        Order order = orderRepository.findByOrderNumberWithItems(orderNumber)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderNumber));

        validateOrderAccess(order, requesterUserId, admin);

        if (!("VALIDATED".equals(order.getStatus()) || "PAYMENT_FAILED".equals(order.getStatus()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Chỉ có thể hủy đơn đã xác nhận hoặc thanh toán thất bại. Đơn đang ở trạng thái: " + order.getStatus());
        }

        restockOrderItems(order, "CANCELLED: " + orderNumber);
        order.setStatus("CANCELLED");
        order.setCancelReason(safeText(reason, 255));
        order.setCancelledAt(java.time.LocalDateTime.now());
        orderRepository.save(order);
        kafkaTemplate.send("order-status-topic", order.getOrderNumber(),
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
            kafkaTemplate.send("inventory-adjustment-topic", item.getSkuCode(), adjustmentEvent);
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