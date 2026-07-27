-- H2 verification equivalent of MySQL V105.
-- Finance, supervisor and warehouse receive inventory.read only.
delete from role_permission
where upper(role_code) in ('FINANCE', 'SUPERVISOR', 'WAREHOUSE', 'OPERATIONS', 'OPS')
  and permission_code in ('inventory.manage', 'inventory.review');

insert into role_permission(tenant_id, role_code, permission_code, created_at)
select tenant.id, template.role_code, 'inventory.read', current_timestamp
from tenant
cross join (values ('FINANCE'), ('SUPERVISOR'), ('WAREHOUSE')) template(role_code)
where not exists (
  select 1
  from role_permission existing
  where existing.tenant_id = tenant.id
    and upper(existing.role_code) = template.role_code
    and existing.permission_code = 'inventory.read'
);

delete from user_permission_override permission_override
where permission_override.effect = 'ALLOW'
  and permission_override.permission_code in ('inventory.manage', 'inventory.review')
  and exists (
    select 1
    from auth_user account
    where account.tenant_id = permission_override.tenant_id
      and account.id = permission_override.user_id
      and upper(account.role) in ('FINANCE', 'SUPERVISOR', 'WAREHOUSE', 'OPERATIONS', 'OPS')
  );

update auth_user
set permission_version = permission_version + 1,
    updated_at = current_timestamp
where upper(role) in ('FINANCE', 'SUPERVISOR', 'WAREHOUSE', 'OPERATIONS', 'OPS');

delete from auth_token
where user_id in (
  select id
  from auth_user
  where upper(role) in ('FINANCE', 'SUPERVISOR', 'WAREHOUSE', 'OPERATIONS', 'OPS')
);
