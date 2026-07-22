-- H2 verification equivalent of MySQL V80. Finance receives only scoped warehouse read access;
-- write and warehouse-management permissions remain exclusive to the warehouse/BOSS boundary.

insert into role_permission(tenant_id, role_code, permission_code, created_at)
select tenant.id, 'FINANCE', 'warehouse.read', current_timestamp
from tenant
where not exists (
  select 1
  from role_permission existing
  where existing.tenant_id = tenant.id
    and existing.role_code = 'FINANCE'
    and existing.permission_code = 'warehouse.read'
);

update auth_user
set permission_version = permission_version + 1,
    updated_at = current_timestamp
where upper(role) = 'FINANCE';

delete from auth_token
where user_id in (
  select id from auth_user where upper(role) = 'FINANCE'
);
