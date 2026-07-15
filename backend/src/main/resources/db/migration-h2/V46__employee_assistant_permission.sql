-- 与 MySQL V46 语义一致：仅授权本项目已有业务角色，不创建或授权 EMPLOYEE 兼容角色。
insert into permission_catalog(
  permission_code, module_code, permission_name, description, risk_level, enabled, sort_order
)
select 'employee_assistant.use', 'EMPLOYEE_ASSISTANT', '使用员工服务助手',
       '使用本地适配的员工常见问题与售后话术助手，不上传经营、客户或附件数据。', 'MEDIUM', 1, 76
where not exists (
  select 1 from permission_catalog where permission_code = 'employee_assistant.use'
);

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

update auth_user
set permission_version = permission_version + 1,
    updated_at = current_timestamp
where upper(role) in ('BOSS', 'FINANCE', 'WAREHOUSE', 'STORE_MANAGER', 'OPERATIONS');

delete from auth_token token
where exists (
  select 1
  from auth_user account
  where account.tenant_id = token.tenant_id
    and account.id = token.user_id
    and upper(account.role) in ('BOSS', 'FINANCE', 'WAREHOUSE', 'STORE_MANAGER', 'OPERATIONS')
);
