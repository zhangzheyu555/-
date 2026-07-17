-- Split SUPERVISOR from OPERATIONS and repair warehouse return attachments.

update warehouse_attachment
set store_id = coalesce(nullif(trim(store_id), ''), (
      select return_order.return_store_id
      from warehouse_return_order return_order
      where return_order.tenant_id = warehouse_attachment.tenant_id
        and return_order.id = warehouse_attachment.business_id
    )),
    business_type = 'WAREHOUSE_RETURN'
where business_type in ('RETURN_ORDER', 'WAREHOUSE_RETURN')
  and exists (
    select 1
    from warehouse_return_order return_order
    where return_order.tenant_id = warehouse_attachment.tenant_id
      and return_order.id = warehouse_attachment.business_id
  )
  and (
    business_type = 'RETURN_ORDER'
    or store_id is null
    or trim(store_id) = ''
  );

delete from role_permission
where upper(role_code) = 'SUPERVISOR';

insert into role_permission(tenant_id, role_code, permission_code, created_at)
select tenant.id, 'SUPERVISOR', template.permission_code, current_timestamp
from tenant
cross join (values
  ('store.read'),
  ('inspection.read'),
  ('inspection.manage'),
  ('attachment.read'),
  ('attachment.write'),
  ('todo.read'),
  ('todo.transition'),
  ('assistant.use'),
  ('employee_assistant.use'),
  ('employee_assistant.handoff_manage')
) template(permission_code)
join permission_catalog catalog
  on catalog.permission_code = template.permission_code
 and catalog.enabled = 1;

delete from user_permission_override
where exists (
    select 1
    from auth_user account
    where account.tenant_id = user_permission_override.tenant_id
      and account.id = user_permission_override.user_id
      and upper(account.role) = 'SUPERVISOR'
  )
  and effect = 'ALLOW'
  and permission_code in (
    'operations.dashboard.read',
    'platform.read',
    'platform.manage',
    'inventory.manage',
    'inventory.review',
    'exam.manage',
    'exam.report'
  );

update user_data_scope
set scope_type = 'NONE',
    scope_value_json = null,
    updated_at = current_timestamp
where exists (
    select 1
    from auth_user account
    where account.tenant_id = user_data_scope.tenant_id
      and account.id = user_data_scope.user_id
      and upper(account.role) = 'SUPERVISOR'
  )
  and (
    domain_code not in ('STORE', 'INSPECTION')
    or scope_type <> 'STORE_LIST'
  );

update auth_user
set permission_version = permission_version + 1,
    updated_at = current_timestamp
where upper(role) = 'SUPERVISOR';

delete from auth_token
where exists (
  select 1
  from auth_user account
  where account.tenant_id = auth_token.tenant_id
    and account.id = auth_token.user_id
    and upper(account.role) = 'SUPERVISOR'
);
