CREATE TABLE outbox_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(36) NOT NULL UNIQUE,
    aggregate_key VARCHAR(128) NOT NULL,
    topic VARCHAR(128) NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    payload LONGTEXT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    publication_state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6) NULL,
    last_error VARCHAR(255) NULL
);
CREATE INDEX idx_outbox_pending ON outbox_event(publication_state, next_attempt_at, id);
CREATE INDEX idx_outbox_ordering ON outbox_event(topic, aggregate_key, id);
