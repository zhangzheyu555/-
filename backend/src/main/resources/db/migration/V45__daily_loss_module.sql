-- V45: 每日报损模块。物料和受控单价复用 warehouse_item，绝不导入旧报损模块的固定物料或价格。

create table daily_loss_record (
  id varchar(120) not null primary key,
  tenant_id bigint not null,
  store_id varchar(64) not null,
  loss_date date not null,
  item_id bigint not null,
  item_code varchar(120) not null,
  item_name varchar(160) not null,
  stock_unit varchar(40) not null,
  loss_quantity decimal(14,2) not null,
  unit_price_snapshot decimal(18,4) not null,
  amount_snapshot decimal(18,2) not null,
  loss_reason varchar(500) not null,
  status varchar(32) not null default 'SUBMITTED',
  submitted_by bigint not null,
  submitted_at timestamp not null default current_timestamp,
  reviewed_by bigint null,
  reviewed_at timestamp null,
  review_note varchar(500) null,
  updated_at timestamp null default null on update current_timestamp,
  index idx_daily_loss_store_date (tenant_id, store_id, loss_date, submitted_at),
  index idx_daily_loss_status (tenant_id, status, submitted_at),
  index idx_daily_loss_item (tenant_id, item_id, loss_date),
  constraint chk_daily_loss_quantity check (loss_quantity > 0),
  constraint chk_daily_loss_unit_price check (unit_price_snapshot >= 0),
  constraint chk_daily_loss_amount check (amount_snapshot >= 0),
  constraint chk_daily_loss_status check (status in ('SUBMITTED', 'APPROVED', 'REJECTED')),
  constraint fk_daily_loss_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_daily_loss_store foreign key (store_id) references store_branch(id),
  constraint fk_daily_loss_item foreign key (item_id) references warehouse_item(id),
  constraint fk_daily_loss_submitter foreign key (submitted_by) references auth_user(id),
  constraint fk_daily_loss_reviewer foreign key (reviewed_by) references auth_user(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

-- This table is the idempotency boundary for inventory deduction.  One approved daily-loss
-- record can produce exactly one LOSS_OUT store inventory movement; it is written in the same
-- transaction as the inventory change and review state transition.
create table daily_loss_inventory_application (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  daily_loss_id varchar(120) not null,
  store_id varchar(64) not null,
  item_id bigint not null,
  quantity decimal(14,2) not null,
  movement_type varchar(40) not null default 'LOSS_OUT',
  applied_by bigint not null,
  applied_at timestamp not null default current_timestamp,
  unique key uk_daily_loss_inventory_application (tenant_id, daily_loss_id),
  index idx_daily_loss_inventory_store (tenant_id, store_id, applied_at),
  constraint chk_daily_loss_inventory_quantity check (quantity > 0),
  constraint chk_daily_loss_inventory_type check (movement_type = 'LOSS_OUT'),
  constraint fk_daily_loss_inventory_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_daily_loss_inventory_record foreign key (daily_loss_id) references daily_loss_record(id),
  constraint fk_daily_loss_inventory_store foreign key (store_id) references store_branch(id),
  constraint fk_daily_loss_inventory_item foreign key (item_id) references warehouse_item(id),
  constraint fk_daily_loss_inventory_actor foreign key (applied_by) references auth_user(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

insert into permission_catalog(
  permission_code, module_code, permission_name, description, risk_level, enabled, sort_order
)
values
  ('daily_loss.read', 'DAILY_LOSS', '查看每日报损', '查看权限范围内的每日报损单及其附件。', 'MEDIUM', 1, 615),
  ('daily_loss.create', 'DAILY_LOSS', '提交每日报损', '为权限范围内的门店提交每日报损单。', 'MEDIUM', 1, 616),
  ('daily_loss.review', 'DAILY_LOSS', '复核每日报损', '复核报损并触发一次门店库存损耗出库。', 'HIGH', 1, 617)
on duplicate key update
  module_code = values(module_code),
  permission_name = values(permission_name),
  description = values(description),
  risk_level = values(risk_level),
  enabled = values(enabled),
  sort_order = values(sort_order);

insert into role_permission(tenant_id, role_code, permission_code, created_at)
select tenant.id, template.role_code, template.permission_code, current_timestamp
from tenant
join (
  select 'STORE_MANAGER' as role_code, 'daily_loss.read' as permission_code
  union all select 'STORE_MANAGER', 'daily_loss.create'
  union all select 'OPERATIONS', 'daily_loss.read'
  union all select 'OPERATIONS', 'daily_loss.review'
  union all select 'FINANCE', 'daily_loss.read'
  union all select 'FINANCE', 'daily_loss.review'
  union all select 'WAREHOUSE', 'daily_loss.read'
  union all select 'WAREHOUSE', 'daily_loss.review'
) template on 1 = 1
where not exists (
  select 1 from role_permission existing
  where existing.tenant_id = tenant.id
    and existing.role_code = template.role_code
    and existing.permission_code = template.permission_code
);

-- Role-template permissions changed; force a fresh authenticated session before the new grants apply.
update auth_user set permission_version = permission_version + 1;
delete from auth_token;
