-- Persist the receiving warehouse identity on return orders.  A return PDF is a
-- historical document, so it must never resolve the current warehouse name at print time.
--
-- This migration is deliberately retry-safe: MySQL DDL commits implicitly, and an
-- unresolved historical record must leave an audit trail before Flyway fails.

-- MySQL 8.0 does not support ADD COLUMN IF NOT EXISTS.  Check metadata inside
-- a temporary procedure so a Flyway repair-and-rerun after a later guard
-- failure does not trip over already-created columns.
drop procedure if exists v54_add_return_snapshot_columns;
delimiter $$
create procedure v54_add_return_snapshot_columns()
begin
  declare code_snapshot_exists int default 0;
  declare name_snapshot_exists int default 0;

  select count(*) into code_snapshot_exists
  from information_schema.columns
  where table_schema = database()
    and table_name = 'warehouse_return_order'
    and column_name = 'receive_warehouse_code_snapshot';

  if code_snapshot_exists = 0 then
    alter table warehouse_return_order
      add column receive_warehouse_code_snapshot varchar(64) null after warehouse_id;
  end if;

  select count(*) into name_snapshot_exists
  from information_schema.columns
  where table_schema = database()
    and table_name = 'warehouse_return_order'
    and column_name = 'receive_warehouse_name_snapshot';

  if name_snapshot_exists = 0 then
    alter table warehouse_return_order
      add column receive_warehouse_name_snapshot varchar(160) null after receive_warehouse_code_snapshot;
  end if;
end$$
delimiter ;
call v54_add_return_snapshot_columns();
drop procedure v54_add_return_snapshot_columns;

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
  unique key uk_return_snapshot_backfill_audit (migration_key, tenant_id, return_order_id),
  index idx_return_snapshot_backfill_failure (tenant_id, failure_code, created_at),
  constraint fk_return_snapshot_backfill_tenant
    foreign key (tenant_id) references tenant(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

-- Audit only rows that still need a snapshot.  Do not overwrite a partial
-- snapshot: it is ambiguous historical evidence and must be fixed explicitly.
insert into warehouse_return_snapshot_backfill_audit (
  migration_key, tenant_id, return_order_id, warehouse_id,
  original_code_snapshot, original_name_snapshot, failure_code, failure_message,
  created_at, last_detected_at
)
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
   or nullif(trim(return_order.receive_warehouse_name_snapshot), '') is null
on duplicate key update
  warehouse_id = values(warehouse_id),
  original_code_snapshot = values(original_code_snapshot),
  original_name_snapshot = values(original_name_snapshot),
  failure_code = values(failure_code),
  failure_message = values(failure_message),
  last_detected_at = current_timestamp;

-- An explicit operation log makes a blocked production migration visible in the
-- normal audit channel as well as in the dedicated migration audit table.
insert into operation_log(
  tenant_id, operator_id, operator_name, action, target_type, target_id,
  store_id, reason, created_at
)
select
  audit.tenant_id,
  null,
  'SYSTEM (V54 migration)',
  'MIGRATION_BLOCKED',
  'warehouse_return_order',
  audit.return_order_id,
  return_order.return_store_id,
  concat('V54 收货仓快照回填失败：', audit.failure_message),
  current_timestamp
from warehouse_return_snapshot_backfill_audit audit
join warehouse_return_order return_order
  on return_order.tenant_id = audit.tenant_id
 and return_order.id = audit.return_order_id
where audit.migration_key = 'V54_RETURN_RECEIVE_WAREHOUSE_SNAPSHOT'
  and audit.failure_code <> 'BACKFILLED'
  and not exists (
    select 1
    from operation_log existing
    where existing.tenant_id = audit.tenant_id
      and existing.action = 'MIGRATION_BLOCKED'
      and existing.target_type = 'warehouse_return_order'
      and existing.target_id = audit.return_order_id
      and existing.reason like 'V54 收货仓快照回填失败：%'
  );

-- Preserve audit evidence even when the migration must stop.  The script can be
-- rerun after data repair because the columns and audit table are idempotent.
commit;

drop procedure if exists v54_fail_unresolved_return_snapshots;
delimiter $$
create procedure v54_fail_unresolved_return_snapshots()
begin
  declare unresolved_count int default 0;

  select count(*) into unresolved_count
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
     );

  if unresolved_count > 0 then
    signal sqlstate '45000'
      set message_text = 'V54 blocked: unresolved return warehouse snapshots; inspect warehouse_return_snapshot_backfill_audit';
  end if;
end$$
delimiter ;
call v54_fail_unresolved_return_snapshots();
drop procedure v54_fail_unresolved_return_snapshots;

-- Only records with both snapshots empty are eligible.  Existing complete
-- snapshots remain immutable, and partial snapshots were blocked above.
update warehouse_return_order return_order
join warehouse_facility facility
  on facility.tenant_id = return_order.tenant_id
 and facility.id = return_order.warehouse_id
set return_order.receive_warehouse_code_snapshot = facility.code,
    return_order.receive_warehouse_name_snapshot = facility.name
where nullif(trim(return_order.receive_warehouse_code_snapshot), '') is null
  and nullif(trim(return_order.receive_warehouse_name_snapshot), '') is null;

alter table warehouse_return_order
  modify column receive_warehouse_code_snapshot varchar(64) not null,
  modify column receive_warehouse_name_snapshot varchar(160) not null;
