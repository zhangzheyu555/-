-- Store managers own daily-loss entry and may query records in their existing store data scope.
-- Supervisors retain review/export responsibilities; finance remains excluded.

insert into role_permission(tenant_id, role_code, permission_code, created_at)
select tenant.id, 'STORE_MANAGER', 'daily_loss.read', current_timestamp
from tenant
where not exists (
  select 1
  from role_permission existing
  where existing.tenant_id = tenant.id
    and upper(existing.role_code) = 'STORE_MANAGER'
    and existing.permission_code = 'daily_loss.read'
);

update permission_catalog
set description = '店长查看所属门店报损，督导或老板查看授权范围内报损。',
    updated_at = current_timestamp
where permission_code = 'daily_loss.read';

update auth_user
set permission_version = permission_version + 1,
    updated_at = current_timestamp
where upper(role) = 'STORE_MANAGER';

delete token
from auth_token token
join auth_user account
  on account.tenant_id = token.tenant_id
 and account.id = token.user_id
where upper(account.role) = 'STORE_MANAGER';
