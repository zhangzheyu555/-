-- OPERATIONS/OPS is a historical compatibility input only. SUPERVISOR becomes the sole
-- formal role for the combined inspection and operations workspace. Historical operation logs
-- are intentionally left untouched.

insert into operation_log(
  tenant_id, operator_id, operator_name, action, target_type, target_id, store_id, month,
  before_json, after_json, reason, created_at
)
select account.tenant_id, null, '系统迁移', '角色迁移', 'auth_user', cast(account.id as char), account.store_id, null,
       concat('{"role":"', upper(account.role), '"}'),
       concat('{"role":"', case when upper(account.role) in ('ADMIN', 'OWNER') then 'BOSS' else 'SUPERVISOR' end, '"}'),
       'V72 角色归一：历史角色迁移为当前正式角色', current_timestamp
from auth_user account
where upper(account.role) in ('ADMIN', 'OWNER', 'OPS', 'OPERATIONS');

-- Preserve the union of old supervisor and operations template permissions without duplicates.
insert into role_permission(tenant_id, role_code, permission_code, created_at)
select source.tenant_id, 'SUPERVISOR', source.permission_code, current_timestamp
from role_permission source
where upper(source.role_code) in ('OPERATIONS', 'OPS')
  and not exists (
    select 1 from role_permission existing
    where existing.tenant_id = source.tenant_id
      and existing.role_code = 'SUPERVISOR'
      and existing.permission_code = source.permission_code
  );

-- Open work must remain actionable by the new formal role. Completed history is not rewritten.
update business_todo
set assignee_role = case when upper(assignee_role) in ('OPERATIONS', 'OPS') then 'SUPERVISOR' else assignee_role end,
    review_role = case when upper(review_role) in ('OPERATIONS', 'OPS') then 'SUPERVISOR' else review_role end,
    updated_at = current_timestamp
where status not in ('COMPLETED', 'REJECTED')
  and (upper(assignee_role) in ('OPERATIONS', 'OPS') or upper(review_role) in ('OPERATIONS', 'OPS'));

update todo_escalation
set source_role = 'SUPERVISOR'
where status <> 'RESOLVED'
  and upper(source_role) in ('OPERATIONS', 'OPS');

-- Template changes invalidate all supervisor sessions; legacy role changes invalidate their own
-- sessions before role values are normalized.
update auth_user
set permission_version = permission_version + 1,
    updated_at = current_timestamp
where upper(role) in ('SUPERVISOR', 'OPERATIONS', 'OPS', 'ADMIN', 'OWNER');

delete token
from auth_token token
join auth_user account
  on account.tenant_id = token.tenant_id
 and account.id = token.user_id
where upper(account.role) in ('SUPERVISOR', 'OPERATIONS', 'OPS', 'ADMIN', 'OWNER');

update auth_user
set role = case
      when upper(role) in ('ADMIN', 'OWNER') then 'BOSS'
      when upper(role) in ('OPS', 'OPERATIONS') then 'SUPERVISOR'
      else upper(role)
    end,
    updated_at = current_timestamp
where upper(role) in ('ADMIN', 'OWNER', 'OPS', 'OPERATIONS');

delete from role_permission
where upper(role_code) in ('ADMIN', 'OWNER', 'OPS', 'OPERATIONS');
