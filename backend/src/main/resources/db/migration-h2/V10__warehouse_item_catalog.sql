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
);

alter table warehouse_item add column if not exists category_id bigint null;
alter table warehouse_item add column if not exists image_url varchar(500) null;
alter table warehouse_item add column if not exists purchase_unit varchar(40) null;
alter table warehouse_item add column if not exists stock_unit varchar(40) null;
alter table warehouse_item add column if not exists ingredient_unit varchar(40) null;
alter table warehouse_item add column if not exists unit_conversion_text varchar(160) null;
alter table warehouse_item add column if not exists warehouse_location varchar(120) null;
alter table warehouse_item add column if not exists item_description text null;
alter table warehouse_item add column if not exists sort_order int not null default 593;
alter table warehouse_item add column if not exists item_attributes varchar(255) null;

create index if not exists idx_warehouse_item_category_id on warehouse_item (tenant_id, category_id);

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
);

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

-- MySQL 版是 update ... join 语法，H2 不支持，改写为相关子查询。
update warehouse_item i
set category_id = (
      select c.id
      from warehouse_item_category c
      where c.tenant_id = i.tenant_id
        and c.parent_id is null
        and c.name = case
          when i.category = '包材' then '包装'
          when i.category = '乳制品' then '奶制品'
          else i.category
        end
    ),
    category = (
      select c.name
      from warehouse_item_category c
      where c.tenant_id = i.tenant_id
        and c.parent_id is null
        and c.name = case
          when i.category = '包材' then '包装'
          when i.category = '乳制品' then '奶制品'
          else i.category
        end
    )
where i.tenant_id = 1
  and i.category_id is null
  and exists (
    select 1
    from warehouse_item_category c
    where c.tenant_id = i.tenant_id
      and c.parent_id is null
      and c.name = case
        when i.category = '包材' then '包装'
        when i.category = '乳制品' then '奶制品'
        else i.category
      end
  );
