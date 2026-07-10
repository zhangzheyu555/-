create table if not exists store_inventory (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  store_id varchar(64) not null,
  item_id bigint not null,
  quantity decimal(14,2) not null default 0,
  unit varchar(40) null,
  updated_at timestamp not null default current_timestamp,
  unique key uk_store_inventory_item (tenant_id, store_id, item_id),
  index idx_store_inventory_store (tenant_id, store_id),
  constraint fk_store_inventory_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_store_inventory_store foreign key (store_id) references store_branch(id),
  constraint fk_store_inventory_item foreign key (item_id) references warehouse_item(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists store_inventory_movement (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  store_id varchar(64) not null,
  item_id bigint not null,
  quantity_delta decimal(14,2) not null,
  movement_type varchar(40) not null,
  source_type varchar(60) null,
  source_id varchar(120) null,
  note text null,
  created_by bigint null,
  created_at timestamp not null default current_timestamp,
  index idx_store_inventory_movement_store (tenant_id, store_id, created_at),
  index idx_store_inventory_movement_source (tenant_id, source_type, source_id),
  constraint fk_store_inventory_movement_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_store_inventory_movement_store foreign key (store_id) references store_branch(id),
  constraint fk_store_inventory_movement_item foreign key (item_id) references warehouse_item(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

insert into store_inventory(tenant_id, store_id, item_id, quantity, unit, updated_at)
select t.tenant_id,
       t.store_id,
       t.item_id,
       sum(t.quantity_delta) as quantity,
       coalesce(max(i.stock_unit), max(i.unit), '件') as unit,
       current_timestamp
from (
  select r.tenant_id, r.store_id, rl.item_id, rl.received_quantity as quantity_delta
  from store_receipt r
  join store_receipt_line rl on rl.tenant_id = r.tenant_id and rl.receipt_id = r.id
  union all
  select ro.tenant_id, ro.return_store_id as store_id, rol.item_id, -rol.quantity as quantity_delta
  from warehouse_return_order ro
  join warehouse_return_order_line rol on rol.tenant_id = ro.tenant_id and rol.return_order_id = ro.id
  where ro.status in ('RECEIVED', 'CHECKED')
) t
join warehouse_item i on i.tenant_id = t.tenant_id and i.id = t.item_id
group by t.tenant_id, t.store_id, t.item_id
having sum(t.quantity_delta) <> 0
on duplicate key update
  quantity = values(quantity),
  unit = values(unit),
  updated_at = current_timestamp;
