CREATE TABLE sku_identity (
 sku_code VARCHAR(255) NOT NULL PRIMARY KEY,
 product_id BIGINT NULL,
 retired BOOLEAN NOT NULL DEFAULT FALSE,
 reserved_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
 retired_at DATETIME(6) NULL
);
INSERT INTO sku_identity(sku_code,product_id) SELECT sku_code,product_id FROM product_variant;
-- Retained initialization intents reserve historical deleted SKUs too.
INSERT INTO sku_identity(sku_code,retired)
 SELECT DISTINCT o.aggregate_key,TRUE FROM outbox_event o
 WHERE o.topic='product-created-topic' AND NOT EXISTS (SELECT 1 FROM sku_identity s WHERE s.sku_code=o.aggregate_key);
