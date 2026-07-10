create table if not exists warehouse_item_category (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  name varchar(120) not null,
  parent_id bigint null,
  sort_order int not null default 0,
  enabled tinyint(1) not null default 1,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  unique key uk_item_category_tenant_parent_name (tenant_id, parent_id, name),
  index idx_item_category_tenant_enabled (tenant_id, enabled, sort_order),
  index idx_item_category_parent (tenant_id, parent_id),
  constraint fk_item_category_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_item_category_parent foreign key (parent_id) references warehouse_item_category(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'warehouse_item' and column_name = 'category_id'), 'select 1', 'alter table warehouse_item add column category_id bigint null after category');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'warehouse_item' and column_name = 'image_url'), 'select 1', 'alter table warehouse_item add column image_url varchar(500) null after category_id');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'warehouse_item' and column_name = 'purchase_unit'), 'select 1', 'alter table warehouse_item add column purchase_unit varchar(40) null after unit');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'warehouse_item' and column_name = 'stock_unit'), 'select 1', 'alter table warehouse_item add column stock_unit varchar(40) null after purchase_unit');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'warehouse_item' and column_name = 'ingredient_unit'), 'select 1', 'alter table warehouse_item add column ingredient_unit varchar(40) null after stock_unit');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'warehouse_item' and column_name = 'unit_conversion_text'), 'select 1', 'alter table warehouse_item add column unit_conversion_text varchar(160) null after ingredient_unit');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'warehouse_item' and column_name = 'warehouse_location'), 'select 1', 'alter table warehouse_item add column warehouse_location varchar(120) null after spec');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'warehouse_item' and column_name = 'item_description'), 'select 1', 'alter table warehouse_item add column item_description text null after expiry_alert_days');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'warehouse_item' and column_name = 'sort_order'), 'select 1', 'alter table warehouse_item add column sort_order int not null default 593 after item_description');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'warehouse_item' and column_name = 'item_attributes'), 'select 1', 'alter table warehouse_item add column item_attributes varchar(255) null after sort_order');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'warehouse_item' and index_name = 'idx_warehouse_item_category_id'), 'select 1', 'alter table warehouse_item add index idx_warehouse_item_category_id (tenant_id, category_id)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

create table if not exists warehouse_item_department (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  item_id bigint not null,
  department_name varchar(120) not null,
  department_code varchar(80) null,
  department_group varchar(120) null,
  purchase_method varchar(120) null,
  supplier_name varchar(160) null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  index idx_item_department_item (tenant_id, item_id),
  constraint fk_item_department_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_item_department_item foreign key (item_id) references warehouse_item(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

insert into warehouse_item_category(tenant_id, name, parent_id, sort_order, enabled, created_at)
values
  (1, '器材1', null, 10, 1, current_timestamp),
  (1, '器材2', null, 20, 1, current_timestamp),
  (1, '水果', null, 30, 1, current_timestamp),
  (1, '包装', null, 40, 1, current_timestamp),
  (1, '茶叶', null, 50, 1, current_timestamp),
  (1, '抹布+工作服', null, 60, 1, current_timestamp),
  (1, '奶制品', null, 70, 1, current_timestamp)
on duplicate key update
  sort_order = values(sort_order),
  enabled = 1,
  updated_at = current_timestamp;

update warehouse_item
set purchase_unit = coalesce(purchase_unit, unit),
    stock_unit = coalesce(stock_unit, unit),
    ingredient_unit = coalesce(ingredient_unit, unit)
where tenant_id = 1;

update warehouse_item i
join warehouse_item_category c
  on c.tenant_id = i.tenant_id
 and c.parent_id is null
 and c.name = case
   when i.category = '包材' then '包装'
   when i.category = '乳制品' then '奶制品'
   else i.category
 end
set i.category_id = c.id,
    i.category = c.name
where i.tenant_id = 1
  and i.category_id is null;
