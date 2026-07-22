-- H2 verification equivalent of MySQL V83.

delete from role_permission
where upper(role_code) = 'FINANCE'
  and permission_code like 'daily_loss.%';

delete from user_permission_override permission_override
where permission_override.effect = 'ALLOW'
  and permission_override.permission_code like 'daily_loss.%'
  and exists (
    select 1
    from auth_user account
    where account.tenant_id = permission_override.tenant_id
      and account.id = permission_override.user_id
      and upper(account.role) = 'FINANCE'
  );

insert into role_permission(tenant_id, role_code, permission_code, created_at)
select tenant.id, 'SUPERVISOR', template.permission_code, current_timestamp
from tenant
join (
  select 'daily_loss.read' permission_code
  union all select 'daily_loss.review'
  union all select 'daily_loss.export'
) template on 1 = 1
where not exists (
  select 1
  from role_permission existing
  where existing.tenant_id = tenant.id
    and existing.role_code = 'SUPERVISOR'
    and existing.permission_code = template.permission_code
);

update permission_catalog
set description = case permission_code
      when 'daily_loss.read' then '仅限督导或老板查询权限范围内的每日报损。'
      when 'daily_loss.review' then '仅限督导或老板复核权限范围内的每日报损。'
      when 'daily_loss.export' then '仅限督导或老板按既有数据范围导出本月每日报损汇总和明细，不包含照片或附件。'
      else description
    end,
    updated_at = current_timestamp
where permission_code in ('daily_loss.read', 'daily_loss.review', 'daily_loss.export');

update auth_user
set permission_version = permission_version + 1,
    updated_at = current_timestamp
where upper(role) in ('FINANCE', 'SUPERVISOR');

delete from auth_token
where user_id in (
  select id from auth_user where upper(role) in ('FINANCE', 'SUPERVISOR')
);
