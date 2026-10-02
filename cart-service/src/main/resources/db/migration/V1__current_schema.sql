-- Additive baseline; existing mysql-init tables and seed rows are preserved.
CREATE TABLE IF NOT EXISTS carts (user_id VARCHAR(255) NOT NULL PRIMARY KEY, version BIGINT);
CREATE TABLE IF NOT EXISTS cart_items (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, sku_code VARCHAR(255), quantity INT NOT NULL,
 product_name VARCHAR(255), image_url VARCHAR(255), price DECIMAL(38,2), cart_user_id VARCHAR(255),
 CONSTRAINT fk_cart_item_cart FOREIGN KEY (cart_user_id) REFERENCES carts(user_id)
);
