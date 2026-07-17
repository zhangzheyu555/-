-- Repair databases where the multi-warehouse schema exists but the WAREHOUSE
-- role template still only has legacy warehouse.central.* permissions.

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
on duplicate key update
  module_code = values(module_code),
  permission_name = values(permission_name),
  description = values(description),
  risk_level = values(risk_level),
  enabled = values(enabled),
  sort_order = values(sort_order),
  updated_at = current_timestamp;

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

update auth_user
set permission_version = permission_version + 1,
    updated_at = current_timestamp
where upper(role) in ('BOSS', 'WAREHOUSE');

delete from auth_token
where exists (
  select 1
  from auth_user account
  where account.tenant_id = auth_token.tenant_id
    and account.id = auth_token.user_id
    and upper(account.role) in ('BOSS', 'WAREHOUSE')
);
