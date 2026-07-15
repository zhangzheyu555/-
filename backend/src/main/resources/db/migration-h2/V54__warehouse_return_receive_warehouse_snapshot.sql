-- H2 test equivalent of V54__warehouse_return_receive_warehouse_snapshot.sql.
-- It mirrors the production safety contract: audit first, commit evidence, then
-- fail rather than guessing a warehouse for an incomplete historical return.

alter table warehouse_return_order
  add column if not exists receive_warehouse_code_snapshot varchar(64);
alter table warehouse_return_order
  add column if not exists receive_warehouse_name_snapshot varchar(160);

create table if not exists warehouse_return_snapshot_backfill_audit (
  id bigint not null auto_increment primary key,
  migration_key varchar(64) not null,
  tenant_id bigint not null,
  return_order_id varchar(120) not null,
  warehouse_id bigint null,
  original_code_snapshot varchar(64) null,
  original_name_snapshot varchar(160) null,
  failure_code varchar(64) not null,
  failure_message varchar(500) not null,
  created_at timestamp not null default current_timestamp,
  last_detected_at timestamp not null default current_timestamp,
  constraint uk_return_snapshot_backfill_audit unique (migration_key, tenant_id, return_order_id)
);

merge into warehouse_return_snapshot_backfill_audit (
  migration_key, tenant_id, return_order_id, warehouse_id,
  original_code_snapshot, original_name_snapshot, failure_code, failure_message,
  created_at, last_detected_at
)
key (migration_key, tenant_id, return_order_id)
select
  'V54_RETURN_RECEIVE_WAREHOUSE_SNAPSHOT',
  return_order.tenant_id,
  return_order.id,
  return_order.warehouse_id,
  return_order.receive_warehouse_code_snapshot,
  return_order.receive_warehouse_name_snapshot,
  case
    when return_order.warehouse_id is null then 'MISSING_WAREHOUSE_ID'
    when facility.id is null then 'WAREHOUSE_NOT_FOUND'
    when (nullif(trim(return_order.receive_warehouse_code_snapshot), '') is null)
         <> (nullif(trim(return_order.receive_warehouse_name_snapshot), '') is null)
      then 'PARTIAL_SNAPSHOT'
    when nullif(trim(facility.code), '') is null
         or nullif(trim(facility.name), '') is null then 'INVALID_FACILITY_SNAPSHOT'
    else 'BACKFILLED'
  end,
  case
    when return_order.warehouse_id is null
      then '退货单缺少 warehouse_id，禁止猜测收货仓。'
    when facility.id is null
      then 'warehouse_id 未关联同租户 warehouse_facility，禁止猜测收货仓。'
    when (nullif(trim(return_order.receive_warehouse_code_snapshot), '') is null)
         <> (nullif(trim(return_order.receive_warehouse_name_snapshot), '') is null)
      then '退货单收货仓快照不完整，禁止覆盖现有历史快照。'
    when nullif(trim(facility.code), '') is null
         or nullif(trim(facility.name), '') is null
      then '关联仓库缺少编码或名称，禁止生成不完整快照。'
    else '按 warehouse_id 回填收货仓快照。'
  end,
  current_timestamp,
  current_timestamp
from warehouse_return_order return_order
left join warehouse_facility facility
  on facility.tenant_id = return_order.tenant_id
 and facility.id = return_order.warehouse_id
where nullif(trim(return_order.receive_warehouse_code_snapshot), '') is null
   or nullif(trim(return_order.receive_warehouse_name_snapshot), '') is null;

commit;

drop table if exists warehouse_return_snapshot_backfill_guard;
create table warehouse_return_snapshot_backfill_guard (
  valid_flag int not null,
  constraint chk_v54_unresolved_return_snapshots check (valid_flag = 1)
);
insert into warehouse_return_snapshot_backfill_guard(valid_flag)
select 0
where exists (
  select 1
  from warehouse_return_order return_order
  left join warehouse_facility facility
    on facility.tenant_id = return_order.tenant_id
   and facility.id = return_order.warehouse_id
  where (nullif(trim(return_order.receive_warehouse_code_snapshot), '') is null)
          <> (nullif(trim(return_order.receive_warehouse_name_snapshot), '') is null)
     or (
       nullif(trim(return_order.receive_warehouse_code_snapshot), '') is null
       and nullif(trim(return_order.receive_warehouse_name_snapshot), '') is null
       and (
         return_order.warehouse_id is null
         or facility.id is null
         or nullif(trim(facility.code), '') is null
         or nullif(trim(facility.name), '') is null
       )
     )
);
drop table warehouse_return_snapshot_backfill_guard;

update warehouse_return_order return_order
set receive_warehouse_code_snapshot = (
      select facility.code
      from warehouse_facility facility
      where facility.tenant_id = return_order.tenant_id
        and facility.id = return_order.warehouse_id
    ),
    receive_warehouse_name_snapshot = (
      select facility.name
      from warehouse_facility facility
      where facility.tenant_id = return_order.tenant_id
        and facility.id = return_order.warehouse_id
    )
where nullif(trim(receive_warehouse_code_snapshot), '') is null
  and nullif(trim(receive_warehouse_name_snapshot), '') is null;

alter table warehouse_return_order
  alter column receive_warehouse_code_snapshot set not null;
alter table warehouse_return_order
  alter column receive_warehouse_name_snapshot set not null;
