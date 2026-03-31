package com.myexampleproject.orderservice.config;

import com.myexampleproject.common.event.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import io.confluent.kafka.streams.serdes.json.KafkaJsonSchemaSerde;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;

import java.util.Map;

@Configuration
@EnableKafkaStreams
@Slf4j
public class OrderStatusJoiner {

    @Value("${spring.kafka.properties.schema.registry.url}")
    private String schemaRegistryUrl;

    private <T> Serde<T> jsonSerde(Class<T> clazz) {
        Serde<T> serde = new KafkaJsonSchemaSerde<>(clazz);
        serde.configure(Map.of(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl), false);
        return serde;
    }

    @Bean
    public KStream<String, PaymentProcessedEvent> joinPaymentAndOrderStatus(StreamsBuilder builder) throws InterruptedException {
        Serde<OrderStatusEvent> statusSerde = jsonSerde(OrderStatusEvent.class);
        Serde<PaymentProcessedEvent> paymentSerde = jsonSerde(PaymentProcessedEvent.class);

        // 1️⃣ Tạo KTable từ order-status-topic (key = orderNumber)
        KTable<String, OrderStatusEvent> orderStatusTable = builder.table(
                "order-status-topic",
                Consumed.with(Serdes.String(), statusSerde)
        );

        // 2️⃣ Tạo KStream từ payment-processed-topic
        KStream<String, PaymentProcessedEvent> paymentStream = builder.stream(
                "payment-processed-topic",
                Consumed.with(Serdes.String(), paymentSerde)
        );

        // 3️⃣ JOIN giữa payment và status
        KStream<String, PaymentProcessedEvent> validPayments = paymentStream.join(
                orderStatusTable,
                (payment, status) -> {
                    if (status == null) {
                        log.warn("BỎ QUA PaymentProcessedEvent: Order {} chưa có trong order-status-topic", payment.getOrderNumber());
                        return null;
                    }
                    String currentStatus = status.getStatus();
                    if (!"PENDING".equals(currentStatus) && !"VALIDATED".equals(currentStatus)) {
                        log.warn("BỎ QUA PaymentProcessedEvent: Order {} không ở trạng thái PENDING (status={})",
                                payment.getOrderNumber(), status.getStatus());
                        return null;
                    }
                    log.info("VALID PaymentProcessedEvent cho Order {}", payment.getOrderNumber());
                    return payment;
                },
                Joined.with(Serdes.String(), paymentSerde, statusSerde)
        ).filter((key, value) -> value != null); // Bỏ các record bị loại

        // 4️⃣ Gửi các event hợp lệ sang topic mới
        validPayments.to("payment-validated-topic", Produced.with(Serdes.String(), paymentSerde));

        return validPayments;
    }
}