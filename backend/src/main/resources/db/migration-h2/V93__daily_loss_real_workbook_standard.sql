-- H2 test counterpart of the real monthly daily-loss workbook standard.

alter table loss_item_config add column pricing_unit varchar(40);
alter table loss_item_config add column quantity_per_pricing_unit decimal(18,4);

update loss_item_config
set pricing_unit = case when unit = '克' then '斤' else unit end,
    quantity_per_pricing_unit = case when unit = '克' then 500 else 1 end,
    unit_price = case when unit = '克' then unit_price * 500 else unit_price end;

alter table loss_item_config alter column pricing_unit set not null;
alter table loss_item_config alter column quantity_per_pricing_unit set not null;
alter table loss_item_config add constraint chk_loss_item_config_pricing_quantity
  check (quantity_per_pricing_unit > 0);

alter table daily_loss_report add column supplier_compensation_amount decimal(18,2) default 0 not null;
alter table daily_loss_report add constraint chk_daily_loss_report_supplier_compensation
  check (supplier_compensation_amount >= 0);

alter table daily_loss_record add column pricing_unit_snapshot varchar(40);
alter table daily_loss_record add column quantity_per_pricing_unit_snapshot decimal(18,4);
alter table daily_loss_record add column priced_quantity_snapshot decimal(18,4);

update daily_loss_record
set pricing_unit_snapshot = stock_unit,
    quantity_per_pricing_unit_snapshot = 1,
    priced_quantity_snapshot = loss_quantity
where pricing_unit_snapshot is null;

alter table daily_loss_record add constraint chk_daily_loss_pricing_quantity
  check (quantity_per_pricing_unit_snapshot > 0);
alter table daily_loss_record add constraint chk_daily_loss_priced_quantity
  check (priced_quantity_snapshot > 0);
