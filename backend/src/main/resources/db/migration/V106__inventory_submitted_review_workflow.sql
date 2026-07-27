-- 店铺盘存正式流程只有“已提交”和“已复核”两种状态。
-- 历史草稿安全提升为已提交；历史作废单保留作为归档，应用查询会明确排除它们。
alter table store_inventory_check
  add column reviewed_by_name varchar(120) null after reviewed_by,
  add column reviewed_by_role varchar(40) null after reviewed_by_name,
  modify column status varchar(40) not null default 'SUBMITTED';

update store_inventory_check
set status = 'SUBMITTED',
    submitted_by = coalesce(submitted_by, created_by),
    updated_at = current_timestamp
where status = 'DRAFT';

-- 为旧的已复核记录补齐负责人姓名与当时职务。找不到账号的历史行仍保留可识别快照。
update store_inventory_check inventory_check
left join auth_user reviewer
  on reviewer.tenant_id = inventory_check.tenant_id
 and reviewer.id = inventory_check.reviewed_by
set inventory_check.reviewed_by_name = coalesce(
      nullif(reviewer.display_name, ''),
      case
        when inventory_check.reviewed_by is null then '历史审核记录'
        else concat('用户#', inventory_check.reviewed_by)
      end
    ),
    inventory_check.reviewed_by_role = case upper(coalesce(reviewer.role, ''))
      when 'ADMIN' then 'BOSS'
      when 'OWNER' then 'BOSS'
      when 'OPS' then 'SUPERVISOR'
      when 'OPERATIONS' then 'SUPERVISOR'
      when '' then 'UNKNOWN'
      else upper(reviewer.role)
    end
where inventory_check.status = 'REVIEWED'
  and (
    inventory_check.reviewed_by_name is null
    or inventory_check.reviewed_by_role is null
  );

-- 老板拥有全权限；财务、督导、仓管获得盘存复核权限，店长仍只负责提交。
insert into role_permission(tenant_id, role_code, permission_code, created_at)
select tenant.id, reviewer_role.role_code, 'inventory.review', current_timestamp
from tenant
join (
  select 'FINANCE' role_code
  union all select 'SUPERVISOR'
  union all select 'WAREHOUSE'
) reviewer_role on 1 = 1
where not exists (
  select 1
  from role_permission existing
  where existing.tenant_id = tenant.id
    and upper(existing.role_code) = reviewer_role.role_code
    and existing.permission_code = 'inventory.review'
);

-- 权限模板变更后使相关旧会话失效，重新登录即可拿到复核权限。
update auth_user
set permission_version = permission_version + 1,
    updated_at = current_timestamp
where upper(role) in ('FINANCE', 'SUPERVISOR', 'WAREHOUSE', 'OPERATIONS', 'OPS');

delete token
from auth_token token
join auth_user account
  on account.tenant_id = token.tenant_id
 and account.id = token.user_id
where upper(account.role) in ('FINANCE', 'SUPERVISOR', 'WAREHOUSE', 'OPERATIONS', 'OPS');
