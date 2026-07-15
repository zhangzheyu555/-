-- 月度经营数据导入与店长手工本店录入分离：仅财务模板获得导入权限，BOSS 由最高角色权限集获得。
insert into permission_catalog(
  permission_code, module_code, permission_name, description, risk_level, enabled, sort_order
)
values (
  'finance.profit.import', 'FINANCE', '导入月度经营数据',
  '导入 Excel 或 CSV 月度经营汇总，可能覆盖指定门店和月份的完整经营记录，仅限财务或老板。', 'HIGH', 1, 215
)
on duplicate key update
  module_code = values(module_code),
  permission_name = values(permission_name),
  description = values(description),
  risk_level = values(risk_level),
  enabled = values(enabled),
  sort_order = values(sort_order);

insert into role_permission(tenant_id, role_code, permission_code, created_at)
select tenant.id, 'FINANCE', 'finance.profit.import', current_timestamp
from tenant
where not exists (
  select 1
  from role_permission existing
  where existing.tenant_id = tenant.id
    and existing.role_code = 'FINANCE'
    and existing.permission_code = 'finance.profit.import'
);

-- 登录令牌内含权限版本。财务和老板均会获得新的有效权限，因此必须重新登录。
update auth_user
set permission_version = permission_version + 1,
    updated_at = current_timestamp
where upper(role) in ('BOSS', 'FINANCE', 'ADMIN', 'OWNER');

delete token
from auth_token token
join auth_user account
  on account.tenant_id = token.tenant_id
 and account.id = token.user_id
where upper(account.role) in ('BOSS', 'FINANCE', 'ADMIN', 'OWNER');
