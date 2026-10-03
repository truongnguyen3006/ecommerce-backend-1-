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
import com.myexampleproject.common.outbox.JdbcOutbox;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.myexampleproject.common.event.InventoryCheckRequest;
import com.myexampleproject.common.event.InventoryCheckResult;
import org.springframework.data.redis.core.RedisTemplate; // <-- Bạn sẽ cần Redis
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
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
    private final JdbcOutbox outbox;
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
        transactions.executeWithoutResult(tx -> publish("order-placed-topic", orderNumber,
                new OrderPlacedEvent(orderNumber, userId, request.getItems(), normalizePaymentMethod(request.getPaymentMethod()),
                safeText(request.getShippingAddressLabel(),128), safeText(request.getShippingRecipientName(),128),
                safeText(request.getShippingRecipientPhone(),32), safeText(request.getShippingAddressLine(),512))));
    }

    private void publish(String topic, String key, Object event) {
        outbox.append(topic, key, event);
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
        OrderLineItems expected = order.getOrderLineItemsList().stream().filter(i -> i.getSkuCode().equals(result.getItem().getSkuCode())).findFirst().orElseThrow();
        if (!expected.getQuantity().equals(result.getItem().getQuantity())) throw new IllegalArgumentException("Inventory result quantity mismatch");
        if (expected.getInventoryOutcome() != null && expected.getInventoryOutcome() != result.isSuccess()) {
            investigate(order, "CONFLICTING_INVENTORY_PROOF");return;
        }
        expected.setInventoryOutcome(result.isSuccess());
        orderRepository.save(order);
        if (order.getOrderLineItemsList().stream().anyMatch(i -> i.getInventoryOutcome() == null)) return;
        boolean success = order.getOrderLineItemsList().stream().allMatch(i -> Boolean.TRUE.equals(i.getInventoryOutcome()));
        order.setWorkflowInvestigationRequired(false);order.setWorkflowInvestigationReason(null);
        if (!success) {
            // Only successful deductions are restored, after every SKU result has arrived.
            for (OrderLineItems item : order.getOrderLineItemsList()) if (Boolean.TRUE.equals(item.getInventoryOutcome()))
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
                .onlinePaymentInFlight(order.getPaymentAttemptId() != null && "VALIDATED".equals(order.getStatus()))
                .paymentReconciliationRequired(order.isPaymentReconciliationRequired())
                .workflowInvestigationRequired(order.isWorkflowInvestigationRequired())
                .workflowInvestigationReason(order.getWorkflowInvestigationReason())
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
                    "payment-failed-topic",
                    "online-payment-received-topic",
                    "payment-investigation-topic"
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
                    case "online-payment-received-topic" -> handleOnlinePaymentReceived(objectMapper.convertValue(record.value(), OnlinePaymentReceivedEvent.class));
                    case "payment-investigation-topic" -> {
                        PaymentInvestigationEvent event = objectMapper.convertValue(record.value(), PaymentInvestigationEvent.class);
                        Order order = orderRepository.findByOrderNumberForUpdate(event.orderNumber()).orElseThrow();
                        if (Objects.equals(order.getPaymentAttemptId(), event.txnRef()) && !"COMPLETED".equals(order.getStatus())) investigate(order, event.reason());
                    }
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
        // SQL receipts survive Redis eviction and service restarts.
        for (OrderLineItems item : order.getOrderLineItemsList()) if (item.getInventoryOutcome() == null)
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
            // A coarse failure cannot prove which checks deducted stock. Keep allocation until proof arrives.
            investigate(order, "ORDER_FAILURE_WITHOUT_COMPLETE_INVENTORY_PROOF");
        } else {
            log.warn("Received failure event for order {} but status was not PENDING (Status: {}).",
                    order.getOrderNumber(), order.getStatus());
        }
    }

    private void handleOnlinePaymentReceived(OnlinePaymentReceivedEvent receipt) {
        Order order = orderRepository.findByOrderNumberForUpdate(receipt.getOrderNumber()).orElseThrow();
        boolean sameReceipt = receipt.getTxnRef().equals(order.getPaymentReceivedRef());
        boolean accepted = "COMPLETED".equals(order.getStatus()) && sameReceipt && !order.isPaymentReconciliationRequired();
        if ("VALIDATED".equals(order.getStatus()) && "VNPAY".equals(order.getPaymentMethod())
                && (order.getPaymentAttemptId() == null || order.getPaymentAttemptId().equals(receipt.getTxnRef()))
                && order.getTotalPrice().compareTo(receipt.getAmount()) == 0 && !order.isPaymentReconciliationRequired()) {
            order.setPaymentReceivedRef(receipt.getTxnRef());
            order.setWorkflowInvestigationRequired(false);order.setWorkflowInvestigationReason(null);
            order.setStatus("COMPLETED");
            orderRepository.save(order);
            publish("order-status-topic", order.getOrderNumber(), new OrderStatusEvent(order.getOrderNumber(), "COMPLETED"));
            ordersCompletedCounter.increment();
            accepted = true;
        }
        if (!accepted) {
            order.setPaymentReconciliationRequired(true);
            if (order.getPaymentReceivedRef() == null) order.setPaymentReceivedRef(receipt.getTxnRef());
            orderRepository.save(order);
            log.error("Online payment requires reconciliation order={} reference={} state={}",
                    order.getOrderNumber(), receipt.getTxnRef(), order.getStatus());
        }
        // Same local transaction as the order decision. Duplicate receipts resend an idempotent acknowledgement.
        publish("online-payment-decision-topic", order.getOrderNumber(), new OnlinePaymentDecisionEvent(
                order.getOrderNumber(), receipt.getTxnRef(), accepted, accepted ? "ACCEPTED" : "ORDER_ALLOCATION_INCOMPATIBLE"));
    }

    @Transactional
    protected void handlePaymentSuccess(PaymentProcessedEvent paymentProcessedEvent) {
        log.info("SUCCESS: Received PaymentProcessedEvent for Order {}. Payment ID: {}. Updating status...",
                paymentProcessedEvent.getOrderNumber(), paymentProcessedEvent.getPaymentId());

        // Không cần try-catch ở đây nữa vì đã có ở hàm listener chính
        Order order = orderRepository.findByOrderNumberForUpdate(paymentProcessedEvent.getOrderNumber())
                .orElseThrow(() -> new RuntimeException("Order not found: " + paymentProcessedEvent.getOrderNumber()));

        if ("VNPAY".equals(order.getPaymentMethod())) {
            // Online completion is owned by the verified receipt/Order decision handshake.
            if ("COMPLETED".equals(order.getStatus()) && Objects.equals(order.getPaymentReceivedRef(),paymentProcessedEvent.getPaymentId()) && !order.isPaymentReconciliationRequired()) return;
            order.setPaymentReconciliationRequired(true);orderRepository.save(order);
            publish("online-payment-decision-topic",order.getOrderNumber(),new OnlinePaymentDecisionEvent(order.getOrderNumber(),paymentProcessedEvent.getPaymentId(),false,"UNPROVEN_LEGACY_PAYMENT"));
            return;
        }
        if ("VALIDATED".equals(order.getStatus()) && !order.isPaymentReconciliationRequired()) {
            if ("VNPAY".equals(order.getPaymentMethod())) order.setPaymentReceivedRef(paymentProcessedEvent.getPaymentId());
            order.setStatus("COMPLETED");
            order.setWorkflowInvestigationRequired(false);order.setWorkflowInvestigationReason(null);
            order.setCancelReason(null);
            order.setCancelledAt(null);
            orderRepository.save(order);
            log.info("Order {} status updated to COMPLETED.", order.getOrderNumber());
            publish("order-status-topic", order.getOrderNumber(),
                    new OrderStatusEvent(order.getOrderNumber(), order.getStatus()));
            this.ordersCompletedCounter.increment();
        } else {
            if ("VNPAY".equals(order.getPaymentMethod()) && !"COMPLETED".equals(order.getStatus())) {
                order.setPaymentReconciliationRequired(true);orderRepository.save(order);
                publish("online-payment-decision-topic", order.getOrderNumber(), new OnlinePaymentDecisionEvent(
                        order.getOrderNumber(), paymentProcessedEvent.getPaymentId(), false, "LATE_LEGACY_PAYMENT"));
            }
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
        if ("VNPAY".equals(order.getPaymentMethod()) && (order.getPaymentReceivedRef() != null
                || paymentFailedEvent.getTxnRef() == null || !Objects.equals(order.getPaymentAttemptId(), paymentFailedEvent.getTxnRef()))) {
            if (!"COMPLETED".equals(order.getStatus())) investigate(order, "UNMATCHED_PAYMENT_FAILURE");
            return;
        }
        if ("VALIDATED".equals(order.getStatus()) && !order.isPaymentReconciliationRequired()) {
            order.setStatus("PAYMENT_FAILED");
            order.setWorkflowInvestigationRequired(false);order.setWorkflowInvestigationReason(null);
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
                if ("PENDING".equals(order.getStatus()) && !order.getOrderLineItemsList().isEmpty()
                        && order.getOrderLineItemsList().stream().allMatch(i -> Boolean.TRUE.equals(i.getInventoryOutcome()))) {
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
    public void beginOnlinePayment(String orderNumber, String requesterUserId, String txnRef) {
        if (txnRef == null || txnRef.isBlank() || txnRef.length() > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payment reference");
        Order order = orderRepository.findByOrderNumberForUpdate(orderNumber)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        validateOrderAccess(order, requesterUserId, false);
        if (!"VALIDATED".equals(order.getStatus()) || !"VNPAY".equals(order.getPaymentMethod())
                || order.isWorkflowInvestigationRequired() || order.isPaymentReconciliationRequired())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order cannot start an online payment");
        if (order.getPaymentAttemptId() != null && !txnRef.equals(order.getPaymentAttemptId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Another payment attempt is already recorded");
        if (order.getPaymentAttemptId() == null) order.setRecoveryNextAt(LocalDateTime.now().plusMinutes(Math.max(1,agedMinutes)));
        order.setPaymentAttemptId(txnRef);
        orderRepository.save(order);
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

        if (order.isWorkflowInvestigationRequired() || order.isPaymentReconciliationRequired() || ("VALIDATED".equals(order.getStatus()) && order.getPaymentAttemptId() != null)) {
            throw new com.myexampleproject.common.exception.DomainException(HttpStatus.CONFLICT,"ONLINE_PAYMENT_IN_FLIGHT","ONLINE_PAYMENT_IN_FLIGHT");
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
            log.info("RESTOCK: Persisted inventory adjustment intent for SKU {} (+{})", item.getSkuCode(), item.getQuantity());
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
    @org.springframework.beans.factory.annotation.Value("${app.saga.aged-minutes:30}")
    private long agedMinutes = 30;
    @org.springframework.beans.factory.annotation.Value("${app.saga.max-retries:5}")
    private int recoveryMaxRetries = 5;

    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${app.saga.scan-ms:30000}")
    public void recoverAgedWorkflows() {
        LocalDateTime now = LocalDateTime.now();
        for (String id : orderRepository.findRecoveryCandidates(now.minusMinutes(Math.max(1,agedMinutes)), now,
                org.springframework.data.domain.PageRequest.of(0,20))) recoverWorkflow(id, now);
    }

    public void recoverWorkflow(String id, LocalDateTime now) {
        transactions.executeWithoutResult(tx -> {
            Order order = orderRepository.findByOrderNumberForUpdate(id).orElseThrow();
            if (!Set.of("PENDING","VALIDATED").contains(order.getStatus()) || order.isWorkflowInvestigationRequired()
                    || order.isPaymentReconciliationRequired() || (order.getRecoveryNextAt() != null && order.getRecoveryNextAt().isAfter(now))) return;
            if ("VALIDATED".equals(order.getStatus()) && "VNPAY".equals(order.getPaymentMethod())) {
                // Awaiting a shopper is not a stuck saga. An issued attempt is uncertain; never release its fence.
                if (order.getPaymentAttemptId() != null) investigate(order,"AGED_ONLINE_PAYMENT");
                else order.setRecoveryNextAt(now.plusHours(24));
                return;
            }
            if (order.getRecoveryAttempts() >= Math.max(1,recoveryMaxRetries)) { investigate(order,"SAGA_RETRIES_EXHAUSTED");return; }
            if ("PENDING".equals(order.getStatus())) {
                for (OrderLineItems item : order.getOrderLineItemsList()) if (item.getInventoryOutcome() == null)
                    publish("inventory-check-request-topic",item.getSkuCode(),new InventoryCheckRequest(id,new OrderLineItemRequest(item.getSkuCode(),item.getQuantity())));
            } else publish("order-validated-topic",id,new OrderValidatedEvent(id,order.getOrderLineItemsList().stream()
                    .map(i -> new OrderLineItemRequest(i.getSkuCode(),i.getQuantity())).toList()));
            order.setRecoveryAttempts(order.getRecoveryAttempts()+1);
            order.setRecoveryNextAt(now.plusSeconds(Math.min(300,10L << Math.min(order.getRecoveryAttempts(),5))));
            orderRepository.save(order);
        });
    }

    @Transactional
    public void retryInvestigatedInventory(String id) {
        Order order=orderRepository.findByOrderNumberForUpdate(id).orElseThrow();
        if (!"PENDING".equals(order.getStatus()) || order.isPaymentReconciliationRequired() || order.getPaymentAttemptId()!=null)
            throw new com.myexampleproject.common.exception.DomainException(HttpStatus.CONFLICT,"RECOVERY_UNSAFE","This workflow requires accounting investigation");
        order.setWorkflowInvestigationRequired(false);order.setWorkflowInvestigationReason(null);
        order.setRecoveryAttempts(0);order.setRecoveryNextAt(null);orderRepository.save(order);
        recoverWorkflow(id,LocalDateTime.now());
    }

    private void investigate(Order order, String reason) {
        order.setWorkflowInvestigationRequired(true);order.setWorkflowInvestigationReason(reason);
        orderRepository.save(order);
        log.error("Workflow investigation required order={} reason={}",order.getOrderNumber(),reason);
    }

    @KafkaListener(topics={"inventory-check-result-topic.DLT","order-placed-topic.DLT","payment-failed-topic.DLT",
            "payment-processed-topic.DLT","online-payment-received-topic.DLT","payment-investigation-topic.DLT","online-payment-decision-topic.DLT","order-validated-topic.DLT","order-failed-topic.DLT"},groupId="order-investigation-group",
            containerFactory="workflowDeadLetterKafkaListenerContainerFactory")
    public void recordDeadLetters(List<ConsumerRecord<String,Object>> records) {
        for (var record : records) transactions.executeWithoutResult(tx -> {
            String orderNumber = null;
            try { orderNumber = objectMapper.valueToTree(record.value()).path("orderNumber").asText(null); } catch (IllegalArgumentException ignored) { }
            // Store source coordinates only: no tokens, provider payloads or exception messages.
            // JdbcOutbox's JDBC connection shares this SQL transaction via Spring.
            recordDeadLetter(record,orderNumber);
            if (orderNumber != null) orderRepository.findByOrderNumberForUpdate(orderNumber).ifPresent(o -> investigate(o,"DEAD_LETTER"));
        });
    }

    private void recordDeadLetter(ConsumerRecord<String,Object> record, String orderNumber) {
        deadLetters.record(record.topic(),record.partition(),record.offset(),orderNumber);
    }
    @org.springframework.beans.factory.annotation.Autowired
    private WorkflowDeadLetters deadLetters;

}
