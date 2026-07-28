alter table store_inventory
  alter column quantity decimal(18,4) not null default 0;

alter table store_inventory_movement
  alter column quantity_delta decimal(18,4) not null;
