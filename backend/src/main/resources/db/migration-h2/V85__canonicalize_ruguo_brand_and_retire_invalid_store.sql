-- Canonicalize the brand name while retaining compatibility in application parsers.
update brand
set name = replace(name, '茹果', '茹菓'),
    updated_at = current_timestamp
where name like '%茹果%';

update employee
set brand_name = replace(brand_name, '茹果', '茹菓'),
    updated_at = current_timestamp
where brand_name like '%茹果%';

update inspection_record
set brand = replace(brand, '茹果', '茹菓'),
    updated_at = current_timestamp
where brand like '%茹果%';

update employee
set remark = replace(remark, '茹果', '茹菓'),
    updated_at = current_timestamp
where remark like '%茹果%';

update profit_entry
set note = replace(note, '茹果', '茹菓'),
    updated_at = current_timestamp
where note like '%茹果%';

-- rg1 was an invalid demo store. It has linked historical records, so retire it
-- instead of deleting those records. The active-store selectors hide retired stores.
update store_branch
set status = '停用',
    updated_at = current_timestamp
where id = 'rg1'
  and code = 'RG001'
  and name = '保利店'
  and exists (
    select 1
    from brand b
    where b.id = store_branch.brand_id
      and b.tenant_id = store_branch.tenant_id
      and upper(b.code) = 'RG'
  );
