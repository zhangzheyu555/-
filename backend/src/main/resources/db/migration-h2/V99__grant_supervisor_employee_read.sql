-- H2 verification equivalent of MySQL V99.
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

delete from auth_token
where user_id in (
  select id from auth_user where upper(role) = 'SUPERVISOR'
);
