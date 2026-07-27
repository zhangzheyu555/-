-- H2 verification equivalent of MySQL V109.20260727120000004.
alter table store_inventory_check
  add column reviewed_by_name varchar(120) null;
alter table store_inventory_check
  add column reviewed_by_role varchar(40) null;
alter table store_inventory_check
  alter column status set default 'SUBMITTED';

update store_inventory_check
set status = 'SUBMITTED',
    submitted_by = coalesce(submitted_by, created_by),
    updated_at = current_timestamp
where status = 'DRAFT';

update store_inventory_check inventory_check
set reviewed_by_name = coalesce(
      (
        select nullif(reviewer.display_name, '')
        from auth_user reviewer
        where reviewer.tenant_id = inventory_check.tenant_id
          and reviewer.id = inventory_check.reviewed_by
      ),
      case
        when inventory_check.reviewed_by is null then '历史审核记录'
        else concat('用户#', inventory_check.reviewed_by)
      end
    ),
    reviewed_by_role = coalesce(
      (
        select case upper(coalesce(reviewer.role, ''))
          when 'ADMIN' then 'BOSS'
          when 'OWNER' then 'BOSS'
          when 'OPS' then 'SUPERVISOR'
          when 'OPERATIONS' then 'SUPERVISOR'
          when '' then 'UNKNOWN'
          else upper(reviewer.role)
        end
        from auth_user reviewer
        where reviewer.tenant_id = inventory_check.tenant_id
          and reviewer.id = inventory_check.reviewed_by
      ),
      'UNKNOWN'
    )
where status = 'REVIEWED'
  and (reviewed_by_name is null or reviewed_by_role is null);

insert into role_permission(tenant_id, role_code, permission_code, created_at)
select tenant.id, reviewer_role.role_code, 'inventory.review', current_timestamp
from tenant
cross join (values ('FINANCE'), ('SUPERVISOR'), ('WAREHOUSE')) reviewer_role(role_code)
where not exists (
  select 1
  from role_permission existing
  where existing.tenant_id = tenant.id
    and upper(existing.role_code) = reviewer_role.role_code
    and existing.permission_code = 'inventory.review'
);

update auth_user
set permission_version = permission_version + 1,
    updated_at = current_timestamp
where upper(role) in ('FINANCE', 'SUPERVISOR', 'WAREHOUSE', 'OPERATIONS', 'OPS');

delete from auth_token
where user_id in (
  select id
  from auth_user
  where upper(role) in ('FINANCE', 'SUPERVISOR', 'WAREHOUSE', 'OPERATIONS', 'OPS')
);
