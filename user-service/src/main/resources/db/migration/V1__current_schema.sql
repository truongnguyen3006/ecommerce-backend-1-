-- Additive baseline; existing mysql-init tables and seed rows are preserved.
CREATE TABLE IF NOT EXISTS t_user (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, keycloak_id VARCHAR(255) NOT NULL UNIQUE, full_name VARCHAR(255),
 email VARCHAR(255), phone_number VARCHAR(255), address VARCHAR(255), status BIT(1) NOT NULL,
 created_date DATETIME(6), updated_date DATETIME(6)
);
CREATE TABLE IF NOT EXISTS t_user_address (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, user_keycloak_id VARCHAR(255) NOT NULL, label VARCHAR(64),
 recipient_name VARCHAR(128) NOT NULL, recipient_phone VARCHAR(32) NOT NULL, address_line VARCHAR(512) NOT NULL,
 is_default BIT(1) NOT NULL, created_date DATETIME(6), updated_date DATETIME(6)
);
