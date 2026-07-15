-- 与 MySQL V48 语义一致：普通员工只新增员工服务助手使用权。
insert into role_permission(tenant_id, role_code, permission_code, created_at)
select tenant.id, 'EMPLOYEE', 'employee_assistant.use', current_timestamp
from tenant
where not exists (
  select 1
  from role_permission existing
  where existing.tenant_id = tenant.id
    and existing.role_code = 'EMPLOYEE'
    and existing.permission_code = 'employee_assistant.use'
);

update auth_user
set permission_version = permission_version + 1,
    updated_at = current_timestamp
where upper(role) = 'EMPLOYEE';

delete from auth_token token
where exists (
  select 1
  from auth_user account
  where account.tenant_id = token.tenant_id
    and account.id = token.user_id
    and upper(account.role) = 'EMPLOYEE'
);
