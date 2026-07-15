-- 独立员工服务助手权限；仅授权本项目已有业务角色，不创建或授权 EMPLOYEE 兼容角色。
insert into permission_catalog(
  permission_code, module_code, permission_name, description, risk_level, enabled, sort_order
)
values (
  'employee_assistant.use', 'EMPLOYEE_ASSISTANT', '使用员工服务助手',
  '使用本地适配的员工常见问题与售后话术助手，不上传经营、客户或附件数据。', 'MEDIUM', 1, 76
)
on duplicate key update
  module_code = values(module_code),
  permission_name = values(permission_name),
  description = values(description),
  risk_level = values(risk_level),
  enabled = values(enabled),
  sort_order = values(sort_order);

insert into role_permission(tenant_id, role_code, permission_code, created_at)
select tenant.id, template.role_code, 'employee_assistant.use', current_timestamp
from tenant
join (
  select 'FINANCE' role_code
  union all select 'WAREHOUSE'
  union all select 'STORE_MANAGER'
  union all select 'OPERATIONS'
) template on 1 = 1
where not exists (
  select 1
  from role_permission existing
  where existing.tenant_id = tenant.id
    and existing.role_code = template.role_code
    and existing.permission_code = 'employee_assistant.use'
);

-- Effective permissions are versioned into login sessions.  Re-login is required for only the
-- roles whose template changed; BOSS also refreshes because it owns every catalog permission.
update auth_user
set permission_version = permission_version + 1,
    updated_at = current_timestamp
where upper(role) in ('BOSS', 'FINANCE', 'WAREHOUSE', 'STORE_MANAGER', 'OPERATIONS');

delete token
from auth_token token
join auth_user account
  on account.tenant_id = token.tenant_id
 and account.id = token.user_id
where upper(account.role) in ('BOSS', 'FINANCE', 'WAREHOUSE', 'STORE_MANAGER', 'OPERATIONS');
