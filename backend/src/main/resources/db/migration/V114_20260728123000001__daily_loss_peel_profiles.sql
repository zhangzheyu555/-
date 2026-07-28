create table daily_loss_peel_profile (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  primary_item_config_id bigint not null,
  peeled_item_config_id bigint null,
  unpeeled_item_config_id bigint null,
  canonical_name varchar(160) not null,
  default_peel_state varchar(20) not null,
  yield_rate decimal(12,9) null,
  gross_grams_per_unit decimal(18,4) null,
  inventory_unit varchar(20) not null,
  source_price_file varchar(255) not null,
  source_formula_file varchar(255) not null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null on update current_timestamp,
  unique key uk_daily_loss_peel_profile_primary (tenant_id, primary_item_config_id),
  index idx_daily_loss_peel_profile_peeled (tenant_id, peeled_item_config_id),
  index idx_daily_loss_peel_profile_unpeeled (tenant_id, unpeeled_item_config_id),
  constraint fk_daily_loss_peel_profile_tenant
    foreign key (tenant_id) references tenant(id),
  constraint fk_daily_loss_peel_profile_primary
    foreign key (primary_item_config_id) references loss_item_config(id),
  constraint fk_daily_loss_peel_profile_peeled
    foreign key (peeled_item_config_id) references loss_item_config(id),
  constraint fk_daily_loss_peel_profile_unpeeled
    foreign key (unpeeled_item_config_id) references loss_item_config(id),
  constraint chk_daily_loss_peel_profile_default
    check (default_peel_state in ('PEELED', 'UNPEELED')),
  constraint chk_daily_loss_peel_profile_yield
    check (yield_rate is null or (yield_rate > 0 and yield_rate <= 1)),
  constraint chk_daily_loss_peel_profile_gross
    check (gross_grams_per_unit is null or gross_grams_per_unit > 0),
  constraint chk_daily_loss_peel_profile_price
    check (peeled_item_config_id is not null or unpeeled_item_config_id is not null)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

alter table daily_loss_record
  add column peel_state_snapshot varchar(20) null after loss_quantity,
  add column price_basis_snapshot varchar(20) null after peel_state_snapshot,
  add column yield_rate_snapshot decimal(12,9) null after price_basis_snapshot,
  add column inventory_quantity_snapshot decimal(18,4) null after yield_rate_snapshot,
  add column inventory_unit_snapshot varchar(20) null after inventory_quantity_snapshot;

update daily_loss_record
set inventory_quantity_snapshot = loss_quantity,
    inventory_unit_snapshot = stock_unit
where inventory_quantity_snapshot is null;

alter table daily_loss_record
  add constraint chk_daily_loss_peel_state
    check (peel_state_snapshot is null or peel_state_snapshot in ('PEELED', 'UNPEELED')),
  add constraint chk_daily_loss_price_basis
    check (price_basis_snapshot is null or price_basis_snapshot in ('PEELED', 'UNPEELED', 'STANDARD')),
  add constraint chk_daily_loss_yield_rate
    check (yield_rate_snapshot is null or (yield_rate_snapshot > 0 and yield_rate_snapshot <= 1)),
  add constraint chk_daily_loss_inventory_snapshot_quantity
    check (inventory_quantity_snapshot is null or inventory_quantity_snapshot > 0);

alter table daily_loss_inventory_application
  modify column quantity decimal(18,4) not null;

insert into daily_loss_peel_profile(
  tenant_id, primary_item_config_id, peeled_item_config_id, unpeeled_item_config_id,
  canonical_name, default_peel_state, yield_rate, gross_grams_per_unit, inventory_unit,
  source_price_file, source_formula_file
)
select tenant.id,
       primary_config.id,
       peeled_config.id,
       unpeeled_config.id,
       template.canonical_name,
       template.default_peel_state,
       template.yield_rate,
       template.gross_grams_per_unit,
       template.inventory_unit,
       '每克损耗单价表(1).numbers',
       '5月产品用量核算表 - 副本(1)(1).numbers'
from tenant
cross join (
  select 'DAILY_LOSS_039' primary_code, 'DAILY_LOSS_039' peeled_code, 'FRUIT_CHECK_002' unpeeled_code,
         '牛油果' canonical_name, 'PEELED' default_peel_state, 0.780000000 yield_rate,
         500.0000 gross_grams_per_unit, '个' inventory_unit
  union all select 'FRUIT_CHECK_003', null, 'FRUIT_CHECK_003', '芒果', 'UNPEELED',
         0.530303030, null, '斤'
  union all select 'FRUIT_CHECK_004', null, 'FRUIT_CHECK_004', '青芒', 'UNPEELED',
         0.700000000, null, '斤'
  union all select 'DAILY_LOSS_031', 'DAILY_LOSS_031', 'FRUIT_CHECK_005', '火龙果', 'PEELED',
         0.700000000, null, '斤'
  union all select 'FRUIT_CHECK_006', null, 'FRUIT_CHECK_006', '橙子', 'UNPEELED',
         0.540000000, null, '斤'
  union all select 'FRUIT_CHECK_007', null, 'FRUIT_CHECK_007', '凤梨', 'UNPEELED',
         0.598263615, 1267.0000, '斤'
  union all select 'DAILY_LOSS_038', 'DAILY_LOSS_038', 'FRUIT_CHECK_008', '秋月梨', 'PEELED',
         0.788622754, null, '斤'
  union all select 'DAILY_LOSS_037', 'DAILY_LOSS_037', 'FRUIT_CHECK_009', '苹果', 'PEELED',
         0.897854954, null, '斤'
  union all select 'DAILY_LOSS_026', 'DAILY_LOSS_026', 'FRUIT_CHECK_010', '百香果', 'PEELED',
         null, null, '斤'
  union all select 'DAILY_LOSS_029', 'DAILY_LOSS_029', 'FRUIT_CHECK_011', '芭乐', 'PEELED',
         0.735675956, null, '斤'
  union all select 'DAILY_LOSS_022', 'DAILY_LOSS_022', 'FRUIT_CHECK_012', '杨梅', 'PEELED',
         0.800000000, null, '斤'
  union all select 'DAILY_LOSS_024', 'DAILY_LOSS_024', 'FRUIT_CHECK_013', '荔枝', 'PEELED',
         0.580448065, null, '斤'
  union all select 'FRUIT_CHECK_014', null, 'FRUIT_CHECK_014', '桃子', 'UNPEELED',
         0.742268041, null, '斤'
  union all select 'DAILY_LOSS_027', 'DAILY_LOSS_027', 'FRUIT_CHECK_015', '黄皮', 'PEELED',
         0.740408163, null, '斤'
  union all select 'DAILY_LOSS_034', 'DAILY_LOSS_034', 'FRUIT_CHECK_016', '金桔', 'PEELED',
         null, null, '斤'
  union all select 'FRUIT_CHECK_017', null, 'FRUIT_CHECK_017', '柠檬', 'UNPEELED',
         0.900000000, null, '斤'
  union all select 'DAILY_LOSS_041', 'DAILY_LOSS_041', 'FRUIT_CHECK_018', '羽衣甘蓝', 'PEELED',
         null, null, '斤'
  union all select 'DAILY_LOSS_023', 'DAILY_LOSS_023', 'FRUIT_CHECK_019', '葡萄', 'PEELED',
         0.650000000, null, '斤'
  union all select 'DAILY_LOSS_033', 'DAILY_LOSS_033', null, '西瓜', 'PEELED',
         0.800000000, null, '斤'
) template
join loss_item_config primary_config
  on primary_config.tenant_id = tenant.id
 and primary_config.item_code = template.primary_code
 and primary_config.active = 1
left join loss_item_config peeled_config
  on peeled_config.tenant_id = tenant.id
 and peeled_config.item_code = template.peeled_code
 and peeled_config.active = 1
left join loss_item_config unpeeled_config
  on unpeeled_config.tenant_id = tenant.id
 and unpeeled_config.item_code = template.unpeeled_code
 and unpeeled_config.active = 1;
