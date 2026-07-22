-- V77 / STORE-01：日报损查询和月度 Excel 导出仅限 FINANCE；BOSS 由最高角色边界获得权限。
-- 店长仍可提交、督导仍可复核，但两者不再拥有列表查询或导出模板权限。

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

-- 失效受影响非财务角色的旧会话；个人 ALLOW 覆盖不删除，但后端角色上限仍会拒绝。
update auth_user
set permission_version = permission_version + 1,
    updated_at = current_timestamp
where upper(role) in ('STORE_MANAGER', 'SUPERVISOR', 'WAREHOUSE', 'EMPLOYEE', 'OPERATIONS', 'OPS');

delete token
from auth_token token
join auth_user account
  on account.tenant_id = token.tenant_id
 and account.id = token.user_id
where upper(account.role) in ('STORE_MANAGER', 'SUPERVISOR', 'WAREHOUSE', 'EMPLOYEE', 'OPERATIONS', 'OPS');
