-- Additive baseline; existing mysql-init tables and seed rows are preserved.
CREATE TABLE IF NOT EXISTS payment_transaction (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, order_number VARCHAR(64) NOT NULL UNIQUE, provider VARCHAR(32) NOT NULL,
 status VARCHAR(32) NOT NULL, amount DECIMAL(18,2) NOT NULL, txn_ref VARCHAR(100) UNIQUE,
 payment_url VARCHAR(1024), gateway_transaction_no VARCHAR(128), gateway_response_code VARCHAR(255),
 gateway_message VARCHAR(255), created_at DATETIME(6), updated_at DATETIME(6)
);
