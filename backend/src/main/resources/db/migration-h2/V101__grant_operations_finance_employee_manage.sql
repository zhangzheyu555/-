-- H2 verification equivalent of MySQL V101.
insert into role_permission(tenant_id, role_code, permission_code, created_at)
select tenant.id, role.role_code, 'employee.manage', current_timestamp
from tenant
cross join (
  select 'SUPERVISOR' role_code
  union all select 'FINANCE'
) role
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

delete from auth_token
where user_id in (
  select id
  from auth_user
  where upper(role) in ('SUPERVISOR', 'OPERATIONS', 'OPS', 'FINANCE')
);
