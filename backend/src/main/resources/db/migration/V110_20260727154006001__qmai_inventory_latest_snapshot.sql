-- Latest successful QMAI daily-inventory snapshot.
-- A failed refresh never changes these rows; the repository replaces header + items in one transaction.
create table qmai_inventory_snapshot (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  brand_code varchar(40) not null,
  snapshot_date date not null,
  captured_at timestamp not null,
  source_total bigint not null,
  row_count int not null,
  created_at timestamp not null default current_timestamp,
  unique key uk_qmai_inventory_snapshot_latest (tenant_id, brand_code),
  index idx_qmai_inventory_snapshot_date (tenant_id, snapshot_date),
  constraint chk_qmai_inventory_snapshot_total check (source_total >= 0 and row_count >= 0),
  constraint fk_qmai_inventory_snapshot_tenant
    foreign key (tenant_id) references tenant(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table qmai_inventory_snapshot_item (
  id bigint not null auto_increment primary key,
  snapshot_id bigint not null,
  line_no int not null,
  warehouse_id varchar(120) null,
  warehouse_no varchar(200) null,
  warehouse_name varchar(300) null,
  org_name varchar(300) null,
  product_id varchar(120) null,
  product_code varchar(200) null,
  product_name varchar(1000) null,
  product_spec varchar(1000) null,
  order_unit varchar(120) null,
  stock_unit varchar(120) null,
  consume_unit varchar(120) null,
  purchase_unit varchar(120) null,
  export_unit varchar(120) null,
  category_id varchar(120) null,
  category_name varchar(300) null,
  quantity decimal(38,18) null,
  available_quantity decimal(38,18) null,
  current_amount decimal(38,18) null,
  estimate_num decimal(38,18) null,
  occupy_quantity decimal(38,18) null,
  cost_price decimal(38,18) null,
  estimate_inbound_num decimal(38,18) null,
  unique key uk_qmai_inventory_snapshot_line (snapshot_id, line_no),
  index idx_qmai_inventory_snapshot_warehouse (snapshot_id, warehouse_no),
  index idx_qmai_inventory_snapshot_product (snapshot_id, product_code),
  constraint fk_qmai_inventory_snapshot_item
    foreign key (snapshot_id) references qmai_inventory_snapshot(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

-- Short database lease prevents multiple application instances from pulling the same tenant/brand at 05:00.
create table qmai_inventory_sync_lease (
  tenant_id bigint not null,
  brand_code varchar(40) not null,
  lock_token varchar(64) not null,
  locked_until timestamp not null,
  updated_at timestamp not null default current_timestamp on update current_timestamp,
  primary key (tenant_id, brand_code),
  index idx_qmai_inventory_lease_expiry (locked_until),
  constraint fk_qmai_inventory_lease_tenant
    foreign key (tenant_id) references tenant(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
