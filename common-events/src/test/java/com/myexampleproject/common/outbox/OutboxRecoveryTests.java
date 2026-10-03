package com.myexampleproject.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myexampleproject.common.event.ProductCreatedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxRecoveryTests {
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    TransactionTemplate tx;
    JdbcOutbox outbox;
    KafkaTemplate<String,Object> kafka = mock(KafkaTemplate.class);
    MutableClock clock = new MutableClock();
    SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    List<ProducerRecord<String,Object>> deliveries = new ArrayList<>();
    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc = new JdbcTemplate(ds);manager = new DataSourceTransactionManager(ds);tx = new TransactionTemplate(manager);
        jdbc.execute("CREATE TABLE outbox_event(id BIGINT AUTO_INCREMENT PRIMARY KEY,event_id VARCHAR(36) UNIQUE,aggregate_key VARCHAR(128),topic VARCHAR(128),event_type VARCHAR(255),payload LONGTEXT,created_at TIMESTAMP,publication_state VARCHAR(16),attempts INT,next_attempt_at TIMESTAMP,published_at TIMESTAMP,last_error VARCHAR(255))");
        jdbc.execute("CREATE TABLE business_row(id INT PRIMARY KEY)");
        outbox = new JdbcOutbox(jdbc,new ObjectMapper(),clock);
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(a -> {deliveries.add(a.getArgument(0));return CompletableFuture.completedFuture(null);});
    }
    OutboxPublisher publisher() {return new OutboxPublisher(jdbc,new ObjectMapper(),kafka,manager,metrics,clock);}
    void intent(String key) {outbox.append("product-created-topic",key,new ProductCreatedEvent(key,3));}
    @Test void rollbackCommitsNeitherBusinessStateNorIntentAndNeverSends() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {jdbc.update("INSERT INTO business_row VALUES(1)");intent("SKU");throw new IllegalStateException("abort");})).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_row",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event",Integer.class)).isZero();
        assertThat(publisher().publishOne()).isFalse();verifyNoInteractions(kafka);
    }
    @Test void aBusinessCommitRemainsRecoverableAcrossKafkaFailureAndPublisherRestart() {
        tx.executeWithoutResult(s -> {jdbc.update("INSERT INTO business_row VALUES(1)");intent("SKU");});
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        OutboxPublisher first = publisher();assertThat(first.publishOne()).isTrue();
        assertThat(jdbc.queryForObject("SELECT publication_state FROM outbox_event",String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_event",Integer.class)).isEqualTo(1);
        assertThat(first.publishOne()).isFalse(); // backoff: no busy retry
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(a -> {deliveries.add(a.getArgument(0));return CompletableFuture.completedFuture(null);});
        clock.now=clock.now.plusSeconds(3);assertThat(publisher().publishOne()).isTrue();
        assertThat(jdbc.queryForObject("SELECT publication_state FROM outbox_event",String.class)).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_row",Integer.class)).isEqualTo(1);
    }
    @Test void crashAfterAcknowledgementKeepsSameEventIdOnRetry() {
        tx.executeWithoutResult(s -> intent("SKU"));
        jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT simulated_commit_failure CHECK(publication_state='PENDING')");
        assertThatThrownBy(() -> publisher().publishOne()).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT publication_state FROM outbox_event",String.class)).isEqualTo("PENDING");
        jdbc.execute("ALTER TABLE outbox_event DROP CONSTRAINT simulated_commit_failure");
        assertThat(publisher().publishOne()).isTrue();assertThat(deliveries).hasSize(2);
        assertThat(deliveries.get(0).headers().lastHeader("event-id").value()).isEqualTo(deliveries.get(1).headers().lastHeader("event-id").value());
    }
    @Test void failedEarlierEventBlocksOvertakingForSameKeyButNotOtherKeys() {
        tx.executeWithoutResult(s -> {intent("A");intent("A");intent("B");});
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(a -> {
            ProducerRecord<String,Object> record=a.getArgument(0);
            return record.key().equals("A") ? CompletableFuture.failedFuture(new IllegalStateException("fail")) : CompletableFuture.completedFuture(null);
        });
        OutboxPublisher publisher=publisher();assertThat(publisher.publishOne()).isTrue();assertThat(publisher.publishOne()).isTrue();assertThat(publisher.publishOne()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_key='A' AND publication_state='PENDING'",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT publication_state FROM outbox_event WHERE aggregate_key='B'",String.class)).isEqualTo("PUBLISHED");
    }
    @Test void appendingOutsideBusinessTransactionFailsClosed() {
        assertThatThrownBy(() -> intent("SKU")).isInstanceOf(IllegalStateException.class);verifyNoInteractions(kafka);
    }
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-03T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;} public Instant instant(){return now;}
    }
}
