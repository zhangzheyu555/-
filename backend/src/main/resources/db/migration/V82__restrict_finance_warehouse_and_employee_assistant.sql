-- Finance must remain focused on finance workflows. Warehouse-center and employee-assistant
-- access belong to other roles; remove both template grants and any historical personal grants.

delete from role_permission
where upper(role_code) = 'FINANCE'
  and permission_code in ('warehouse.read', 'employee_assistant.use');

delete permission_override
from user_permission_override permission_override
join auth_user account
  on account.tenant_id = permission_override.tenant_id
 and account.id = permission_override.user_id
where upper(account.role) = 'FINANCE'
  and permission_override.effect = 'ALLOW'
  and (
    permission_override.permission_code like 'warehouse.%'
    or permission_override.permission_code = 'employee_assistant.use'
  );

-- Effective permissions are versioned into login sessions, so revoke existing finance sessions.
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
