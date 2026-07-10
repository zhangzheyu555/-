alter table warehouse_return_order add column if not exists source_requisition_id varchar(120) null;
alter table warehouse_return_order add column if not exists source_delivery_id varchar(120) null;
alter table warehouse_return_order add column if not exists reason text null;
alter table warehouse_return_order add column if not exists review_note text null;
alter table warehouse_return_order add column if not exists received_note text null;
alter table warehouse_return_order add column if not exists reviewed_at timestamp null default null;
alter table warehouse_return_order add column if not exists received_at timestamp null default null;

alter table warehouse_return_order_line add column if not exists batch_id bigint null;
alter table warehouse_return_order_line add column if not exists batch_no varchar(120) null;
alter table warehouse_return_order_line add column if not exists source_requisition_line_id bigint null;
alter table warehouse_return_order_line add column if not exists reason text null;

alter table warehouse_attachment add column if not exists content longblob null;

create index if not exists idx_return_order_source_req on warehouse_return_order (tenant_id, source_requisition_id);
create index if not exists idx_return_order_source_delivery on warehouse_return_order (tenant_id, source_delivery_id);
