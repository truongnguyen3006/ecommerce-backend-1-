-- Additive baseline; existing mysql-init tables and seed rows are preserved.
CREATE TABLE IF NOT EXISTS product (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, name VARCHAR(255) NOT NULL, description VARCHAR(255), category VARCHAR(255),
 base_price DECIMAL(38,2), image_url VARCHAR(255), created_at DATETIME(6), updated_at DATETIME(6)
);
CREATE TABLE IF NOT EXISTS product_variant (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, sku_code VARCHAR(255) NOT NULL UNIQUE, color VARCHAR(255), size VARCHAR(255),
 price DECIMAL(38,2), image_url VARCHAR(255), is_active BIT(1), product_id BIGINT,
 CONSTRAINT fk_variant_product FOREIGN KEY (product_id) REFERENCES product(id)
);
CREATE TABLE IF NOT EXISTS product_images (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, image_url VARCHAR(255), variant_id BIGINT,
 CONSTRAINT fk_image_variant FOREIGN KEY (variant_id) REFERENCES product_variant(id)
);
