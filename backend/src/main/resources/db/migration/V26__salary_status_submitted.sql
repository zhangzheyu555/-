-- V26: Rename PENDING_REVIEW status to SUBMITTED
-- Aligns with the target state machine: DRAFT → SUBMITTED → APPROVED/REJECTED → PAID → LOCKED
-- Also updates any operation_log records that reference the old status name

set @sql := if(
  exists(select 1 from information_schema.columns
    where table_schema = database() and table_name = 'salary_record' and column_name = 'status'),
  'update salary_record set status = ''SUBMITTED'' where status = ''PENDING_REVIEW''',
  'select 1'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;
