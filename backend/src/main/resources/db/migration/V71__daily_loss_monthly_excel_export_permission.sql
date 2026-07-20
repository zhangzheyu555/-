-- 月度每日报损 Excel 导出：仅授权角色按其既有数据范围导出。
insert into permission_catalog(
  permission_code, module_code, permission_name, description, risk_level, enabled, sort_order
)
values (
  'daily_loss.export', 'DAILY_LOSS', '导出本月报损 Excel',
  '按当前账号的门店数据范围导出本月每日报损汇总和明细，不包含照片或附件。',
  'HIGH', 1, 618
)
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
  select 'STORE_MANAGER' as role_code, 'daily_loss.export' as permission_code
  union all select 'SUPERVISOR', 'daily_loss.export'
  union all select 'FINANCE', 'daily_loss.export'
) template on 1 = 1
where not exists (
  select 1
  from role_permission existing
  where existing.tenant_id = tenant.id
    and existing.role_code = template.role_code
    and existing.permission_code = template.permission_code
);

update auth_user
set permission_version = permission_version + 1,
    updated_at = current_timestamp
where upper(role) in ('FINANCE', 'SUPERVISOR', 'STORE_MANAGER');

delete token
from auth_token token
join auth_user account
  on account.tenant_id = token.tenant_id
 and account.id = token.user_id
where upper(account.role) in ('FINANCE', 'SUPERVISOR', 'STORE_MANAGER');
