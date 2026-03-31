package com.myexampleproject.notificationservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myexampleproject.common.event.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {
    // Inject template để gửi WebSocket
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "order-placed-topic",
            groupId = "notification-group",
            containerFactory = "orderPlacedKafkaListenerContainerFactory"
    )
    public void handleOrderPlaced(@Payload OrderPlacedEvent event) {
        log.info("Order placed: {}", event.getOrderNumber());

        // Gửi thông báo realtime
        // Topic: /topic/order/{orderNumber}
        messagingTemplate.convertAndSend("/topic/order/" + event.getOrderNumber(),
                Map.of("status", "PENDING", "message", "Đơn hàng đã được tiếp nhận!"));
    }

    @KafkaListener(
            topics = "payment-processed-topic",
            groupId = "notification-group",
            containerFactory = "paymentProcessedKafkaListenerContainerFactory"
    )
    public void handlePaymentSuccess(@Payload PaymentProcessedEvent event) {
        log.info("Payment success for order {} (paymentId={})",
                event.getOrderNumber(), event.getPaymentId());
        messagingTemplate.convertAndSend("/topic/order/" + event.getOrderNumber(),
                Map.of("status", "COMPLETED", "message", "Thanh toán thành công! Đơn hàng hoàn tất."));
    }

    // SỬA HÀM NÀY: Nhận ConsumerRecord thay vì @Payload Object
    @KafkaListener(topics = "order-failed-topic", groupId = "notification-group")
    public void handleOrderFailed(ConsumerRecord<String, String> record) { // Nhận String thô
        try {
            String json = cleanJson(record.value()); // Làm sạch chuỗi JSON
            OrderFailedEvent event = objectMapper.readValue(json, OrderFailedEvent.class);

            log.warn("Notification: Inventory Failed for Order {}", event.getOrderNumber());

            messagingTemplate.convertAndSend("/topic/order/" + event.getOrderNumber(),
                    Map.of("status", "FAILED", "message", "Hết hàng: " + event.getReason()));
        } catch (Exception e) {
            log.error("Lỗi parse OrderFailedEvent: {}", e.getMessage());
        }
    }

    // SỬA HÀM NÀY: Nhận ConsumerRecord thay vì @Payload Object
    @KafkaListener(topics = "payment-failed-topic", groupId = "notification-group")
    public void handlePaymentFailed(ConsumerRecord<String, String> record) { // Nhận String thô
        try {
            String json = cleanJson(record.value()); // Làm sạch chuỗi JSON
            PaymentFailedEvent event = objectMapper.readValue(json, PaymentFailedEvent.class);

            log.warn("Notification: Payment Failed for Order {}", event.getOrderNumber());

            messagingTemplate.convertAndSend("/topic/order/" + event.getOrderNumber(),
                    Map.of("status", "PAYMENT_FAILED", "message", "Thanh toán lỗi: " + event.getReason()));
        } catch (Exception e) {
            log.error("Lỗi parse PaymentFailedEvent: {}", e.getMessage());
        }
    }

    // --- HÀM PHỤ TRỢ: Lọc bỏ "Magic Bytes" của Kafka Schema Registry ---
    private String cleanJson(String raw) {
        if (raw == null) return "";
        // Tìm vị trí dấu mở ngoặc nhọn đầu tiên '{'
        int jsonStart = raw.indexOf("{");
        if (jsonStart != -1) {
            // Cắt bỏ phần rác phía trước
            return raw.substring(jsonStart);
        }
        return raw;
    }
}
