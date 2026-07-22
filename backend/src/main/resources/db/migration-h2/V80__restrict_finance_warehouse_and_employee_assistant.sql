-- H2 verification equivalent of MySQL V82.

delete from role_permission
where upper(role_code) = 'FINANCE'
  and permission_code in ('warehouse.read', 'employee_assistant.use');

delete from user_permission_override permission_override
where permission_override.effect = 'ALLOW'
  and (
    permission_override.permission_code like 'warehouse.%'
    or permission_override.permission_code = 'employee_assistant.use'
  )
  and exists (
    select 1
    from auth_user account
    where account.tenant_id = permission_override.tenant_id
      and account.id = permission_override.user_id
      and upper(account.role) = 'FINANCE'
  );

update auth_user
set permission_version = permission_version + 1,
    updated_at = current_timestamp
where upper(role) = 'FINANCE';

delete from auth_token
where user_id in (
  select id from auth_user where upper(role) = 'FINANCE'
);
