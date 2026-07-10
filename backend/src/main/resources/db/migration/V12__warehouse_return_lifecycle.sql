set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'warehouse_return_order' and column_name = 'source_requisition_id'), 'select 1', 'alter table warehouse_return_order add column source_requisition_id varchar(120) null after return_no');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'warehouse_return_order' and column_name = 'source_delivery_id'), 'select 1', 'alter table warehouse_return_order add column source_delivery_id varchar(120) null after source_requisition_id');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'warehouse_return_order' and column_name = 'reason'), 'select 1', 'alter table warehouse_return_order add column reason text null after total_amount');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'warehouse_return_order' and column_name = 'review_note'), 'select 1', 'alter table warehouse_return_order add column review_note text null after note');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'warehouse_return_order' and column_name = 'received_note'), 'select 1', 'alter table warehouse_return_order add column received_note text null after review_note');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'warehouse_return_order' and column_name = 'reviewed_at'), 'select 1', 'alter table warehouse_return_order add column reviewed_at timestamp null default null after return_date');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'warehouse_return_order' and column_name = 'received_at'), 'select 1', 'alter table warehouse_return_order add column received_at timestamp null default null after reviewed_at');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'warehouse_return_order_line' and column_name = 'batch_id'), 'select 1', 'alter table warehouse_return_order_line add column batch_id bigint null after spec');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'warehouse_return_order_line' and column_name = 'batch_no'), 'select 1', 'alter table warehouse_return_order_line add column batch_no varchar(120) null after batch_id');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'warehouse_return_order_line' and column_name = 'source_requisition_line_id'), 'select 1', 'alter table warehouse_return_order_line add column source_requisition_line_id bigint null after return_order_id');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'warehouse_return_order_line' and column_name = 'reason'), 'select 1', 'alter table warehouse_return_order_line add column reason text null after amount');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'warehouse_attachment' and column_name = 'content'), 'select 1', 'alter table warehouse_attachment add column content longblob null after storage_path');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'warehouse_return_order' and index_name = 'idx_return_order_source_req'), 'select 1', 'alter table warehouse_return_order add index idx_return_order_source_req (tenant_id, source_requisition_id)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'warehouse_return_order' and index_name = 'idx_return_order_source_delivery'), 'select 1', 'alter table warehouse_return_order add index idx_return_order_source_delivery (tenant_id, source_delivery_id)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
