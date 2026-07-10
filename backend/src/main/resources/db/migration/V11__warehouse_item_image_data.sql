set @sql := if(
  exists(
    select 1
    from information_schema.columns
    where table_schema = database()
      and table_name = 'warehouse_item'
      and column_name = 'image_url'
      and data_type <> 'mediumtext'
  ),
  'alter table warehouse_item modify column image_url mediumtext null',
  'select 1'
);
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
