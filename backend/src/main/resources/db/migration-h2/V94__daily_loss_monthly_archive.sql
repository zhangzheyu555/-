create table daily_loss_monthly_archive (
  id varchar(120) primary key,
  tenant_id bigint not null,
  loss_month char(7) not null,
  source_sheet varchar(120) not null,
  source_title varchar(255) not null,
  source_file_name varchar(255) not null,
  source_file_sha256 char(64) not null,
  declared_total_loss_amount decimal(18,6) not null,
  detail_total_loss_amount decimal(18,6) not null,
  declared_supplier_compensation_amount decimal(18,6) not null,
  detail_supplier_compensation_amount decimal(18,6) not null,
  declared_store_borne_amount decimal(18,6) not null,
  detail_store_borne_amount decimal(18,6) not null,
  calculated_store_borne_amount decimal(18,6) not null,
  declared_borne_difference decimal(18,6) not null,
  detail_loss_difference decimal(18,6) not null,
  store_count int not null,
  item_count int not null,
  reconciliation_status varchar(32) not null,
  source_note varchar(500),
  imported_by bigint,
  imported_at timestamp not null default current_timestamp,
  updated_at timestamp,
  constraint uk_daily_loss_monthly_archive unique (tenant_id, loss_month),
  constraint fk_daily_loss_archive_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_daily_loss_archive_importer foreign key (imported_by) references auth_user(id),
  constraint chk_daily_loss_archive_status check (reconciliation_status in ('MATCHED', 'SOURCE_VARIANCE')),
  constraint chk_daily_loss_archive_counts check (store_count > 0 and item_count > 0)
);

create table daily_loss_monthly_archive_store (
  archive_id varchar(120) not null,
  source_row int not null,
  store_id varchar(64) not null,
  store_name_snapshot varchar(160) not null,
  primary key (archive_id, source_row),
  constraint uk_daily_loss_archive_store unique (archive_id, store_id),
  constraint fk_daily_loss_archive_store_parent foreign key (archive_id)
    references daily_loss_monthly_archive(id) on delete cascade,
  constraint fk_daily_loss_archive_store_branch foreign key (store_id) references store_branch(id)
);

create table daily_loss_monthly_archive_item (
  archive_id varchar(120) not null,
  source_column int not null,
  item_config_id bigint not null,
  item_name_snapshot varchar(160) not null,
  input_unit_snapshot varchar(40) not null,
  pricing_unit_snapshot varchar(40) not null,
  quantity_per_pricing_unit_snapshot decimal(18,4) not null,
  total_loss_quantity decimal(18,4) not null,
  priced_quantity decimal(18,4) not null,
  unit_price decimal(18,4),
  unit_price_source varchar(32) not null,
  total_loss_amount decimal(18,6) not null,
  supplier_compensation_amount decimal(18,6) not null,
  store_borne_amount decimal(18,6) not null,
  primary key (archive_id, source_column),
  constraint uk_daily_loss_archive_item unique (archive_id, item_config_id),
  constraint fk_daily_loss_archive_item_parent foreign key (archive_id)
    references daily_loss_monthly_archive(id) on delete cascade,
  constraint fk_daily_loss_archive_item_config foreign key (item_config_id) references loss_item_config(id),
  constraint chk_daily_loss_archive_item_factor check (quantity_per_pricing_unit_snapshot > 0),
  constraint chk_daily_loss_archive_item_values check (
    total_loss_quantity >= 0 and priced_quantity >= 0 and (unit_price is null or unit_price >= 0)
    and total_loss_amount >= 0 and supplier_compensation_amount >= 0
  )
);

create table daily_loss_monthly_archive_store_item (
  archive_id varchar(120) not null,
  source_row int not null,
  source_column int not null,
  store_id varchar(64) not null,
  item_config_id bigint not null,
  loss_quantity decimal(18,4) not null,
  primary key (archive_id, source_row, source_column),
  constraint fk_daily_loss_archive_value_parent foreign key (archive_id)
    references daily_loss_monthly_archive(id) on delete cascade,
  constraint fk_daily_loss_archive_value_store foreign key (store_id) references store_branch(id),
  constraint fk_daily_loss_archive_value_item foreign key (item_config_id) references loss_item_config(id),
  constraint chk_daily_loss_archive_value_quantity check (loss_quantity >= 0)
);

create index idx_daily_loss_archive_month on daily_loss_monthly_archive(tenant_id, loss_month);
create index idx_daily_loss_archive_store_item_store on daily_loss_monthly_archive_store_item(store_id, archive_id);
create index idx_daily_loss_archive_store_item_config on daily_loss_monthly_archive_store_item(item_config_id, archive_id);
