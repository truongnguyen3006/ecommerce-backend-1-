ALTER TABLE t_orders ADD COLUMN payment_attempt_id VARCHAR(100) NULL;
ALTER TABLE t_orders ADD COLUMN payment_received_ref VARCHAR(100) NULL;
ALTER TABLE t_orders ADD COLUMN payment_reconciliation_required BOOLEAN NOT NULL DEFAULT FALSE;
