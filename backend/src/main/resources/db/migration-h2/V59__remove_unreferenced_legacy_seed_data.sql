-- H2 test equivalent of V59__remove_unreferenced_legacy_seed_data.sql.

create temporary table v59_legacy_demo_item_candidate (
  item_id bigint not null,
  batch_id bigint not null
);

insert into v59_legacy_demo_item_candidate(item_id, batch_id)
select seed_item.id, seed_batch.id
from warehouse_item seed_item
join (
  select 'CUP-700' as code, '700ml杯子' as item_name, '包装' as item_category,
         '1000个/件' as item_spec, 138.00 as unit_price, 365 as shelf_life_days,
         1000 as cups_per_unit, 8 as daily_usage_estimate, 20 as min_stock_days,
         90 as max_stock_days, 160 as min_stock_quantity, 120 as batch_quantity
  union all
  select 'COCONUT-POWDER', '椰子粉', '原料', '1箱/件', 260.00, 365,
         300, 1.6, 15, 80, 24, 90
  union all
  select 'GRAPE-15JIN', '葡萄', '水果', '15斤/件', 95.00, 5,
         20, 9, 3, 7, 27, 18
  union all
  select 'FRESH-MILK', '鲜奶', '奶制品', '12盒/件', 88.00, 15,
         120, 4, 5, 12, 20, 36
  union all
  select 'STRAW', '吸管', '包装', '1000支/件', 55.00, 730,
         1000, 6, 20, 120, 120, 180
) original_seed on original_seed.code = seed_item.code
join warehouse_stock_batch seed_batch
  on seed_batch.tenant_id = seed_item.tenant_id
 and seed_batch.item_id = seed_item.id
 and seed_batch.batch_no = concat(seed_item.code, '-SEED')
join warehouse_facility central
  on central.tenant_id = seed_item.tenant_id
 and central.code = 'JZ-CENTRAL'
 and central.id = seed_batch.warehouse_id
join warehouse_facility regional
  on regional.tenant_id = seed_item.tenant_id
 and regional.code = 'SD-REGIONAL'
where seed_item.tenant_id = 1
  and seed_item.name = original_seed.item_name
  and seed_item.category = original_seed.item_category
  and seed_item.unit = '件'
  and seed_item.purchase_unit = '件'
  and seed_item.stock_unit = '件'
  and seed_item.ingredient_unit = '件'
  and seed_item.spec = original_seed.item_spec
  and seed_item.unit_price = original_seed.unit_price
  and seed_item.shelf_life_days = original_seed.shelf_life_days
  and seed_item.cups_per_unit = original_seed.cups_per_unit
  and seed_item.daily_usage_estimate = original_seed.daily_usage_estimate
  and seed_item.min_stock_days = original_seed.min_stock_days
  and seed_item.max_stock_days = original_seed.max_stock_days
  and seed_item.min_stock_quantity = original_seed.min_stock_quantity
  and seed_item.alert_enabled = 1
  and seed_item.expiry_alert_days = 3
  and seed_item.active = 1
  and seed_item.image_url is null
  and seed_item.unit_conversion_text is null
  and seed_item.warehouse_location is null
  and seed_item.item_description is null
  and seed_item.item_attributes is null
  and seed_item.sort_order = 593
  and seed_item.updated_at is null
  and seed_batch.quantity = original_seed.batch_quantity
  and seed_batch.reserved_quantity = 0
  and seed_batch.version = 0
  and seed_batch.unit_cost = original_seed.unit_price
  and seed_batch.note = '演示初始库存'
  and seed_batch.expiry_date = dateadd(day, seed_item.shelf_life_days, seed_batch.received_date)
  and seed_batch.updated_at is null
  and not exists (
    select 1
    from warehouse_stock_batch other_batch
    where other_batch.tenant_id = seed_item.tenant_id
      and other_batch.item_id = seed_item.id
      and other_batch.id <> seed_batch.id
  )
  and (
    select count(*)
    from warehouse_inventory inventory
    where inventory.tenant_id = seed_item.tenant_id
      and inventory.item_id = seed_item.id
  ) = 2
  and not exists (
    select 1
    from warehouse_inventory inventory
    where inventory.tenant_id = seed_item.tenant_id
      and inventory.item_id = seed_item.id
      and not (
        (
          inventory.warehouse_id = central.id
          and inventory.on_hand_quantity = original_seed.batch_quantity
          and inventory.reserved_quantity = 0
          and inventory.in_transit_quantity = 0
          and inventory.unit_cost = round(original_seed.unit_price, 4)
          and inventory.min_stock_quantity = original_seed.min_stock_quantity
          and inventory.alert_enabled = true
          and inventory.expiry_alert_days = 3
          and inventory.version = 0
          and inventory.updated_at is null
        )
        or (
          inventory.warehouse_id = regional.id
          and inventory.on_hand_quantity = 0
          and inventory.reserved_quantity = 0
          and inventory.in_transit_quantity = 0
          and inventory.unit_cost = 0
          and inventory.min_stock_quantity = original_seed.min_stock_quantity
          and inventory.alert_enabled = true
          and inventory.expiry_alert_days = 3
          and inventory.version = 0
          and inventory.updated_at is null
        )
      )
  )
  and not exists (
    select 1 from warehouse_stock_movement movement
    where movement.tenant_id = seed_item.tenant_id
      and (movement.item_id = seed_item.id or movement.batch_id = seed_batch.id)
  )
  and not exists (
    select 1 from warehouse_stock_adjustment adjustment
    where adjustment.tenant_id = seed_item.tenant_id
      and (adjustment.item_id = seed_item.id or adjustment.batch_id = seed_batch.id)
  )
  and not exists (
    select 1 from store_requisition_line line
    where line.tenant_id = seed_item.tenant_id and line.item_id = seed_item.id
  )
  and not exists (
    select 1 from warehouse_purchase_order_line line
    where line.tenant_id = seed_item.tenant_id and line.item_id = seed_item.id
  )
  and not exists (
    select 1 from warehouse_delivery_order_line line
    where line.tenant_id = seed_item.tenant_id and line.item_id = seed_item.id
  )
  and not exists (
    select 1 from store_receipt_line line
    where line.tenant_id = seed_item.tenant_id and line.item_id = seed_item.id
  )
  and not exists (
    select 1 from warehouse_return_order_line line
    where line.tenant_id = seed_item.tenant_id
      and (line.item_id = seed_item.id or line.batch_id = seed_batch.id)
  )
  and not exists (
    select 1 from store_inventory inventory
    where inventory.tenant_id = seed_item.tenant_id and inventory.item_id = seed_item.id
  )
  and not exists (
    select 1 from store_inventory_movement movement
    where movement.tenant_id = seed_item.tenant_id and movement.item_id = seed_item.id
  )
  and not exists (
    select 1 from store_inventory_check_line line
    where line.tenant_id = seed_item.tenant_id and line.item_code = seed_item.code
  )
  and not exists (
    select 1 from warehouse_item_department department
    where department.tenant_id = seed_item.tenant_id and department.item_id = seed_item.id
  )
  and not exists (
    select 1 from warehouse_transfer_line line
    where line.tenant_id = seed_item.tenant_id and line.item_id = seed_item.id
  )
  and not exists (
    select 1 from daily_loss_record loss_record
    where loss_record.tenant_id = seed_item.tenant_id and loss_record.item_id = seed_item.id
  )
  and not exists (
    select 1 from daily_loss_inventory_application application
    where application.tenant_id = seed_item.tenant_id and application.item_id = seed_item.id
  )
  and not exists (
    select 1 from warehouse_alert alert
    where alert.tenant_id = seed_item.tenant_id and alert.item_id = seed_item.id
  )
  and not exists (
    select 1 from warehouse_attachment attachment
    where attachment.tenant_id = seed_item.tenant_id
      and attachment.business_id = cast(seed_item.id as varchar)
  )
  and not exists (
    select 1 from warehouse_request_dedup request_dedup
    where request_dedup.tenant_id = seed_item.tenant_id
      and request_dedup.business_id = cast(seed_item.id as varchar)
  )
  and not exists (
    select 1 from operation_log log_entry
    where log_entry.tenant_id = seed_item.tenant_id
      and log_entry.target_id = cast(seed_item.id as varchar)
  );

create temporary table v59_legacy_default_supplier_candidate (
  supplier_id bigint not null
);

insert into v59_legacy_default_supplier_candidate(supplier_id)
select supplier.id
from warehouse_supplier supplier
where supplier.tenant_id = 1
  and supplier.name = '总部默认供应商'
  and supplier.contact_name = '采购负责人'
  and supplier.phone = ''
  and supplier.settlement_cycle = '月结'
  and supplier.active = 1
  and supplier.updated_at is null
  and not exists (
    select 1 from warehouse_purchase_order purchase_order
    where purchase_order.tenant_id = supplier.tenant_id
      and purchase_order.supplier_id = supplier.id
  )
  and not exists (
    select 1 from warehouse_attachment attachment
    where attachment.tenant_id = supplier.tenant_id
      and attachment.business_id = cast(supplier.id as varchar)
  )
  and not exists (
    select 1 from warehouse_request_dedup request_dedup
    where request_dedup.tenant_id = supplier.tenant_id
      and request_dedup.business_id = cast(supplier.id as varchar)
  )
  and not exists (
    select 1 from operation_log log_entry
    where log_entry.tenant_id = supplier.tenant_id
      and log_entry.target_id = cast(supplier.id as varchar)
  );

delete from warehouse_inventory
where item_id in (select item_id from v59_legacy_demo_item_candidate);

delete from warehouse_stock_batch
where id in (select batch_id from v59_legacy_demo_item_candidate);

delete from warehouse_item
where id in (select item_id from v59_legacy_demo_item_candidate);

delete from warehouse_supplier
where id in (select supplier_id from v59_legacy_default_supplier_candidate);

drop table v59_legacy_default_supplier_candidate;
drop table v59_legacy_demo_item_candidate;
