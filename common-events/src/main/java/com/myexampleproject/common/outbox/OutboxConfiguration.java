package com.myexampleproject.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;

/** Explicitly imported by only the three SQL services that publish business events. */
@Configuration(proxyBeanMethods = false)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="app.outbox.enabled", havingValue="true", matchIfMissing=true)
@EnableScheduling
public class OutboxConfiguration {
    @Bean("outboxClock") Clock outboxClock() { return Clock.systemUTC(); }
    @Bean JdbcOutbox jdbcOutbox(JdbcTemplate jdbc, ObjectMapper mapper, @Qualifier("outboxClock") Clock clock) {
        return new JdbcOutbox(jdbc, mapper, clock);
    }
    @Bean OutboxPublisher outboxPublisher(JdbcTemplate jdbc, ObjectMapper mapper, KafkaTemplate<String,Object> kafka,
                                         PlatformTransactionManager manager, MeterRegistry metrics, @Qualifier("outboxClock") Clock clock) {
        return new OutboxPublisher(jdbc, mapper, kafka, manager, metrics, clock);
    }
}
