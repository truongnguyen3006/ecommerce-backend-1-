package com.myexampleproject.inventoryservice.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.annotation.KafkaStreamsDefaultConfiguration;
import org.springframework.kafka.config.KafkaStreamsConfiguration;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableKafkaStreams
public class KafkaStreamsConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${server.port}")
    private String serverPort;

    @Value("${spring.kafka.streams.properties.application.id}")
    private String applicationId;

    @Value("${spring.kafka.streams.properties.state.dir}")
    private String stateDir;

    /*
     * XÓA BỎ BEAN 'inventoryKafkaStreams' Ở ĐÂY.
     * CHÚNG TA SẼ INJECT TRỰC TIẾP StreamsBuilderFactoryBean VÀO SERVICE.
     */
    // @Bean(name = "inventoryKafkaStreams")
    // public KafkaStreams kafkaStreams(StreamsBuilderFactoryBean factory) {
    //    return factory.getKafkaStreams(); // DÒNG NÀY TRẢ VỀ NULL KHI KHỞI ĐỘNG
    // }


    @Bean(name = KafkaStreamsDefaultConfiguration.DEFAULT_STREAMS_CONFIG_BEAN_NAME)
    public KafkaStreamsConfiguration kafkaStreamsConfiguration() {

        Map<String, Object> props = new HashMap<>();

        // REQUIRED
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        // REQUIRED: YOU MUST SET THIS
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, applicationId);

        // REQUIRED FOR INTERACTIVE QUERIES
        props.put(StreamsConfig.APPLICATION_SERVER_CONFIG, "localhost:" + serverPort);

        // STATE DIR FROM PROPERTIES
        props.put(StreamsConfig.STATE_DIR_CONFIG, stateDir);

        // GUARANTEE
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);

        // SERDES
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);

        // STREAMS TUNING
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 5000);
        props.put(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, 10_485_760);

        // CONSUMER CONFIG
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // PRODUCER SAFETY
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.RETRIES_CONFIG, 10);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);

        return new KafkaStreamsConfiguration(props);
    }
}