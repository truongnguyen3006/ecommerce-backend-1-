ALTER TABLE product ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE product ADD CONSTRAINT chk_product_revision CHECK (revision >= 0);
