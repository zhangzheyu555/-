-- 最低安全库存：当当前库存低于这个数量时，提醒仓库管理员补货。
-- 兼容旧数据：先用 daily_usage_estimate * min_stock_days 回填一次默认最低安全库存，后续由仓库管理员直接维护数量。

alter table warehouse_item add column if not exists min_stock_quantity decimal(14,2) not null default 0;
alter table warehouse_item add column if not exists alert_enabled tinyint(1) not null default 1;
alter table warehouse_item add column if not exists expiry_alert_days int null default 3;

update warehouse_item
set min_stock_quantity = greatest(0, coalesce(daily_usage_estimate, 0) * coalesce(min_stock_days, 0))
where min_stock_quantity = 0;
