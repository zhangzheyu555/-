-- Generic attachments must be bound to a tenant and store before clients can retrieve them.
alter table warehouse_attachment add column if not exists store_id varchar(64) null;

create index if not exists idx_warehouse_attachment_store on warehouse_attachment (tenant_id, store_id, id);
