create table if not exists warehouse_item (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  code varchar(80) not null,
  name varchar(160) not null,
  category varchar(80) null,
  unit varchar(40) not null default '件',
  spec varchar(160) null,
  unit_price decimal(14,2) not null default 0,
  shelf_life_days int null,
  cups_per_unit decimal(14,2) not null default 0,
  daily_usage_estimate decimal(14,2) not null default 0,
  min_stock_days int not null default 7,
  max_stock_days int not null default 60,
  active tinyint(1) not null default 1,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  unique key uk_warehouse_item_tenant_code (tenant_id, code),
  index idx_warehouse_item_tenant_category (tenant_id, category),
  constraint fk_warehouse_item_tenant foreign key (tenant_id) references tenant(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists warehouse_stock_batch (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  item_id bigint not null,
  batch_no varchar(120) not null,
  received_date date not null,
  expiry_date date null,
  quantity decimal(14,2) not null default 0,
  unit_cost decimal(14,2) not null default 0,
  note text null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  unique key uk_warehouse_batch_tenant_item_batch (tenant_id, item_id, batch_no),
  index idx_warehouse_batch_tenant_expiry (tenant_id, expiry_date),
  index idx_warehouse_batch_item (item_id),
  constraint fk_warehouse_batch_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_warehouse_batch_item foreign key (item_id) references warehouse_item(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists warehouse_stock_movement (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  item_id bigint not null,
  batch_id bigint null,
  movement_type varchar(40) not null,
  quantity_delta decimal(14,2) not null,
  source_type varchar(60) null,
  source_id varchar(120) null,
  store_id varchar(64) null,
  note text null,
  operator_id bigint null,
  created_at timestamp not null default current_timestamp,
  index idx_warehouse_movement_tenant_item (tenant_id, item_id, created_at),
  index idx_warehouse_movement_source (tenant_id, source_type, source_id),
  constraint fk_warehouse_movement_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_warehouse_movement_item foreign key (item_id) references warehouse_item(id),
  constraint fk_warehouse_movement_batch foreign key (batch_id) references warehouse_stock_batch(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists store_requisition (
  id varchar(120) not null primary key,
  tenant_id bigint not null,
  store_id varchar(64) not null,
  status varchar(40) not null default 'SUBMITTED',
  total_amount decimal(14,2) not null default 0,
  note text null,
  submitted_by bigint null,
  reviewed_by bigint null,
  shipped_by bigint null,
  submitted_at timestamp not null default current_timestamp,
  reviewed_at timestamp null default null,
  shipped_at timestamp null default null,
  updated_at timestamp null default null,
  index idx_store_requisition_tenant_status (tenant_id, status, submitted_at),
  index idx_store_requisition_store (tenant_id, store_id, submitted_at),
  constraint fk_store_requisition_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_store_requisition_store foreign key (store_id) references store_branch(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists store_requisition_line (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  requisition_id varchar(120) not null,
  item_id bigint not null,
  requested_quantity decimal(14,2) not null default 0,
  approved_quantity decimal(14,2) null,
  shipped_quantity decimal(14,2) not null default 0,
  unit_price decimal(14,2) not null default 0,
  amount decimal(14,2) not null default 0,
  warning_text varchar(255) null,
  note text null,
  index idx_requisition_line_req (tenant_id, requisition_id),
  index idx_requisition_line_item (tenant_id, item_id),
  constraint fk_requisition_line_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_requisition_line_req foreign key (requisition_id) references store_requisition(id),
  constraint fk_requisition_line_item foreign key (item_id) references warehouse_item(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

insert into warehouse_item(
  tenant_id, code, name, category, unit, spec, unit_price, shelf_life_days,
  cups_per_unit, daily_usage_estimate, min_stock_days, max_stock_days, created_at
) values
  (1, 'CUP-700', '700ml杯子', '包材', '件', '1000个/件', 138.00, 365, 1000, 8, 20, 90, current_timestamp),
  (1, 'COCONUT-POWDER', '椰子粉', '原料', '件', '1箱/件', 260.00, 365, 300, 1.6, 15, 80, current_timestamp),
  (1, 'GRAPE-15JIN', '葡萄', '水果', '件', '15斤/件', 95.00, 5, 20, 9, 3, 7, current_timestamp),
  (1, 'FRESH-MILK', '鲜奶', '乳制品', '件', '12盒/件', 88.00, 15, 120, 4, 5, 12, current_timestamp),
  (1, 'STRAW', '吸管', '包材', '件', '1000支/件', 55.00, 730, 1000, 6, 20, 120, current_timestamp)
on duplicate key update
  name = values(name),
  category = values(category),
  unit = values(unit),
  spec = values(spec),
  unit_price = values(unit_price),
  shelf_life_days = values(shelf_life_days),
  cups_per_unit = values(cups_per_unit),
  daily_usage_estimate = values(daily_usage_estimate),
  min_stock_days = values(min_stock_days),
  max_stock_days = values(max_stock_days),
  active = 1,
  updated_at = current_timestamp;

insert into warehouse_stock_batch(tenant_id, item_id, batch_no, received_date, expiry_date, quantity, unit_cost, note, created_at)
select 1, id, concat(code, '-SEED'), current_date, date_add(current_date, interval coalesce(shelf_life_days, 365) day),
       case code
         when 'CUP-700' then 120
         when 'COCONUT-POWDER' then 90
         when 'GRAPE-15JIN' then 18
         when 'FRESH-MILK' then 36
         else 180
       end,
       unit_price,
       '演示初始库存',
       current_timestamp
from warehouse_item
where tenant_id = 1 and code in ('CUP-700', 'COCONUT-POWDER', 'GRAPE-15JIN', 'FRESH-MILK', 'STRAW')
on duplicate key update
  quantity = values(quantity),
  unit_cost = values(unit_cost),
  expiry_date = values(expiry_date),
  updated_at = current_timestamp;
