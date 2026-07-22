-- The store operating assistant is not part of the supervisor workspace. Remove historical role
-- grants and mistaken personal ALLOW overrides, then invalidate existing supervisor sessions.

delete from role_permission
where upper(role_code) = 'SUPERVISOR'
  and permission_code = 'assistant.use';

delete permission_override
from user_permission_override permission_override
join auth_user account
  on account.tenant_id = permission_override.tenant_id
 and account.id = permission_override.user_id
where upper(account.role) = 'SUPERVISOR'
  and permission_override.effect = 'ALLOW'
  and permission_override.permission_code = 'assistant.use';

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
