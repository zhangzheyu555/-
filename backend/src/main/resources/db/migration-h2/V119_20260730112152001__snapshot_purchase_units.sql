-- Keep the material and unit conversion used when a purchase order is created.
-- Purchase quantities/prices use purchase_unit_snapshot; inventory uses stock_unit_snapshot.

alter table warehouse_purchase_order_line
  add column item_code_snapshot varchar(80) null;

alter table warehouse_purchase_order_line
  add column item_name_snapshot varchar(160) null;

alter table warehouse_purchase_order_line
  add column spec_snapshot varchar(255) null;

alter table warehouse_purchase_order_line
  add column purchase_unit_snapshot varchar(40) null;

alter table warehouse_purchase_order_line
  add column stock_unit_snapshot varchar(40) null;

alter table warehouse_purchase_order_line
  add column unit_conversion_snapshot varchar(120) null;

alter table warehouse_purchase_order_line
  add column conversion_factor decimal(18,8) null;
