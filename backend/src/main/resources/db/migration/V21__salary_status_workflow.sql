-- V21 was not applied to the current database before this compatibility fix.
-- Use dynamic DDL because MySQL 5.5 does not support ADD/CREATE IF NOT EXISTS.

set @sql := if(
  exists(select 1 from information_schema.columns
    where table_schema = database() and table_name = 'salary_record' and column_name = 'review_note'),
  'select 1',
  'alter table salary_record add column review_note varchar(500) null after reviewed_at'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql := if(
  exists(select 1 from information_schema.columns
    where table_schema = database() and table_name = 'salary_record' and column_name = 'paid_at'),
  'select 1',
  'alter table salary_record add column paid_at timestamp null after review_note'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql := if(
  exists(select 1 from information_schema.columns
    where table_schema = database() and table_name = 'salary_record' and column_name = 'version'),
  'select 1',
  'alter table salary_record add column version int not null default 1 after paid_at'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql := if(
  exists(select 1 from information_schema.statistics
    where table_schema = database() and table_name = 'salary_record' and index_name = 'idx_salary_version'),
  'select 1',
  'create index idx_salary_version on salary_record(tenant_id, employee_id, month, version)'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;
