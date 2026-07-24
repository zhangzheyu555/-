-- Add the workbook pricing unit and its conversion factor to each daily-loss item.

set @sql := if(
  exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'loss_item_config' and column_name = 'pricing_unit'),
  'select 1',
  'alter table loss_item_config add column pricing_unit varchar(40) null after unit'
);
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(
  exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'loss_item_config' and column_name = 'quantity_per_pricing_unit'),
  'select 1',
  'alter table loss_item_config add column quantity_per_pricing_unit decimal(18,4) null after pricing_unit'
);
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

update loss_item_config
set pricing_unit = case when unit = '克' then '斤' else unit end,
    quantity_per_pricing_unit = case when unit = '克' then 500.0000 else 1.0000 end
where pricing_unit is null
   or quantity_per_pricing_unit is null
   or quantity_per_pricing_unit <= 0;

alter table loss_item_config
  modify column pricing_unit varchar(40) not null,
  modify column quantity_per_pricing_unit decimal(18,4) not null;

set @sql := if(
  exists(select 1 from information_schema.table_constraints where table_schema = database() and table_name = 'loss_item_config' and constraint_name = 'chk_loss_item_config_pricing_quantity'),
  'select 1',
  'alter table loss_item_config add constraint chk_loss_item_config_pricing_quantity check (quantity_per_pricing_unit > 0)'
);
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
