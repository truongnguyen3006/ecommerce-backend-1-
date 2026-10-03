ALTER TABLE payment_transaction ADD COLUMN expires_at DATETIME(6) NULL;
ALTER TABLE payment_transaction ADD COLUMN last_callback_at DATETIME(6) NULL;
ALTER TABLE payment_transaction ADD COLUMN recovery_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE payment_transaction ADD COLUMN recovery_next_at DATETIME(6) NULL;
-- Legacy URLs without proven validity fail closed. Their references remain intact for delayed IPNs.
UPDATE payment_transaction SET expires_at=COALESCE(created_at,CURRENT_TIMESTAMP) WHERE status='PENDING' AND payment_url IS NOT NULL;
CREATE INDEX idx_payment_recovery ON payment_transaction(status, expires_at, recovery_next_at);
