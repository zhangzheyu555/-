-- V64: align daily loss item configuration with warehouse item categories.
-- This maps configuration rows only; it does not import daily report business data.

alter table loss_item_config add column if not exists warehouse_category_id bigint null;

create index if not exists idx_loss_item_config_warehouse_category
  on loss_item_config(tenant_id, warehouse_category_id, active);

alter table loss_item_config add constraint if not exists fk_loss_item_config_warehouse_category
  foreign key (warehouse_category_id) references warehouse_item_category(id);

insert into warehouse_item_category(tenant_id, name, parent_id, sort_order, enabled, created_at)
select tenants.tenant_id, required_category.name, null, required_category.sort_order, true, current_timestamp
from (
  select distinct tenant_id from loss_item_config
) tenants
join (
  select '水果' as name, 30 as sort_order
  union all select '茶叶', 50
  union all select '奶制品', 70
  union all select '小料/配料', 80
) required_category on 1 = 1
left join warehouse_item_category existing
  on existing.tenant_id = tenants.tenant_id
 and existing.parent_id is null
 and existing.name = required_category.name
where existing.id is null;

update warehouse_item_category
set enabled = true,
    updated_at = current_timestamp
where parent_id is null
  and name in ('水果', '茶叶', '奶制品', '小料/配料')
  and tenant_id in (select distinct tenant_id from loss_item_config);

update loss_item_config config
set warehouse_category_id = (
  select category.id
  from warehouse_item_category category
  where category.tenant_id = config.tenant_id
    and category.parent_id is null
    and category.name = case
      when config.item_code in (
        'DAILY_LOSS_014', 'DAILY_LOSS_015', 'DAILY_LOSS_016', 'DAILY_LOSS_017', 'DAILY_LOSS_018'
      ) then '奶制品'
      when config.item_code in (
        'DAILY_LOSS_030', 'DAILY_LOSS_042', 'DAILY_LOSS_043'
      ) then '茶叶'
      when config.item_code in (
        'DAILY_LOSS_019', 'DAILY_LOSS_020'
      ) then '小料/配料'
      when config.item_code in (
        'DAILY_LOSS_002', 'DAILY_LOSS_003', 'DAILY_LOSS_004', 'DAILY_LOSS_005',
        'DAILY_LOSS_006', 'DAILY_LOSS_007', 'DAILY_LOSS_008', 'DAILY_LOSS_009',
        'DAILY_LOSS_010', 'DAILY_LOSS_011', 'DAILY_LOSS_012', 'DAILY_LOSS_013',
        'DAILY_LOSS_021', 'DAILY_LOSS_022', 'DAILY_LOSS_023', 'DAILY_LOSS_024',
        'DAILY_LOSS_025', 'DAILY_LOSS_026', 'DAILY_LOSS_027', 'DAILY_LOSS_028',
        'DAILY_LOSS_029', 'DAILY_LOSS_031', 'DAILY_LOSS_032', 'DAILY_LOSS_033',
        'DAILY_LOSS_034', 'DAILY_LOSS_035', 'DAILY_LOSS_036', 'DAILY_LOSS_037',
        'DAILY_LOSS_038', 'DAILY_LOSS_039', 'DAILY_LOSS_040', 'DAILY_LOSS_041',
        'FRUIT_CHECK_002', 'FRUIT_CHECK_003', 'FRUIT_CHECK_004', 'FRUIT_CHECK_005',
        'FRUIT_CHECK_006', 'FRUIT_CHECK_007', 'FRUIT_CHECK_008', 'FRUIT_CHECK_009',
        'FRUIT_CHECK_010', 'FRUIT_CHECK_011', 'FRUIT_CHECK_012', 'FRUIT_CHECK_013',
        'FRUIT_CHECK_014', 'FRUIT_CHECK_015', 'FRUIT_CHECK_016', 'FRUIT_CHECK_017',
        'FRUIT_CHECK_018', 'FRUIT_CHECK_019'
      ) then '水果'
      else null
    end
)
where config.item_code in (
  'DAILY_LOSS_002', 'DAILY_LOSS_003', 'DAILY_LOSS_004', 'DAILY_LOSS_005',
  'DAILY_LOSS_006', 'DAILY_LOSS_007', 'DAILY_LOSS_008', 'DAILY_LOSS_009',
  'DAILY_LOSS_010', 'DAILY_LOSS_011', 'DAILY_LOSS_012', 'DAILY_LOSS_013',
  'DAILY_LOSS_014', 'DAILY_LOSS_015', 'DAILY_LOSS_016', 'DAILY_LOSS_017',
  'DAILY_LOSS_018', 'DAILY_LOSS_019', 'DAILY_LOSS_020', 'DAILY_LOSS_021',
  'DAILY_LOSS_022', 'DAILY_LOSS_023', 'DAILY_LOSS_024', 'DAILY_LOSS_025',
  'DAILY_LOSS_026', 'DAILY_LOSS_027', 'DAILY_LOSS_028', 'DAILY_LOSS_029',
  'DAILY_LOSS_030', 'DAILY_LOSS_031', 'DAILY_LOSS_032', 'DAILY_LOSS_033',
  'DAILY_LOSS_034', 'DAILY_LOSS_035', 'DAILY_LOSS_036', 'DAILY_LOSS_037',
  'DAILY_LOSS_038', 'DAILY_LOSS_039', 'DAILY_LOSS_040', 'DAILY_LOSS_041',
  'DAILY_LOSS_042', 'DAILY_LOSS_043',
  'FRUIT_CHECK_002', 'FRUIT_CHECK_003', 'FRUIT_CHECK_004', 'FRUIT_CHECK_005',
  'FRUIT_CHECK_006', 'FRUIT_CHECK_007', 'FRUIT_CHECK_008', 'FRUIT_CHECK_009',
  'FRUIT_CHECK_010', 'FRUIT_CHECK_011', 'FRUIT_CHECK_012', 'FRUIT_CHECK_013',
  'FRUIT_CHECK_014', 'FRUIT_CHECK_015', 'FRUIT_CHECK_016', 'FRUIT_CHECK_017',
  'FRUIT_CHECK_018', 'FRUIT_CHECK_019'
);
