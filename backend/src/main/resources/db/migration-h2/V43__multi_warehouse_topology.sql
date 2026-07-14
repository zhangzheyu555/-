-- H2 test equivalent of V43__multi_warehouse_topology.sql.

create table warehouse_facility (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  code varchar(64) not null,
  name varchar(160) not null,
  warehouse_type varchar(32) not null,
  region_code varchar(32) not null,
  parent_warehouse_id bigint null,
  external_purchase_allowed tinyint(1) not null default 0,
  store_supply_allowed tinyint(1) not null default 1,
  enabled tinyint(1) not null default 1,
  created_by bigint null,
  created_at timestamp not null default current_timestamp,
  updated_by bigint null,
  updated_at timestamp null default null,
  constraint uk_warehouse_facility_code unique (tenant_id, code),
  constraint chk_warehouse_facility_type check (warehouse_type in ('CENTRAL', 'REGIONAL')),
  constraint chk_warehouse_facility_region check (region_code in ('JINGZHOU', 'SHANDONG')),
  constraint chk_warehouse_facility_parent check (
    (warehouse_type = 'CENTRAL' and parent_warehouse_id is null)
    or (warehouse_type = 'REGIONAL' and parent_warehouse_id is not null)
  ),
  constraint chk_warehouse_facility_purchase check (
    external_purchase_allowed = 0 or warehouse_type = 'CENTRAL'
  ),
  constraint fk_warehouse_facility_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_warehouse_facility_parent foreign key (parent_warehouse_id)
    references warehouse_facility(id)
);

create index idx_warehouse_facility_region
  on warehouse_facility(tenant_id, region_code, enabled);
create index idx_warehouse_facility_parent
  on warehouse_facility(tenant_id, parent_warehouse_id);

insert into warehouse_facility(
  tenant_id, code, name, warehouse_type, region_code, parent_warehouse_id,
  external_purchase_allowed, store_supply_allowed, enabled, created_at
)
select tenant.id, 'JZ-CENTRAL', '荆州总仓', 'CENTRAL', 'JINGZHOU', null,
       1, 1, 1, current_timestamp
from tenant;

insert into warehouse_facility(
  tenant_id, code, name, warehouse_type, region_code, parent_warehouse_id,
  external_purchase_allowed, store_supply_allowed, enabled, created_at
)
select tenant.id, 'SD-REGIONAL', '山东分仓', 'REGIONAL', 'SHANDONG', central.id,
       0, 1, 1, current_timestamp
from tenant
join warehouse_facility central
  on central.tenant_id = tenant.id and central.code = 'JZ-CENTRAL';

create table warehouse_transfer_route (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  source_warehouse_id bigint not null,
  target_warehouse_id bigint not null,
  enabled tinyint(1) not null default 1,
  created_by bigint null,
  created_at timestamp not null default current_timestamp,
  updated_by bigint null,
  updated_at timestamp null default null,
  constraint uk_warehouse_transfer_route unique (
    tenant_id, source_warehouse_id, target_warehouse_id
  ),
  constraint chk_warehouse_transfer_route_distinct check (source_warehouse_id <> target_warehouse_id),
  constraint fk_warehouse_transfer_route_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_warehouse_transfer_route_source foreign key (source_warehouse_id)
    references warehouse_facility(id),
  constraint fk_warehouse_transfer_route_target foreign key (target_warehouse_id)
    references warehouse_facility(id)
);

create index idx_warehouse_transfer_route_target
  on warehouse_transfer_route(tenant_id, target_warehouse_id, enabled);

insert into warehouse_transfer_route(
  tenant_id, source_warehouse_id, target_warehouse_id, enabled, created_at
)
select central.tenant_id, central.id, regional.id, 1, current_timestamp
from warehouse_facility central
join warehouse_facility regional
  on regional.tenant_id = central.tenant_id and regional.code = 'SD-REGIONAL'
where central.code = 'JZ-CENTRAL';

alter table store_branch add column region_code varchar(32) null;
alter table store_branch add column supply_warehouse_id bigint null;
create index idx_store_supply_warehouse
  on store_branch(tenant_id, supply_warehouse_id, status);
alter table store_branch add constraint chk_store_region_code check (
  region_code is null or region_code in ('JINGZHOU', 'SHANDONG')
);
alter table store_branch add constraint fk_store_supply_warehouse
  foreign key (supply_warehouse_id) references warehouse_facility(id);

create table warehouse_topology_migration_audit (
  id bigint not null auto_increment primary key,
  migration_key varchar(64) not null,
  tenant_id bigint not null,
  expected_business_store_count int not null,
  actual_business_store_count int not null,
  bound_store_count int not null default 0,
  central_warehouse_id bigint not null,
  regional_warehouse_id bigint not null,
  binding_status varchar(40) not null,
  difference_message varchar(500) null,
  created_at timestamp not null default current_timestamp,
  constraint uk_warehouse_topology_audit unique (migration_key, tenant_id),
  constraint chk_warehouse_topology_status check (
    binding_status in ('BOUND_38', 'SKIPPED_EMPTY', 'SKIPPED_COUNT_MISMATCH')
  ),
  constraint fk_warehouse_topology_audit_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_warehouse_topology_audit_central foreign key (central_warehouse_id)
    references warehouse_facility(id),
  constraint fk_warehouse_topology_audit_regional foreign key (regional_warehouse_id)
    references warehouse_facility(id)
);

create index idx_warehouse_topology_status
  on warehouse_topology_migration_audit(binding_status, created_at);

insert into warehouse_topology_migration_audit(
  migration_key, tenant_id, expected_business_store_count, actual_business_store_count,
  bound_store_count, central_warehouse_id, regional_warehouse_id,
  binding_status, difference_message, created_at
)
select 'V43_JINGZHOU_38_STORE_BINDING',
       tenant.id,
       38,
       (
         select count(*) from store_branch store
         where store.tenant_id = tenant.id
       ),
       0,
       central.id,
       regional.id,
       case
         when (
           select count(*) from store_branch store
           where store.tenant_id = tenant.id
         ) = 38 then 'BOUND_38'
         when (
           select count(*) from store_branch store
           where store.tenant_id = tenant.id
         ) = 0 then 'SKIPPED_EMPTY'
         else 'SKIPPED_COUNT_MISMATCH'
       end,
       case
         when (
           select count(*) from store_branch store
           where store.tenant_id = tenant.id
         ) = 38 then null
         when (
           select count(*) from store_branch store
           where store.tenant_id = tenant.id
         ) = 0 then '空库未执行门店绑定；后续启用门店必须先设置明确区域和供货仓。'
         else concat(
           '期望38家门店，实际',
           (
             select count(*) from store_branch store
             where store.tenant_id = tenant.id
           ),
           '家；已停止批量绑定，未按门店名称或模糊区域更新。'
         )
       end,
       current_timestamp
from tenant
join warehouse_facility central
  on central.tenant_id = tenant.id and central.code = 'JZ-CENTRAL'
join warehouse_facility regional
  on regional.tenant_id = tenant.id and regional.code = 'SD-REGIONAL';

update store_branch store
set region_code = 'JINGZHOU',
    supply_warehouse_id = (
      select central.id from warehouse_facility central
      where central.tenant_id = store.tenant_id and central.code = 'JZ-CENTRAL'
    ),
    updated_at = current_timestamp
where exists (
    select 1 from warehouse_topology_migration_audit audit
    where audit.tenant_id = store.tenant_id
      and audit.migration_key = 'V43_JINGZHOU_38_STORE_BINDING'
      and audit.binding_status = 'BOUND_38'
  );

update warehouse_topology_migration_audit audit
set bound_store_count = (
  select count(*) from store_branch store
  where store.tenant_id = audit.tenant_id
    and store.region_code = 'JINGZHOU'
    and store.supply_warehouse_id = audit.central_warehouse_id
)
where audit.migration_key = 'V43_JINGZHOU_38_STORE_BINDING';

alter table warehouse_stock_batch add column warehouse_id bigint null;
alter table warehouse_stock_batch add column reserved_quantity decimal(14,2) not null default 0;
alter table warehouse_stock_batch add column version bigint not null default 0;
update warehouse_stock_batch batch set warehouse_id = (
  select facility.id from warehouse_facility facility
  where facility.tenant_id = batch.tenant_id and facility.code = 'JZ-CENTRAL'
);
alter table warehouse_stock_batch alter column warehouse_id set not null;
alter table warehouse_stock_batch drop constraint uk_warehouse_batch_tenant_item_batch;
create unique index uk_warehouse_batch_facility_item_batch
  on warehouse_stock_batch(tenant_id, warehouse_id, item_id, batch_no);
create index idx_warehouse_batch_facility_item
  on warehouse_stock_batch(tenant_id, warehouse_id, item_id, expiry_date);
alter table warehouse_stock_batch add constraint chk_warehouse_batch_quantities check (
  quantity >= 0 and reserved_quantity >= 0 and reserved_quantity <= quantity
);
alter table warehouse_stock_batch add constraint fk_warehouse_batch_facility
  foreign key (warehouse_id) references warehouse_facility(id);

alter table warehouse_stock_movement add column warehouse_id bigint null;
alter table warehouse_stock_movement add column reserved_quantity_delta decimal(14,2) not null default 0;
alter table warehouse_stock_movement add column in_transit_quantity_delta decimal(14,2) not null default 0;
alter table warehouse_stock_movement add column unit_cost decimal(14,2) null;
update warehouse_stock_movement movement set warehouse_id = (
  select facility.id from warehouse_facility facility
  where facility.tenant_id = movement.tenant_id and facility.code = 'JZ-CENTRAL'
);
alter table warehouse_stock_movement alter column warehouse_id set not null;
create index idx_warehouse_movement_facility
  on warehouse_stock_movement(tenant_id, warehouse_id, created_at);
alter table warehouse_stock_movement add constraint chk_warehouse_movement_cost check (
  unit_cost is null or unit_cost >= 0
);
alter table warehouse_stock_movement add constraint fk_warehouse_movement_facility
  foreign key (warehouse_id) references warehouse_facility(id);

alter table store_requisition add column supply_warehouse_id bigint null;
alter table store_requisition add column idempotency_key varchar(120) null;
alter table store_requisition add column version bigint not null default 0;
update store_requisition requisition set supply_warehouse_id = (
  select facility.id from warehouse_facility facility
  where facility.tenant_id = requisition.tenant_id and facility.code = 'JZ-CENTRAL'
);
alter table store_requisition alter column supply_warehouse_id set not null;
create index idx_requisition_supply_warehouse
  on store_requisition(tenant_id, supply_warehouse_id, status, submitted_at);
create unique index uk_requisition_idempotency
  on store_requisition(tenant_id, idempotency_key);
alter table store_requisition add constraint fk_requisition_supply_warehouse
  foreign key (supply_warehouse_id) references warehouse_facility(id);

alter table warehouse_purchase_order add column warehouse_id bigint null;
alter table warehouse_purchase_order add column idempotency_key varchar(120) null;
alter table warehouse_purchase_order add column version bigint not null default 0;
update warehouse_purchase_order purchase_order set warehouse_id = (
  select facility.id from warehouse_facility facility
  where facility.tenant_id = purchase_order.tenant_id and facility.code = 'JZ-CENTRAL'
);
alter table warehouse_purchase_order alter column warehouse_id set not null;
create index idx_purchase_order_warehouse
  on warehouse_purchase_order(tenant_id, warehouse_id, status, created_at);
create unique index uk_purchase_idempotency
  on warehouse_purchase_order(tenant_id, idempotency_key);
alter table warehouse_purchase_order add constraint fk_purchase_order_warehouse
  foreign key (warehouse_id) references warehouse_facility(id);

alter table warehouse_delivery_order add column warehouse_id bigint null;
update warehouse_delivery_order delivery set warehouse_id = (
  select facility.id from warehouse_facility facility
  where facility.tenant_id = delivery.tenant_id and facility.code = 'JZ-CENTRAL'
);
alter table warehouse_delivery_order alter column warehouse_id set not null;
create index idx_delivery_warehouse
  on warehouse_delivery_order(tenant_id, warehouse_id, status, shipped_at);
alter table warehouse_delivery_order add constraint fk_delivery_warehouse
  foreign key (warehouse_id) references warehouse_facility(id);

alter table store_receipt add column warehouse_id bigint null;
update store_receipt receipt set warehouse_id = (
  select facility.id from warehouse_facility facility
  where facility.tenant_id = receipt.tenant_id and facility.code = 'JZ-CENTRAL'
);
alter table store_receipt alter column warehouse_id set not null;
create index idx_receipt_warehouse on store_receipt(tenant_id, warehouse_id, received_at);
alter table store_receipt add constraint fk_receipt_warehouse
  foreign key (warehouse_id) references warehouse_facility(id);

alter table warehouse_stock_adjustment add column warehouse_id bigint null;
update warehouse_stock_adjustment adjustment set warehouse_id = (
  select facility.id from warehouse_facility facility
  where facility.tenant_id = adjustment.tenant_id and facility.code = 'JZ-CENTRAL'
);
alter table warehouse_stock_adjustment alter column warehouse_id set not null;
create index idx_adjustment_warehouse
  on warehouse_stock_adjustment(tenant_id, warehouse_id, created_at);
alter table warehouse_stock_adjustment add constraint fk_adjustment_warehouse
  foreign key (warehouse_id) references warehouse_facility(id);

alter table warehouse_return_order add column warehouse_id bigint null;
update warehouse_return_order return_order set warehouse_id = (
  select facility.id from warehouse_facility facility
  where facility.tenant_id = return_order.tenant_id and facility.code = 'JZ-CENTRAL'
);
alter table warehouse_return_order alter column warehouse_id set not null;
create index idx_return_order_warehouse
  on warehouse_return_order(tenant_id, warehouse_id, status, return_date);
alter table warehouse_return_order add constraint fk_return_order_warehouse
  foreign key (warehouse_id) references warehouse_facility(id);

alter table warehouse_alert add column warehouse_id bigint null;
update warehouse_alert alert set warehouse_id = (
  select facility.id from warehouse_facility facility
  where facility.tenant_id = alert.tenant_id and facility.code = 'JZ-CENTRAL'
);
alter table warehouse_alert alter column warehouse_id set not null;
create index idx_warehouse_alert_facility
  on warehouse_alert(tenant_id, warehouse_id, status, created_at);
alter table warehouse_alert add constraint fk_warehouse_alert_facility
  foreign key (warehouse_id) references warehouse_facility(id);

-- Transactionally maintained balance projection used for row locking/version checks.
-- warehouse_stock_batch remains the cost/batch detail ledger.
create table warehouse_inventory (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  warehouse_id bigint not null,
  item_id bigint not null,
  on_hand_quantity decimal(14,2) not null default 0,
  reserved_quantity decimal(14,2) not null default 0,
  in_transit_quantity decimal(14,2) not null default 0,
  unit_cost decimal(18,4) not null default 0,
  min_stock_quantity decimal(14,2) not null default 0,
  alert_enabled boolean not null default true,
  expiry_alert_days int null default 3,
  version bigint not null default 0,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  constraint uk_warehouse_inventory unique (tenant_id, warehouse_id, item_id),
  constraint chk_warehouse_inventory_quantities check (
    on_hand_quantity >= 0
    and reserved_quantity >= 0
    and reserved_quantity <= on_hand_quantity
    and in_transit_quantity >= 0
    and unit_cost >= 0
    and min_stock_quantity >= 0
    and (expiry_alert_days is null or expiry_alert_days >= 0)
  ),
  constraint fk_warehouse_inventory_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_warehouse_inventory_facility foreign key (warehouse_id)
    references warehouse_facility(id),
  constraint fk_warehouse_inventory_item foreign key (item_id) references warehouse_item(id)
);

create index idx_warehouse_inventory_item
  on warehouse_inventory(tenant_id, item_id, warehouse_id);

insert into warehouse_inventory(
  tenant_id, warehouse_id, item_id, on_hand_quantity, reserved_quantity,
  in_transit_quantity, unit_cost, min_stock_quantity, alert_enabled,
  expiry_alert_days, version, created_at
)
select item.tenant_id,
       central.id,
       item.id,
       coalesce(sum(batch.quantity), 0),
       coalesce(sum(batch.reserved_quantity), 0),
       0,
       case
         when coalesce(sum(batch.quantity), 0) = 0 then 0
         else round(sum(batch.quantity * batch.unit_cost) / sum(batch.quantity), 4)
       end,
       item.min_stock_quantity,
       item.alert_enabled,
       item.expiry_alert_days,
       0,
       current_timestamp
from warehouse_item item
join warehouse_facility central
  on central.tenant_id = item.tenant_id and central.code = 'JZ-CENTRAL'
left join warehouse_stock_batch batch
  on batch.tenant_id = item.tenant_id
 and batch.warehouse_id = central.id
 and batch.item_id = item.id
group by item.tenant_id, central.id, item.id, item.min_stock_quantity,
         item.alert_enabled, item.expiry_alert_days;

insert into warehouse_inventory(
  tenant_id, warehouse_id, item_id, on_hand_quantity, reserved_quantity,
  in_transit_quantity, unit_cost, min_stock_quantity, alert_enabled,
  expiry_alert_days, version, created_at
)
select item.tenant_id, regional.id, item.id, 0, 0, 0, 0,
       item.min_stock_quantity, item.alert_enabled, item.expiry_alert_days,
       0, current_timestamp
from warehouse_item item
join warehouse_facility regional
  on regional.tenant_id = item.tenant_id and regional.code = 'SD-REGIONAL';

create table warehouse_transfer_order (
  id varchar(120) not null primary key,
  tenant_id bigint not null,
  transfer_no varchar(120) not null,
  source_warehouse_id bigint not null,
  target_warehouse_id bigint not null,
  status varchar(40) not null default 'DRAFT',
  idempotency_key varchar(120) not null,
  total_amount decimal(14,2) not null default 0,
  version bigint not null default 0,
  note text null,
  review_note text null,
  requested_by bigint null,
  reviewed_by bigint null,
  shipped_by bigint null,
  received_by bigint null,
  cancelled_by bigint null,
  created_at timestamp not null default current_timestamp,
  submitted_at timestamp null default null,
  reviewed_at timestamp null default null,
  shipped_at timestamp null default null,
  received_at timestamp null default null,
  cancelled_at timestamp null default null,
  updated_at timestamp null default null,
  constraint uk_warehouse_transfer_no unique (tenant_id, transfer_no),
  constraint uk_warehouse_transfer_idempotency unique (tenant_id, idempotency_key),
  constraint chk_warehouse_transfer_status check (
    status in (
      'DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'SHIPPED',
      'PARTIALLY_RECEIVED', 'RECEIVED', 'CANCELLED'
    )
  ),
  constraint chk_warehouse_transfer_distinct check (source_warehouse_id <> target_warehouse_id),
  constraint chk_warehouse_transfer_amount check (total_amount >= 0),
  constraint fk_warehouse_transfer_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_warehouse_transfer_source foreign key (source_warehouse_id)
    references warehouse_facility(id),
  constraint fk_warehouse_transfer_target foreign key (target_warehouse_id)
    references warehouse_facility(id)
);

create index idx_warehouse_transfer_source
  on warehouse_transfer_order(tenant_id, source_warehouse_id, status, created_at);
create index idx_warehouse_transfer_target
  on warehouse_transfer_order(tenant_id, target_warehouse_id, status, created_at);

create table warehouse_transfer_line (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  transfer_order_id varchar(120) not null,
  item_id bigint not null,
  requested_quantity decimal(14,2) not null,
  approved_quantity decimal(14,2) not null default 0,
  reserved_quantity decimal(14,2) not null default 0,
  shipped_quantity decimal(14,2) not null default 0,
  received_quantity decimal(14,2) not null default 0,
  in_transit_quantity decimal(14,2) not null default 0,
  unit_cost decimal(18,4) not null default 0,
  amount decimal(14,2) not null default 0,
  version bigint not null default 0,
  note text null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  constraint uk_warehouse_transfer_line_item unique (tenant_id, transfer_order_id, item_id),
  constraint chk_warehouse_transfer_line_quantities check (
    requested_quantity > 0
    and approved_quantity >= 0 and approved_quantity <= requested_quantity
    and reserved_quantity >= 0 and reserved_quantity <= approved_quantity
    and shipped_quantity >= 0 and shipped_quantity <= approved_quantity
    and received_quantity >= 0 and received_quantity <= shipped_quantity
    and in_transit_quantity >= 0
    and in_transit_quantity = shipped_quantity - received_quantity
    and unit_cost >= 0 and amount >= 0
  ),
  constraint fk_warehouse_transfer_line_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_warehouse_transfer_line_order foreign key (transfer_order_id)
    references warehouse_transfer_order(id),
  constraint fk_warehouse_transfer_line_item foreign key (item_id) references warehouse_item(id)
);

create index idx_warehouse_transfer_line_item
  on warehouse_transfer_line(tenant_id, item_id);

create table warehouse_transfer_action (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  transfer_order_id varchar(120) not null,
  action_type varchar(32) not null,
  idempotency_key varchar(120) not null,
  result_version bigint not null,
  operator_id bigint null,
  created_at timestamp not null default current_timestamp,
  constraint uk_warehouse_transfer_action unique (tenant_id, action_type, idempotency_key),
  constraint chk_warehouse_transfer_action check (
    action_type in ('SUBMIT', 'APPROVE', 'REJECT', 'SHIP', 'RECEIVE', 'CANCEL')
  ),
  constraint fk_warehouse_transfer_action_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_warehouse_transfer_action_order foreign key (transfer_order_id)
    references warehouse_transfer_order(id)
);

create index idx_warehouse_transfer_action_order
  on warehouse_transfer_action(tenant_id, transfer_order_id, created_at);

alter table user_data_scope drop constraint chk_user_data_scope_payload;
alter table user_data_scope drop constraint chk_user_data_scope_type;
alter table user_data_scope add constraint chk_user_data_scope_type check (
  scope_type in (
    'ALL', 'STORE_LIST', 'OWN_STORE', 'NONE', 'CENTRAL_WAREHOUSE',
    'WAREHOUSE_LIST', 'SELF'
  )
);
alter table user_data_scope add constraint chk_user_data_scope_payload check (
  (scope_type = 'STORE_LIST' and scope_value_json is not null and scope_value_json is json array)
  or (scope_type = 'WAREHOUSE_LIST'
    and domain_code = 'WAREHOUSE'
    and scope_value_json is not null
    and scope_value_json is json array
    and length(scope_value_json) > 2)
  or (scope_type not in ('STORE_LIST', 'WAREHOUSE_LIST') and scope_value_json is null)
);

insert into permission_catalog(
  permission_code, module_code, permission_name, description,
  risk_level, enabled, sort_order, created_at
)
values
  ('warehouse.read', 'WAREHOUSE', '查看仓库', '查看授权仓库的库存、流水和业务单据。', 'MEDIUM', 1, 500, current_timestamp),
  ('warehouse.purchase', 'WAREHOUSE', '外部采购', '为允许外部采购的总仓创建和处理采购入库。', 'HIGH', 1, 510, current_timestamp),
  ('warehouse.transfer.request', 'WAREHOUSE', '申请仓间调拨', '从授权分仓向合法上级仓提交补货申请。', 'MEDIUM', 1, 520, current_timestamp),
  ('warehouse.transfer.approve', 'WAREHOUSE', '审批仓间调拨', '审批或驳回授权仓库的调拨申请。', 'HIGH', 1, 530, current_timestamp),
  ('warehouse.transfer.ship', 'WAREHOUSE', '仓间调拨发货', '从授权来源仓发出已审批的调拨物料。', 'HIGH', 1, 540, current_timestamp),
  ('warehouse.transfer.receive', 'WAREHOUSE', '仓间调拨收货', '在授权目标仓确认调拨收货。', 'HIGH', 1, 550, current_timestamp),
  ('warehouse.requisition.process', 'WAREHOUSE', '处理门店叫货', '处理绑定到授权仓库的门店叫货。', 'HIGH', 1, 560, current_timestamp),
  ('warehouse.configure', 'WAREHOUSE', '配置仓库体系', '配置仓库、供货关系和仓库数据范围。', 'HIGH', 1, 570, current_timestamp)
on duplicate key update permission_code = values(permission_code);

-- Preserve explicit high-risk DENY decisions when replacing the old broad
-- warehouse permissions with action-specific permissions.
insert into user_permission_override(
  tenant_id, user_id, permission_code, effect, created_by, created_at
)
select legacy.tenant_id,
       legacy.user_id,
       mapping.permission_code,
       'DENY',
       legacy.created_by,
       current_timestamp
from user_permission_override legacy
join (
  select 'warehouse.central.read' legacy_code, 'warehouse.read' permission_code
  union all select 'warehouse.central.manage', 'warehouse.purchase'
  union all select 'warehouse.central.manage', 'warehouse.transfer.request'
  union all select 'warehouse.central.manage', 'warehouse.transfer.approve'
  union all select 'warehouse.central.manage', 'warehouse.transfer.ship'
  union all select 'warehouse.central.manage', 'warehouse.transfer.receive'
  union all select 'warehouse.central.manage', 'warehouse.requisition.process'
  union all select 'warehouse.central.manage', 'warehouse.configure'
  union all select 'warehouse.requisition.review', 'warehouse.requisition.process'
) mapping on mapping.legacy_code = legacy.permission_code
where legacy.effect = 'DENY'
  and not exists (
    select 1 from user_permission_override existing
    where existing.tenant_id = legacy.tenant_id
      and existing.user_id = legacy.user_id
      and existing.permission_code = mapping.permission_code
  );

insert into role_permission(tenant_id, role_code, permission_code, created_at)
select tenant.id, 'WAREHOUSE', permission.permission_code, current_timestamp
from tenant
join (
  select 'warehouse.read' permission_code
  union all select 'warehouse.purchase'
  union all select 'warehouse.transfer.request'
  union all select 'warehouse.transfer.approve'
  union all select 'warehouse.transfer.ship'
  union all select 'warehouse.transfer.receive'
  union all select 'warehouse.requisition.process'
  union all select 'warehouse.configure'
) permission on 1 = 1
where not exists (
  select 1 from role_permission existing
  where existing.tenant_id = tenant.id
    and existing.role_code = 'WAREHOUSE'
    and existing.permission_code = permission.permission_code
);

insert into user_data_scope(
  tenant_id, user_id, domain_code, scope_type, scope_value_json, created_by, created_at
)
select account.tenant_id,
       account.id,
       'WAREHOUSE',
       'WAREHOUSE_LIST',
       concat('["', central.id, '","', regional.id, '"]'),
       null,
       current_timestamp
from auth_user account
join warehouse_facility central
  on central.tenant_id = account.tenant_id and central.code = 'JZ-CENTRAL'
join warehouse_facility regional
  on regional.tenant_id = account.tenant_id and regional.code = 'SD-REGIONAL'
where upper(account.role) = 'WAREHOUSE'
  and not exists (
    select 1 from user_data_scope existing
    where existing.tenant_id = account.tenant_id
      and existing.user_id = account.id
      and existing.domain_code = 'WAREHOUSE'
  );

update user_data_scope scope
set scope_type = 'WAREHOUSE_LIST',
    scope_value_json = (
      select concat('["', central.id, '","', regional.id, '"]')
      from warehouse_facility central
      join warehouse_facility regional
        on regional.tenant_id = central.tenant_id and regional.code = 'SD-REGIONAL'
      where central.tenant_id = scope.tenant_id and central.code = 'JZ-CENTRAL'
    ),
    updated_at = current_timestamp
where scope.domain_code = 'WAREHOUSE'
  and exists (
    select 1 from auth_user account
    where account.tenant_id = scope.tenant_id
      and account.id = scope.user_id
      and upper(account.role) = 'WAREHOUSE'
  );

update auth_user
set permission_version = permission_version + 1,
    updated_at = current_timestamp
where upper(role) in ('BOSS', 'WAREHOUSE');

delete from auth_token token
where exists (
  select 1 from auth_user account
  where account.tenant_id = token.tenant_id
    and account.id = token.user_id
    and upper(account.role) in ('BOSS', 'WAREHOUSE')
);
