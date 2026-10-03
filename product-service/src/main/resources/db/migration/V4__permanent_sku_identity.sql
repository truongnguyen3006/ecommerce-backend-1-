CREATE TABLE sku_identity (
 sku_code VARCHAR(255) NOT NULL PRIMARY KEY,
 product_id BIGINT NULL,
 retired BOOLEAN NOT NULL DEFAULT FALSE,
 reserved_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 retired_at DATETIME(6) NULL
);
INSERT INTO sku_identity(sku_code,product_id) SELECT sku_code,product_id FROM product_variant;
-- Retained initialization intents reserve historical deleted SKUs too.
INSERT INTO sku_identity(sku_code,retired)
 SELECT DISTINCT o.aggregate_key,TRUE FROM outbox_event o
 LEFT JOIN sku_identity s ON s.sku_code=o.aggregate_key
 WHERE o.topic='product-created-topic' AND s.sku_code IS NULL;
