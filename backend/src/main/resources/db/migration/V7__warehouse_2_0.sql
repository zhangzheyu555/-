set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'store_requisition' and column_name = 'received_by'), 'select 1', 'alter table store_requisition add column received_by bigint null after shipped_by');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'store_requisition' and column_name = 'received_at'), 'select 1', 'alter table store_requisition add column received_at timestamp null default null after shipped_at');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'store_requisition' and column_name = 'received_note'), 'select 1', 'alter table store_requisition add column received_note text null after note');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

create table if not exists warehouse_supplier (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  name varchar(160) not null,
  contact_name varchar(80) null,
  phone varchar(80) null,
  settlement_cycle varchar(80) null,
  active tinyint(1) not null default 1,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  unique key uk_warehouse_supplier_tenant_name (tenant_id, name),
  index idx_warehouse_supplier_tenant_active (tenant_id, active),
  constraint fk_warehouse_supplier_tenant foreign key (tenant_id) references tenant(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists warehouse_purchase_order (
  id varchar(120) not null primary key,
  tenant_id bigint not null,
  supplier_id bigint null,
  status varchar(40) not null default 'DRAFT',
  total_amount decimal(14,2) not null default 0,
  note text null,
  created_by bigint null,
  received_by bigint null,
  created_at timestamp not null default current_timestamp,
  received_at timestamp null default null,
  updated_at timestamp null default null,
  index idx_purchase_order_tenant_status (tenant_id, status, created_at),
  index idx_purchase_order_supplier (tenant_id, supplier_id),
  constraint fk_purchase_order_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_purchase_order_supplier foreign key (supplier_id) references warehouse_supplier(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists warehouse_purchase_order_line (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  purchase_order_id varchar(120) not null,
  item_id bigint not null,
  ordered_quantity decimal(14,2) not null default 0,
  received_quantity decimal(14,2) not null default 0,
  unit_cost decimal(14,2) not null default 0,
  amount decimal(14,2) not null default 0,
  note text null,
  index idx_purchase_line_order (tenant_id, purchase_order_id),
  index idx_purchase_line_item (tenant_id, item_id),
  constraint fk_purchase_line_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_purchase_line_order foreign key (purchase_order_id) references warehouse_purchase_order(id),
  constraint fk_purchase_line_item foreign key (item_id) references warehouse_item(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists warehouse_delivery_order (
  id varchar(120) not null primary key,
  tenant_id bigint not null,
  requisition_id varchar(120) not null,
  store_id varchar(64) not null,
  status varchar(40) not null default 'SHIPPED',
  shipped_by bigint null,
  received_by bigint null,
  shipped_at timestamp not null default current_timestamp,
  received_at timestamp null default null,
  note text null,
  index idx_delivery_tenant_status (tenant_id, status, shipped_at),
  index idx_delivery_req (tenant_id, requisition_id),
  constraint fk_delivery_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_delivery_req foreign key (requisition_id) references store_requisition(id),
  constraint fk_delivery_store foreign key (store_id) references store_branch(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists warehouse_delivery_order_line (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  delivery_id varchar(120) not null,
  requisition_line_id bigint null,
  item_id bigint not null,
  shipped_quantity decimal(14,2) not null default 0,
  received_quantity decimal(14,2) not null default 0,
  unit_price decimal(14,2) not null default 0,
  amount decimal(14,2) not null default 0,
  note text null,
  index idx_delivery_line_delivery (tenant_id, delivery_id),
  index idx_delivery_line_item (tenant_id, item_id),
  constraint fk_delivery_line_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_delivery_line_delivery foreign key (delivery_id) references warehouse_delivery_order(id),
  constraint fk_delivery_line_item foreign key (item_id) references warehouse_item(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists store_receipt (
  id varchar(120) not null primary key,
  tenant_id bigint not null,
  delivery_id varchar(120) not null,
  requisition_id varchar(120) not null,
  store_id varchar(64) not null,
  status varchar(40) not null default 'RECEIVED',
  received_by bigint null,
  received_at timestamp not null default current_timestamp,
  note text null,
  index idx_store_receipt_store (tenant_id, store_id, received_at),
  constraint fk_receipt_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_receipt_delivery foreign key (delivery_id) references warehouse_delivery_order(id),
  constraint fk_receipt_req foreign key (requisition_id) references store_requisition(id),
  constraint fk_receipt_store foreign key (store_id) references store_branch(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists store_receipt_line (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  receipt_id varchar(120) not null,
  item_id bigint not null,
  received_quantity decimal(14,2) not null default 0,
  note text null,
  index idx_receipt_line_receipt (tenant_id, receipt_id),
  constraint fk_receipt_line_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_receipt_line_receipt foreign key (receipt_id) references store_receipt(id),
  constraint fk_receipt_line_item foreign key (item_id) references warehouse_item(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists warehouse_stock_adjustment (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  item_id bigint not null,
  batch_id bigint null,
  adjustment_type varchar(40) not null,
  quantity_delta decimal(14,2) not null default 0,
  reason text null,
  operator_id bigint null,
  created_at timestamp not null default current_timestamp,
  index idx_adjustment_tenant_item (tenant_id, item_id, created_at),
  constraint fk_adjustment_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_adjustment_item foreign key (item_id) references warehouse_item(id),
  constraint fk_adjustment_batch foreign key (batch_id) references warehouse_stock_batch(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists warehouse_attachment (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  business_type varchar(60) not null,
  business_id varchar(120) not null,
  file_name varchar(255) not null,
  content_type varchar(120) null,
  file_size bigint null,
  storage_path varchar(500) null,
  uploaded_by bigint null,
  uploaded_at timestamp not null default current_timestamp,
  index idx_warehouse_attachment_business (tenant_id, business_type, business_id),
  constraint fk_warehouse_attachment_tenant foreign key (tenant_id) references tenant(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists warehouse_alert (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  alert_type varchar(60) not null,
  severity varchar(40) not null default 'WARN',
  item_id bigint null,
  store_id varchar(64) null,
  source_type varchar(60) null,
  source_id varchar(120) null,
  message varchar(500) not null,
  status varchar(40) not null default 'OPEN',
  created_at timestamp not null default current_timestamp,
  handled_at timestamp null default null,
  index idx_warehouse_alert_tenant_status (tenant_id, status, severity, created_at),
  constraint fk_warehouse_alert_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_warehouse_alert_item foreign key (item_id) references warehouse_item(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

insert into warehouse_supplier(tenant_id, name, contact_name, phone, settlement_cycle, active, created_at)
values
  (1, '总部默认供应商', '采购负责人', '', '月结', 1, current_timestamp)
on duplicate key update
  contact_name = values(contact_name),
  settlement_cycle = values(settlement_cycle),
  active = 1,
  updated_at = current_timestamp;
