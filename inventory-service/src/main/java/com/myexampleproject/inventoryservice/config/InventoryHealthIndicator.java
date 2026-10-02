package com.myexampleproject.inventoryservice.config;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.streams.KafkaStreams;
import org.springframework.boot.actuate.health.*;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.stereotype.Component;
@Component("inventory")
@RequiredArgsConstructor
public class InventoryHealthIndicator implements HealthIndicator {
    private final StreamsBuilderFactoryBean factory;
    public Health health() {
        KafkaStreams streams = factory.getKafkaStreams();
        return streams != null && streams.state() == KafkaStreams.State.RUNNING ? Health.up().build() : Health.down().build();
    }
}
