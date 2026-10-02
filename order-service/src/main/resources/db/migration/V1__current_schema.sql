-- Additive baseline; existing mysql-init tables and seed rows are preserved.
CREATE TABLE IF NOT EXISTS t_orders (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, order_number VARCHAR(255) NOT NULL UNIQUE, user_id VARCHAR(255) NOT NULL,
 order_date DATETIME(6), status VARCHAR(255), total_price DECIMAL(38,2), payment_method VARCHAR(32),
 shipping_address_label VARCHAR(128), shipping_recipient_name VARCHAR(128), shipping_recipient_phone VARCHAR(32),
 shipping_address_line VARCHAR(512), cancel_reason VARCHAR(255), cancelled_at DATETIME(6)
);
CREATE TABLE IF NOT EXISTS t_orders_line_items (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, sku_code VARCHAR(255), quantity INT, price DECIMAL(38,2),
 product_name VARCHAR(255), color VARCHAR(255), size VARCHAR(255), order_id BIGINT,
 CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES t_orders(id)
);
