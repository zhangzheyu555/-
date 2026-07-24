-- H2 verification equivalent of MySQL V100.
insert into role_permission(tenant_id, role_code, permission_code, created_at)
select tenant.id, 'SUPERVISOR', required.permission_code, current_timestamp
from tenant
cross join (
  select 'daily_loss.read' permission_code
  union all select 'daily_loss.review'
  union all select 'daily_loss.export'
) required
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

delete from auth_token
where user_id in (
  select id from auth_user where upper(role) = 'SUPERVISOR'
);
