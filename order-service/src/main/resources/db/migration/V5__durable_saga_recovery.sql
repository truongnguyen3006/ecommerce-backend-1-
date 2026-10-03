ALTER TABLE t_orders_line_items ADD COLUMN inventory_outcome BOOLEAN NULL;
ALTER TABLE t_orders ADD COLUMN recovery_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE t_orders ADD COLUMN recovery_next_at DATETIME(6) NULL;
ALTER TABLE t_orders ADD COLUMN workflow_investigation_required BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE t_orders ADD COLUMN workflow_investigation_reason VARCHAR(128) NULL;
CREATE INDEX idx_order_recovery ON t_orders(status, workflow_investigation_required, recovery_next_at, order_date);
CREATE TABLE workflow_dead_letter (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 source_topic VARCHAR(128) NOT NULL,
 source_partition INT NOT NULL,
 source_offset BIGINT NOT NULL,
 order_number VARCHAR(255) NULL,
 recorded_at DATETIME(6) NOT NULL,
 resolution VARCHAR(32) NOT NULL DEFAULT 'INVESTIGATION_REQUIRED',
 CONSTRAINT uq_dead_letter UNIQUE(source_topic, source_partition, source_offset)
);
