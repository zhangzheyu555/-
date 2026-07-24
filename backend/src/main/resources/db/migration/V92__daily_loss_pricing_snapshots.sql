-- Persist the input-to-pricing conversion used by each submitted daily-loss record.

set @sql := if(
  exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'daily_loss_record' and column_name = 'pricing_unit_snapshot'),
  'select 1',
  'alter table daily_loss_record add column pricing_unit_snapshot varchar(40) null after stock_unit'
);
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(
  exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'daily_loss_record' and column_name = 'quantity_per_pricing_unit_snapshot'),
  'select 1',
  'alter table daily_loss_record add column quantity_per_pricing_unit_snapshot decimal(18,4) null after pricing_unit_snapshot'
);
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(
  exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'daily_loss_record' and column_name = 'priced_quantity_snapshot'),
  'select 1',
  'alter table daily_loss_record add column priced_quantity_snapshot decimal(18,4) null after loss_quantity'
);
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(
  exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'daily_loss_report' and column_name = 'supplier_compensation_amount'),
  'select 1',
  'alter table daily_loss_report add column supplier_compensation_amount decimal(18,2) not null default 0.00 after review_note'
);
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
