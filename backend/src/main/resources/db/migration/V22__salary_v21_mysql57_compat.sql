-- V22: MySQL 5.5/5.7 compatibility for V21
-- V21 uses "ADD COLUMN IF NOT EXISTS" / "CREATE INDEX IF NOT EXISTS" (MySQL 8.0.16+ only)
-- This migration provides the same DDL using information_schema probes (MySQL 5.5+ compatible)
-- If V21 already ran on MySQL 8.0+, this migration is a no-op.

-- review_note column
set @sql := if(
  exists(select 1 from information_schema.columns
    where table_schema = database() and table_name = 'salary_record' and column_name = 'review_note'),
  'select 1',
  'alter table salary_record add column review_note varchar(500) null after reviewed_at'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

-- paid_at column
set @sql := if(
  exists(select 1 from information_schema.columns
    where table_schema = database() and table_name = 'salary_record' and column_name = 'paid_at'),
  'select 1',
  'alter table salary_record add column paid_at timestamp null after review_note'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

-- version column
set @sql := if(
  exists(select 1 from information_schema.columns
    where table_schema = database() and table_name = 'salary_record' and column_name = 'version'),
  'select 1',
  'alter table salary_record add column version int not null default 1 after paid_at'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

-- idx_salary_version index
set @sql := if(
  exists(select 1 from information_schema.statistics
    where table_schema = database() and table_name = 'salary_record' and index_name = 'idx_salary_version'),
  'select 1',
  'create index idx_salary_version on salary_record(tenant_id, employee_id, month, version)'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;
