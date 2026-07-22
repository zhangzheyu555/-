-- Supervisors own daily-loss review/export and operations workflows, but must not access the
-- warehouse center, employee profiles, or the employee-service assistant. Remove historical role
-- grants and personal ALLOW overrides, then invalidate existing sessions.

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

delete permission_override
from user_permission_override permission_override
join auth_user account
  on account.tenant_id = permission_override.tenant_id
 and account.id = permission_override.user_id
where upper(account.role) = 'SUPERVISOR'
  and permission_override.effect = 'ALLOW'
  and (
    permission_override.permission_code like 'warehouse.%'
    or permission_override.permission_code in (
      'employee.read',
      'employee.manage',
      'employee_assistant.use',
      'employee_assistant.knowledge_manage',
      'employee_assistant.handoff_manage'
    )
  );

delete scope
from user_data_scope scope
join auth_user account
  on account.tenant_id = scope.tenant_id
 and account.id = scope.user_id
where upper(account.role) = 'SUPERVISOR'
  and upper(scope.domain_code) = 'WAREHOUSE';

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
