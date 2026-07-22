-- V56 added column comments with MODIFY COLUMN statements but omitted several
-- operational defaults. Warehouse create/upsert paths intentionally omit these
-- fields because a new order or stock row starts at zero / its initial status.
-- Do not restore tenant_id defaults: callers must always provide the tenant.

ALTER TABLE warehouse_purchase_order
  ALTER COLUMN status SET DEFAULT 'DRAFT',
  ALTER COLUMN total_amount SET DEFAULT 0;

ALTER TABLE warehouse_purchase_order_line
  ALTER COLUMN ordered_quantity SET DEFAULT 0,
  ALTER COLUMN received_quantity SET DEFAULT 0,
  ALTER COLUMN unit_cost SET DEFAULT 0,
  ALTER COLUMN amount SET DEFAULT 0;

ALTER TABLE warehouse_delivery_order
  ALTER COLUMN status SET DEFAULT 'SHIPPED';

ALTER TABLE warehouse_delivery_order_line
  ALTER COLUMN shipped_quantity SET DEFAULT 0,
  ALTER COLUMN received_quantity SET DEFAULT 0,
  ALTER COLUMN unit_price SET DEFAULT 0,
  ALTER COLUMN amount SET DEFAULT 0;

ALTER TABLE warehouse_inventory
  ALTER COLUMN on_hand_quantity SET DEFAULT 0,
  ALTER COLUMN reserved_quantity SET DEFAULT 0,
  ALTER COLUMN in_transit_quantity SET DEFAULT 0,
  ALTER COLUMN unit_cost SET DEFAULT 0,
  ALTER COLUMN min_stock_quantity SET DEFAULT 0,
  ALTER COLUMN alert_enabled SET DEFAULT 1,
  ALTER COLUMN expiry_alert_days SET DEFAULT 3;

ALTER TABLE warehouse_stock_batch
  ALTER COLUMN quantity SET DEFAULT 0,
  ALTER COLUMN reserved_quantity SET DEFAULT 0,
  ALTER COLUMN unit_cost SET DEFAULT 0;

ALTER TABLE warehouse_stock_movement
  ALTER COLUMN reserved_quantity_delta SET DEFAULT 0,
  ALTER COLUMN in_transit_quantity_delta SET DEFAULT 0;

ALTER TABLE warehouse_transfer_order
  ALTER COLUMN status SET DEFAULT 'DRAFT',
  ALTER COLUMN total_amount SET DEFAULT 0;

ALTER TABLE warehouse_transfer_line
  ALTER COLUMN approved_quantity SET DEFAULT 0,
  ALTER COLUMN reserved_quantity SET DEFAULT 0,
  ALTER COLUMN shipped_quantity SET DEFAULT 0,
  ALTER COLUMN received_quantity SET DEFAULT 0,
  ALTER COLUMN in_transit_quantity SET DEFAULT 0,
  ALTER COLUMN unit_cost SET DEFAULT 0,
  ALTER COLUMN amount SET DEFAULT 0;

ALTER TABLE warehouse_return_order
  ALTER COLUMN status SET DEFAULT 'CHECKED',
  ALTER COLUMN total_amount SET DEFAULT 0;

ALTER TABLE warehouse_return_order_line
  ALTER COLUMN quantity SET DEFAULT 0,
  ALTER COLUMN unit_price SET DEFAULT 0,
  ALTER COLUMN return_price SET DEFAULT 0,
  ALTER COLUMN amount SET DEFAULT 0;

ALTER TABLE warehouse_facility
  ALTER COLUMN external_purchase_allowed SET DEFAULT 0,
  ALTER COLUMN store_supply_allowed SET DEFAULT 1,
  ALTER COLUMN enabled SET DEFAULT 1;

ALTER TABLE warehouse_alert
  ALTER COLUMN severity SET DEFAULT 'WARN',
  ALTER COLUMN status SET DEFAULT 'OPEN';

ALTER TABLE warehouse_supplier
  ALTER COLUMN active SET DEFAULT 1;

ALTER TABLE store_receipt_line
  ALTER COLUMN received_quantity SET DEFAULT 0;

ALTER TABLE store_inventory
  ALTER COLUMN quantity SET DEFAULT 0;
