-- V24: Add employee data source tracking column
-- Prevents seed/import processes from silently overwriting manually entered employee data
-- Uses information_schema probe for MySQL 5.5+ compatibility

set @sql := if(
  exists(select 1 from information_schema.columns
    where table_schema = database() and table_name = 'employee' and column_name = 'data_source'),
  'select 1',
  'alter table employee add column data_source varchar(40) null after remark'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

-- Backfill: existing employees without data_source are treated as MANUAL_ENTRY (safe default)
set @sql := if(
  exists(select 1 from information_schema.columns
    where table_schema = database() and table_name = 'employee' and column_name = 'data_source'),
  'update employee set data_source = ''MANUAL_ENTRY'' where data_source is null',
  'select 1'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;
