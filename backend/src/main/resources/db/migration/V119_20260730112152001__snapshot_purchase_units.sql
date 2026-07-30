-- Keep the material and unit conversion used when a purchase order is created.
-- Purchase quantities/prices use purchase_unit_snapshot; inventory uses stock_unit_snapshot.

alter table warehouse_purchase_order_line
  add column item_code_snapshot varchar(80) null after item_id,
  add column item_name_snapshot varchar(160) null after item_code_snapshot,
  add column spec_snapshot varchar(255) null after item_name_snapshot,
  add column purchase_unit_snapshot varchar(40) null after spec_snapshot,
  add column stock_unit_snapshot varchar(40) null after purchase_unit_snapshot,
  add column unit_conversion_snapshot varchar(120) null after stock_unit_snapshot,
  add column conversion_factor decimal(18,8) null after unit_conversion_snapshot;
