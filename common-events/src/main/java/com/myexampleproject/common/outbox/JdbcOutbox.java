package com.myexampleproject.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

public class JdbcOutbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public JdbcOutbox(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc; this.mapper = mapper; this.clock = clock;
    }

    public String append(String topic, String key, Object event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Outbox requires the business SQL transaction");
        }
        String id = UUID.randomUUID().toString();
        try {
            String type = event == null ? "TOMBSTONE" : event.getClass().getName();
            String payload = event == null ? null : mapper.writeValueAsString(event);
            Timestamp now = Timestamp.from(clock.instant());
            jdbc.update("INSERT INTO outbox_event (event_id,aggregate_key,topic,event_type,payload,created_at,publication_state,attempts,next_attempt_at) VALUES (?,?,?,?,?,?,'PENDING',0,?)",
                    id, key, topic, type, payload, now, now);
            return id;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not persist required event intent", ex);
        }
    }
}
