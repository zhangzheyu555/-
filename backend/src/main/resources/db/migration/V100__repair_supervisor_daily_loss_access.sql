-- V100: repair tenants where an older V83 only retained daily_loss.review for supervisors.
insert into role_permission(tenant_id, role_code, permission_code, created_at)
select tenant.id, 'SUPERVISOR', required.permission_code, current_timestamp
from tenant
join (
  select 'daily_loss.read' permission_code
  union all select 'daily_loss.review'
  union all select 'daily_loss.export'
) required on 1 = 1
where not exists (
  select 1
  from role_permission existing
  where existing.tenant_id = tenant.id
    and upper(existing.role_code) = 'SUPERVISOR'
    and existing.permission_code = required.permission_code
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
