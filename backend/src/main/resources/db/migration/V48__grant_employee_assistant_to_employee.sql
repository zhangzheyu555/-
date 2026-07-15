-- 普通员工只新增员工服务助手使用权，不增加经营、财务、门店或管理权限。
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

-- 登录令牌承载权限快照；仅使普通员工重新登录以取得这项新增的最小权限。
update auth_user
set permission_version = permission_version + 1,
    updated_at = current_timestamp
where upper(role) = 'EMPLOYEE';

delete token
from auth_token token
join auth_user account
  on account.tenant_id = token.tenant_id
 and account.id = token.user_id
where upper(account.role) = 'EMPLOYEE';
