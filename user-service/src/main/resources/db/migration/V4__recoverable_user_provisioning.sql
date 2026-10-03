CREATE TABLE user_provisioning_intent (
 intent_id VARCHAR(36) PRIMARY KEY,
 idempotency_key VARCHAR(64) NOT NULL UNIQUE,
 username_key VARCHAR(255) NOT NULL UNIQUE,
 email_key VARCHAR(255) NOT NULL UNIQUE,
 request_hash VARCHAR(128) NOT NULL,
 keycloak_id VARCHAR(255),
 state VARCHAR(32) NOT NULL,
 attempts INT NOT NULL DEFAULT 0,
 last_error_code VARCHAR(64),
 created_at TIMESTAMP(6) NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL
);
CREATE INDEX idx_provisioning_reconciliation ON user_provisioning_intent(state, updated_at);
