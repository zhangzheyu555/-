-- H2 verification equivalent of MySQL V97.

insert into operation_log(
  tenant_id, operator_id, operator_name, action, target_type, target_id, store_id, month,
  before_json, after_json, reason, created_at
)
select account.tenant_id, null, '系统迁移', '修复督导门店范围', 'auth_user',
       cast(account.id as varchar(120)), null, null,
       '{"scope":"NONE_OR_PARTIAL"}',
       '{"scope":"ALL_EXISTING_STORES","domains":["STORE","WAREHOUSE","INSPECTION","EXAM","PLATFORM"]}',
       'V97 修复督导角色合并后未同步全部门店数据范围的问题', current_timestamp
from auth_user account
where upper(account.role) = 'SUPERVISOR';

insert into user_store_scope(tenant_id, user_id, store_id, created_at)
select account.tenant_id, account.id, branch.id, current_timestamp
from auth_user account
join store_branch branch on branch.tenant_id = account.tenant_id
where upper(account.role) = 'SUPERVISOR'
  and not exists (
    select 1
    from user_store_scope existing
    where existing.tenant_id = account.tenant_id
      and existing.user_id = account.id
      and existing.store_id = branch.id
  );

delete from user_data_scope scope_row
where upper(scope_row.domain_code) in ('STORE', 'WAREHOUSE', 'INSPECTION', 'EXAM', 'PLATFORM')
  and exists (
    select 1
    from auth_user account
    where account.tenant_id = scope_row.tenant_id
      and account.id = scope_row.user_id
      and upper(account.role) = 'SUPERVISOR'
  );

insert into user_data_scope(
  tenant_id, user_id, domain_code, scope_type, scope_value_json, created_by, created_at, updated_at
)
select account.tenant_id, account.id, domain.domain_code, 'STORE_LIST', assigned.scope_value_json,
       null, current_timestamp, current_timestamp
from auth_user account
join (
  select scope.tenant_id, scope.user_id, json_arrayagg(scope.store_id) scope_value_json
  from user_store_scope scope
  join store_branch branch
    on branch.tenant_id = scope.tenant_id
   and branch.id = scope.store_id
  group by scope.tenant_id, scope.user_id
) assigned
  on assigned.tenant_id = account.tenant_id
 and assigned.user_id = account.id
join (
  select 'STORE' domain_code
  union all select 'WAREHOUSE'
  union all select 'INSPECTION'
  union all select 'EXAM'
  union all select 'PLATFORM'
) domain on 1 = 1
where upper(account.role) = 'SUPERVISOR';

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
