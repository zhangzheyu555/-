-- V101：运营与财务可在各自门店数据范围内新增、查看、编辑和办理员工离职。
insert into role_permission(tenant_id, role_code, permission_code, created_at)
select tenant.id, role.role_code, 'employee.manage', current_timestamp
from tenant
join (
  select 'SUPERVISOR' role_code
  union all select 'FINANCE'
) role on 1 = 1
where not exists (
  select 1
  from role_permission existing
  where existing.tenant_id = tenant.id
    and upper(existing.role_code) = role.role_code
    and existing.permission_code = 'employee.manage'
);

update auth_user
set permission_version = permission_version + 1,
    updated_at = current_timestamp
where upper(role) in ('SUPERVISOR', 'OPERATIONS', 'OPS', 'FINANCE');

delete token
from auth_token token
join auth_user account
  on account.tenant_id = token.tenant_id
 and account.id = token.user_id
where upper(account.role) in ('SUPERVISOR', 'OPERATIONS', 'OPS', 'FINANCE');
