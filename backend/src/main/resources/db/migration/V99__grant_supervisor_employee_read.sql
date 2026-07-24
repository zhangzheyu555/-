-- V99：督导与财务一致，只读查看其既有门店数据范围内的员工档案。
-- 不授予 employee.manage，新增、修改、离职、开号和导入仍由有维护权限的角色处理。
insert into role_permission(tenant_id, role_code, permission_code, created_at)
select tenant.id, 'SUPERVISOR', 'employee.read', current_timestamp
from tenant
where not exists (
  select 1
  from role_permission existing
  where existing.tenant_id = tenant.id
    and upper(existing.role_code) = 'SUPERVISOR'
    and existing.permission_code = 'employee.read'
);

update auth_user
set permission_version = permission_version + 1,
    updated_at = current_timestamp
where upper(role) = 'SUPERVISOR';

delete token
from auth_token token
join auth_user account
  on account.tenant_id = token.tenant_id
 and account.id = token.user_id
where upper(account.role) = 'SUPERVISOR';
