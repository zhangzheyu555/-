-- V90: align daily-loss pricing and monthly settlement with the approved real workbook.
-- Raw quantities stay in their operational unit. Gram items are priced by 500g (one 斤).

update loss_item_config
set pricing_unit = case
      when cast(unit as binary) = 0xE5858B then convert(0xE696A4 using utf8mb4)
      else unit
    end,
    quantity_per_pricing_unit = case when cast(unit as binary) = 0xE5858B then 500 else 1 end,
    unit_price = case when cast(unit as binary) = 0xE5858B then unit_price * 500 else unit_price end;

alter table loss_item_config
  modify column pricing_unit varchar(40) not null,
  modify column quantity_per_pricing_unit decimal(18,4) not null;

alter table daily_loss_report
  add constraint chk_daily_loss_report_supplier_compensation check (supplier_compensation_amount >= 0);

update daily_loss_record
set pricing_unit_snapshot = stock_unit,
    quantity_per_pricing_unit_snapshot = 1,
    priced_quantity_snapshot = loss_quantity
where pricing_unit_snapshot is null;

alter table daily_loss_record
  add constraint chk_daily_loss_pricing_quantity check (quantity_per_pricing_unit_snapshot > 0),
  add constraint chk_daily_loss_priced_quantity check (priced_quantity_snapshot > 0);
