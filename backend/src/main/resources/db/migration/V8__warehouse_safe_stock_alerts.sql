-- 最低安全库存：当当前库存低于这个数量时，提醒仓库管理员补货。
-- 兼容旧数据：先用 daily_usage_estimate * min_stock_days 回填一次默认最低安全库存，后续由仓库管理员直接维护数量。

set @sql := if(
  exists(
    select 1 from information_schema.columns
    where table_schema = database()
      and table_name = 'warehouse_item'
      and column_name = 'min_stock_quantity'
  ),
  'select 1',
  'alter table warehouse_item add column min_stock_quantity decimal(14,2) not null default 0 after max_stock_days'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql := if(
  exists(
    select 1 from information_schema.columns
    where table_schema = database()
      and table_name = 'warehouse_item'
      and column_name = 'alert_enabled'
  ),
  'select 1',
  'alter table warehouse_item add column alert_enabled tinyint(1) not null default 1 after min_stock_quantity'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql := if(
  exists(
    select 1 from information_schema.columns
    where table_schema = database()
      and table_name = 'warehouse_item'
      and column_name = 'expiry_alert_days'
  ),
  'select 1',
  'alter table warehouse_item add column expiry_alert_days int null default 3 after alert_enabled'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

update warehouse_item
set min_stock_quantity = greatest(0, coalesce(daily_usage_estimate, 0) * coalesce(min_stock_days, 0))
where min_stock_quantity = 0;
