package com.myexampleproject.inventoryservice.config;

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.streams.serdes.json.KafkaJsonSchemaSerde;
import org.apache.kafka.common.serialization.Serde;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class SerdeConfig {

    @Value("${spring.kafka.properties.schema.registry.url}")
    private String schemaRegistryUrl;

    // Generic helper - dùng ở InventoryTopology
    public <T> Serde<T> jsonSchemaSerde(Class<T> clazz) {
        final Serde<T> serde = new KafkaJsonSchemaSerde<>(clazz);
        serde.configure(
                Map.of(
                        AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl,
                        "json.value.type", clazz.getName()
                ),
                false
        );
        return serde;
    }
}
