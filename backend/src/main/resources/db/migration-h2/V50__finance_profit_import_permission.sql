-- 与 MySQL V50 语义一致：月度经营数据导入仅授权财务模板，BOSS 由最高角色权限集获得。
insert into permission_catalog(
  permission_code, module_code, permission_name, description, risk_level, enabled, sort_order
)
select 'finance.profit.import', 'FINANCE', '导入月度经营数据',
       '导入 Excel 或 CSV 月度经营汇总，可能覆盖指定门店和月份的完整经营记录，仅限财务或老板。', 'HIGH', 1, 215
where not exists (
  select 1 from permission_catalog where permission_code = 'finance.profit.import'
);

insert into role_permission(tenant_id, role_code, permission_code, created_at)
select tenant.id, 'FINANCE', 'finance.profit.import', current_timestamp
from tenant
where not exists (
  select 1
  from role_permission existing
  where existing.tenant_id = tenant.id
    and existing.role_code = 'FINANCE'
    and existing.permission_code = 'finance.profit.import'
);

update auth_user
set permission_version = permission_version + 1,
    updated_at = current_timestamp
where upper(role) in ('BOSS', 'FINANCE', 'ADMIN', 'OWNER');

delete from auth_token token
where exists (
  select 1
  from auth_user account
  where account.tenant_id = token.tenant_id
    and account.id = token.user_id
    and upper(account.role) in ('BOSS', 'FINANCE', 'ADMIN', 'OWNER')
);
