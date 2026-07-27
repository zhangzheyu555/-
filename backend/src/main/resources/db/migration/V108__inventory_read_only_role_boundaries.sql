-- 财务、督导、仓库统一收敛为店铺盘存只读角色。
-- 清理旧 OPERATIONS 模板曾带给督导的 manage/review，避免会话权限继续展示写入口。
delete from role_permission
where upper(role_code) in ('FINANCE', 'SUPERVISOR', 'WAREHOUSE', 'OPERATIONS', 'OPS')
  and permission_code in ('inventory.manage', 'inventory.review');

insert into role_permission(tenant_id, role_code, permission_code, created_at)
select tenant.id, template.role_code, 'inventory.read', current_timestamp
from tenant
join (
  select 'FINANCE' role_code
  union all select 'SUPERVISOR'
  union all select 'WAREHOUSE'
) template on 1 = 1
where not exists (
  select 1
  from role_permission existing
  where existing.tenant_id = tenant.id
    and upper(existing.role_code) = template.role_code
    and existing.permission_code = 'inventory.read'
);

-- 手工遗留的个人 ALLOW 也不能让只读角色重新获得写权限。
delete permission_override
from user_permission_override permission_override
join auth_user account
  on account.tenant_id = permission_override.tenant_id
 and account.id = permission_override.user_id
where upper(account.role) in ('FINANCE', 'SUPERVISOR', 'WAREHOUSE', 'OPERATIONS', 'OPS')
  and permission_override.effect = 'ALLOW'
  and permission_override.permission_code in ('inventory.manage', 'inventory.review');

-- 模板变化后立即使三类只读账号的旧会话失效。
update auth_user
set permission_version = permission_version + 1,
    updated_at = current_timestamp
where upper(role) in ('FINANCE', 'SUPERVISOR', 'WAREHOUSE', 'OPERATIONS', 'OPS');

delete token
from auth_token token
join auth_user account
  on account.tenant_id = token.tenant_id
 and account.id = token.user_id
where upper(account.role) in ('FINANCE', 'SUPERVISOR', 'WAREHOUSE', 'OPERATIONS', 'OPS');
