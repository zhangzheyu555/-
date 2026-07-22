-- V56 preserved column comments but unintentionally removed defaults from several
-- pre-existing numeric version columns.  These defaults are required by legacy
-- insert/upsert paths and initialise optimistic-lock state consistently.

ALTER TABLE inspection_rectification
  MODIFY COLUMN version bigint NOT NULL DEFAULT 0 COMMENT '版本号';

ALTER TABLE salary_policy
  MODIFY COLUMN version int NOT NULL DEFAULT 1 COMMENT '版本号';

ALTER TABLE salary_record
  MODIFY COLUMN version int NOT NULL DEFAULT 1 COMMENT '版本号';

ALTER TABLE warehouse_inventory
  MODIFY COLUMN version bigint NOT NULL DEFAULT 0 COMMENT '版本号';

ALTER TABLE warehouse_purchase_order
  MODIFY COLUMN version bigint NOT NULL DEFAULT 0 COMMENT '版本号';

ALTER TABLE warehouse_stock_batch
  MODIFY COLUMN version bigint NOT NULL DEFAULT 0 COMMENT '版本号';

ALTER TABLE warehouse_transfer_line
  MODIFY COLUMN version bigint NOT NULL DEFAULT 0 COMMENT '版本号';

ALTER TABLE warehouse_transfer_order
  MODIFY COLUMN version bigint NOT NULL DEFAULT 0 COMMENT '版本号';
