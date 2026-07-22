-- H2 verification equivalent of MySQL V85.

delete from role_permission
where upper(role_code) = 'SUPERVISOR'
  and (
    permission_code like 'warehouse.%'
    or permission_code in (
      'employee.read',
      'employee.manage',
      'employee_assistant.use',
      'employee_assistant.knowledge_manage',
      'employee_assistant.handoff_manage'
    )
  );

delete from user_permission_override permission_override
where permission_override.effect = 'ALLOW'
  and (
    permission_override.permission_code like 'warehouse.%'
    or permission_override.permission_code in (
      'employee.read',
      'employee.manage',
      'employee_assistant.use',
      'employee_assistant.knowledge_manage',
      'employee_assistant.handoff_manage'
    )
  )
  and exists (
    select 1
    from auth_user account
    where account.tenant_id = permission_override.tenant_id
      and account.id = permission_override.user_id
      and upper(account.role) = 'SUPERVISOR'
  );

delete from user_data_scope scope
where upper(scope.domain_code) = 'WAREHOUSE'
  and exists (
    select 1
    from auth_user account
    where account.tenant_id = scope.tenant_id
      and account.id = scope.user_id
      and upper(account.role) = 'SUPERVISOR'
  );

update auth_user
set permission_version = permission_version + 1,
    updated_at = current_timestamp
where upper(role) = 'SUPERVISOR';

delete from auth_token
where user_id in (
  select id from auth_user where upper(role) = 'SUPERVISOR'
);
