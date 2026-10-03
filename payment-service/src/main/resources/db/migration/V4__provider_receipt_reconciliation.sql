ALTER TABLE payment_transaction ADD COLUMN provider_success_received BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE payment_transaction ADD COLUMN order_decision_reason VARCHAR(255) NULL;
