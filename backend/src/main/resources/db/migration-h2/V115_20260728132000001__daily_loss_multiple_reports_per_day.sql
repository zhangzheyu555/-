alter table daily_loss_report
  drop constraint if exists uk_daily_loss_report_store_day;
