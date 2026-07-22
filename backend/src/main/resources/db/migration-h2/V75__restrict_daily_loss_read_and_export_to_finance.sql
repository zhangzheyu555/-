-- H2 verification equivalent of MySQL V77. H2 has its own compatibility chain.
-- BOSS uses the highest-role boundary; FINANCE is the sole role template for query/export.

insert into permission_catalog(
  permission_code, module_code, permission_name, description, risk_level, enabled, sort_order
)
select 'daily_loss.export', 'DAILY_LOSS', '导出本月报损 Excel',
       '仅限财务或老板按既有数据范围导出本月每日报损汇总和明细，不包含照片或附件。', 'HIGH', 1, 618
where not exists (
  select 1 from permission_catalog where permission_code = 'daily_loss.export'
);

delete from role_permission
where permission_code in ('daily_loss.read', 'daily_loss.export')
  and upper(role_code) in ('STORE_MANAGER', 'SUPERVISOR', 'WAREHOUSE', 'EMPLOYEE', 'OPERATIONS', 'OPS');

insert into role_permission(tenant_id, role_code, permission_code, created_at)
select tenant.id, 'FINANCE', template.permission_code, current_timestamp
from tenant
join (
  select 'daily_loss.read' as permission_code
  union all select 'daily_loss.export'
) template on 1 = 1
where not exists (
  select 1
  from role_permission existing
  where existing.tenant_id = tenant.id
    and existing.role_code = 'FINANCE'
    and existing.permission_code = template.permission_code
);

update permission_catalog
set description = case permission_code
      when 'daily_loss.read' then '仅限财务或老板查询权限范围内的每日报损。'
      when 'daily_loss.export' then '仅限财务或老板按既有数据范围导出本月每日报损汇总和明细，不包含照片或附件。'
      else description
    end,
    updated_at = current_timestamp
where permission_code in ('daily_loss.read', 'daily_loss.export');

update auth_user
set permission_version = permission_version + 1,
    updated_at = current_timestamp
where upper(role) in ('STORE_MANAGER', 'SUPERVISOR', 'WAREHOUSE', 'EMPLOYEE', 'OPERATIONS', 'OPS');

delete from auth_token
where user_id in (
  select id from auth_user
  where upper(role) in ('STORE_MANAGER', 'SUPERVISOR', 'WAREHOUSE', 'EMPLOYEE', 'OPERATIONS', 'OPS')
);
