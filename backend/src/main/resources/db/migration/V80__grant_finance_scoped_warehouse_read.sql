-- FINANCE may inspect explicitly authorized warehouse procurement, inbound records and documents.
-- This is deliberately read-only: no purchase, transfer, requisition-processing or warehouse
-- configuration permission is granted here. Per-user WAREHOUSE data scope remains mandatory.

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

-- The template change must take effect for already-issued finance sessions.
update auth_user
set permission_version = permission_version + 1,
    updated_at = current_timestamp
where upper(role) = 'FINANCE';

delete token
from auth_token token
join auth_user account
  on account.tenant_id = token.tenant_id
 and account.id = token.user_id
where upper(account.role) = 'FINANCE';
