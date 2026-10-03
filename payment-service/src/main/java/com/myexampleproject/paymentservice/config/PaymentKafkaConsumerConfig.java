package com.myexampleproject.paymentservice.config;

import com.myexampleproject.common.event.OrderValidatedEvent; // <-- THÊM IMPORT NÀY
import io.confluent.kafka.serializers.json.KafkaJsonSchemaDeserializer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.util.HashMap;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.core.KafkaTemplate;
import org.apache.kafka.common.TopicPartition;
import org.springframework.util.backoff.FixedBackOff;
import java.util.Map;

@EnableKafka
@Configuration
public class PaymentKafkaConsumerConfig {
    private final KafkaTemplate<String,Object> template;
    public PaymentKafkaConsumerConfig(KafkaTemplate<String,Object> template) { this.template = template; }

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrap;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    @Value("${spring.kafka.properties.schema.registry.url}")
    private String schemaRegistry;

    @Bean
    public ConsumerFactory<String, Object> paymentConsumerFactory() {

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);

        // DESERIALIZER
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaJsonSchemaDeserializer.class);

        // Schema Registry
        props.put("schema.registry.url", schemaRegistry);
        props.put("auto.register.schemas", true);

        // SỬA LỖI TẠI ĐÂY:
        // Cần chỉ rõ class mục tiêu cho JSON Schema Deserializer
        props.put("json.value.type", OrderValidatedEvent.class);

        // Xóa dòng 'specific.avro.reader' vì đây là JSON, không phải Avro
        // props.put("specific.avro.reader", false);

        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");

        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> paymentKafkaListenerContainerFactory() {
        return listenerFactory(paymentConsumerFactory());
    }

    @Bean
    public ConsumerFactory<String, Object> onlinePaymentDecisionConsumerFactory() {
        Map<String,Object> props = new HashMap<>(paymentConsumerFactory().getConfigurationProperties());
        props.put("json.value.type", com.myexampleproject.common.event.OnlinePaymentDecisionEvent.class);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> onlinePaymentDecisionKafkaListenerContainerFactory() {
        return listenerFactory(onlinePaymentDecisionConsumerFactory());
    }

    private ConcurrentKafkaListenerContainerFactory<String, Object> listenerFactory(ConsumerFactory<String,Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(3);
        factory.setBatchListener(true);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template, (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1));
        recoverer.setFailIfSendResultIsError(true);
        DefaultErrorHandler errors = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
        errors.addNotRetryableExceptions(IllegalArgumentException.class);
        factory.setCommonErrorHandler(errors);
        return factory;
    }
}
