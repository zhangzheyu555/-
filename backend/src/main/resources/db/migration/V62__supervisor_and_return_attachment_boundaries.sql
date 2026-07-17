-- Split SUPERVISOR from OPERATIONS and repair warehouse return attachments.

update warehouse_attachment attachment
join warehouse_return_order return_order
  on return_order.tenant_id = attachment.tenant_id
 and return_order.id = attachment.business_id
set attachment.store_id = coalesce(nullif(trim(attachment.store_id), ''), return_order.return_store_id),
    attachment.business_type = 'WAREHOUSE_RETURN'
where attachment.business_type in ('RETURN_ORDER', 'WAREHOUSE_RETURN')
  and (
    attachment.business_type = 'RETURN_ORDER'
    or attachment.store_id is null
    or trim(attachment.store_id) = ''
  );

delete from role_permission
where upper(role_code) = 'SUPERVISOR';

insert into role_permission(tenant_id, role_code, permission_code, created_at)
select tenant.id, 'SUPERVISOR', template.permission_code, current_timestamp
from tenant
join (
  select 'store.read' permission_code
  union all select 'inspection.read'
  union all select 'inspection.manage'
  union all select 'attachment.read'
  union all select 'attachment.write'
  union all select 'todo.read'
  union all select 'todo.transition'
  union all select 'assistant.use'
  union all select 'employee_assistant.use'
  union all select 'employee_assistant.handoff_manage'
) template on 1 = 1
join permission_catalog catalog
  on catalog.permission_code = template.permission_code
 and catalog.enabled = 1;

delete override_row
from user_permission_override override_row
join auth_user account
  on account.tenant_id = override_row.tenant_id
 and account.id = override_row.user_id
where upper(account.role) = 'SUPERVISOR'
  and override_row.effect = 'ALLOW'
  and override_row.permission_code in (
    'operations.dashboard.read',
    'platform.read',
    'platform.manage',
    'inventory.manage',
    'inventory.review',
    'exam.manage',
    'exam.report'
  );

update user_data_scope scope_row
join auth_user account
  on account.tenant_id = scope_row.tenant_id
 and account.id = scope_row.user_id
set scope_row.scope_type = 'NONE',
    scope_row.scope_value_json = null,
    scope_row.updated_at = current_timestamp
where upper(account.role) = 'SUPERVISOR'
  and (
    scope_row.domain_code not in ('STORE', 'INSPECTION')
    or scope_row.scope_type <> 'STORE_LIST'
  );

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
