package com.myexampleproject.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Durable at-least-once publication. Kafka acknowledgement is never called a DB/Kafka atomic commit. */
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final KafkaTemplate<String, Object> kafka;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Counter failures;
    private final Counter published;

    public OutboxPublisher(JdbcTemplate jdbc, ObjectMapper mapper, KafkaTemplate<String,Object> kafka,
                           PlatformTransactionManager manager, MeterRegistry metrics, Clock clock) {
        this.jdbc = jdbc; this.mapper = mapper; this.kafka = kafka; this.clock = clock;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        failures = metrics.counter("outbox.publish.failures");
        published = metrics.counter("outbox.published");
        Gauge.builder("outbox.pending", this, p -> p.pendingCount()).register(metrics);
        Gauge.builder("outbox.oldest.pending.seconds", this, p -> p.oldestPendingAge()).register(metrics);
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-delay-ms:1000}")
    public void publishBatch() {
        try {
            for (int i = 0; i < 20 && publishOne(); i++) { /* bounded work, then yield */ }
        } catch (Exception ex) {
            failures.increment();
            log.error("Outbox polling/commit failed; committed intents will be retried ({})", ex.getClass().getSimpleName());
        }
    }

    public boolean publishOne() {
        return Boolean.TRUE.equals(transactions.execute(tx -> {
            // Prevent later mutations of the same topic/key overtaking a failed earlier one.
            // Lock is held through the bounded send; SKIP LOCKED permits safe competing publishers.
            List<Message> rows = jdbc.query("""
                SELECT o.id,o.event_id,o.aggregate_key,o.topic,o.event_type,o.payload,o.attempts
                FROM outbox_event o
                WHERE o.publication_state='PENDING' AND o.next_attempt_at<=?
                  AND NOT EXISTS (SELECT 1 FROM outbox_event older WHERE older.topic=o.topic
                    AND older.aggregate_key=o.aggregate_key AND older.id<o.id AND older.publication_state='PENDING')
                ORDER BY o.id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, n) -> new Message(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),
                    rs.getString(5),rs.getString(6),rs.getInt(7)), Timestamp.from(clock.instant()));
            if (rows.isEmpty()) return false;
            Message row = rows.getFirst();
            try {
                Object event = null;
                if (!"TOMBSTONE".equals(row.type())) {
                    if (!row.type().startsWith("com.myexampleproject.common.event."))
                        throw new IllegalArgumentException("Unsupported outbox payload type");
                    event = mapper.readValue(row.payload(), Class.forName(row.type()));
                }
                ProducerRecord<String,Object> record = new ProducerRecord<>(row.topic(), row.key(), event);
                record.headers().add("event-id", row.eventId().getBytes(StandardCharsets.UTF_8));
                kafka.send(record).get(10, TimeUnit.SECONDS);
            } catch (Exception ex) {
                if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                int attempts = row.attempts() == Integer.MAX_VALUE ? Integer.MAX_VALUE : row.attempts() + 1;
                long delaySeconds = Math.min(60, 1L << Math.min(6, attempts));
                jdbc.update("UPDATE outbox_event SET attempts=?,next_attempt_at=?,last_error=? WHERE id=?",
                        attempts, Timestamp.from(clock.instant().plusSeconds(delaySeconds)), ex.getClass().getSimpleName(), row.id());
                failures.increment();
                log.warn("Outbox retry event={} topic={} attempt={}", row.eventId(), row.topic(), attempts);
                return true;
            }
            // A crash/SQL failure here intentionally leaves a retry with the SAME event ID.
            jdbc.update("UPDATE outbox_event SET publication_state='PUBLISHED',published_at=?,last_error=NULL WHERE id=?",
                    Timestamp.from(clock.instant()), row.id());
            published.increment();
            log.debug("Outbox published event={} topic={}", row.eventId(), row.topic());
            return true;
        }));
    }

    private long pendingCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE publication_state='PENDING'", Long.class);
        return count == null ? 0 : count;
    }

    private double oldestPendingAge() {
        Timestamp oldest = jdbc.queryForObject("SELECT MIN(created_at) FROM outbox_event WHERE publication_state='PENDING'", Timestamp.class);
        return oldest == null ? 0 : Math.max(0, (clock.millis() - oldest.getTime()) / 1000.0);
    }

    private record Message(long id, String eventId, String key, String topic, String type, String payload, int attempts) {}
}
