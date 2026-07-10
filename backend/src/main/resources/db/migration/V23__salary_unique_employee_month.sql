-- V23: Add unique constraint to prevent duplicate salary records per employee per month
-- (tenant_id, employee_id, month) must be unique when employee_id is assigned
-- NULL employee_id is allowed multiple times (MySQL UNIQUE key behavior)
-- This uses information_schema probe for MySQL 5.5+ compatibility

set @sql := if(
  exists(select 1 from information_schema.table_constraints
    where table_schema = database() and table_name = 'salary_record' and constraint_name = 'uk_salary_tenant_employee_month'),
  'select 1',
  'alter table salary_record add unique key uk_salary_tenant_employee_month (tenant_id, employee_id, month)'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;
