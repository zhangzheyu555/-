-- V90 is recorded as applied in some imported real databases, but its four
-- delivery-platform expense columns are absent. MySQL 8 does not support
-- ADD COLUMN IF NOT EXISTS, so keep V90 immutable and repair by metadata checks.
set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'profit_entry' and column_name = 'meituan'), 'select 1', 'alter table profit_entry add column meituan decimal(14,2) not null default 0 after commission');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'profit_entry' and column_name = 'eleme'), 'select 1', 'alter table profit_entry add column eleme decimal(14,2) not null default 0 after meituan');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'profit_entry' and column_name = 'douyin'), 'select 1', 'alter table profit_entry add column douyin decimal(14,2) not null default 0 after eleme');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'profit_entry' and column_name = 'amap'), 'select 1', 'alter table profit_entry add column amap decimal(14,2) not null default 0 after douyin');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;
