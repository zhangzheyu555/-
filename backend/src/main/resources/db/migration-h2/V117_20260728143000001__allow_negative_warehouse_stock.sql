alter table warehouse_stock_batch
  drop constraint chk_warehouse_batch_quantities;

alter table warehouse_stock_batch
  add constraint chk_warehouse_batch_quantities check (
    reserved_quantity >= 0
    and (
      (quantity >= 0 and reserved_quantity <= quantity)
      or (quantity < 0 and reserved_quantity = 0)
    )
  );

alter table warehouse_inventory
  drop constraint chk_warehouse_inventory_quantities;

alter table warehouse_inventory
  add constraint chk_warehouse_inventory_quantities check (
    reserved_quantity >= 0
    and in_transit_quantity >= 0
    and unit_cost >= 0
    and min_stock_quantity >= 0
    and (expiry_alert_days is null or expiry_alert_days >= 0)
  );
