-- Generic attachments must be bound to a tenant and store before clients can retrieve them.
set @sql := if(
  exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'warehouse_attachment' and column_name = 'store_id'),
  'select 1',
  'alter table warehouse_attachment add column store_id varchar(64) null after tenant_id'
);
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(
  exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'warehouse_attachment' and index_name = 'idx_warehouse_attachment_store'),
  'select 1',
  'alter table warehouse_attachment add index idx_warehouse_attachment_store (tenant_id, store_id, id)'
);
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
