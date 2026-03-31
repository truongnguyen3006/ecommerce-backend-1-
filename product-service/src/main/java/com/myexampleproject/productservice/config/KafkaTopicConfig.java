package com.myexampleproject.productservice.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    private final int NUM_PARTITIONS = 10;
    private final short REPLICAS = 1;

    // Product Service là chủ sở hữu của 2 topic này:

    @Bean
    public NewTopic productCreatedTopic() {
        return TopicBuilder.name("product-created-topic")
                .partitions(NUM_PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }

    @Bean
    public NewTopic productCacheUpdateTopic() {
        return TopicBuilder.name("product-cache-update-topic")
                .partitions(NUM_PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }
}