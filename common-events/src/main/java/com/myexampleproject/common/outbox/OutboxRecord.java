package com.myexampleproject.common.outbox;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** JPA mapping lets local ddl-auto=update create the same additive table as Flyway. */
@Entity
@Table(name = "outbox_event", indexes = {
        @Index(name = "idx_outbox_pending", columnList = "publication_state,next_attempt_at,id"),
        @Index(name = "idx_outbox_ordering", columnList = "topic,aggregate_key,id")})
public class OutboxRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "event_id", nullable = false, unique = true, length = 36) private String eventId;
    @Column(name = "aggregate_key", nullable = false, length = 128) private String aggregateKey;
    @Column(nullable = false, length = 128) private String topic;
    @Column(name = "event_type", nullable = false, length = 255) private String eventType;
    @Column(columnDefinition = "LONGTEXT") private String payload;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "publication_state", nullable = false, length = 16) private String publicationState;
    @Column(nullable = false) private int attempts;
    @Column(name = "next_attempt_at", nullable = false) private LocalDateTime nextAttemptAt;
    @Column(name = "published_at") private LocalDateTime publishedAt;
    @Column(name = "last_error", length = 255) private String lastError;
}
