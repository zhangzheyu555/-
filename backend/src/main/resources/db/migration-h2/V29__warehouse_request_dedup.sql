-- H2 test equivalent of V29__warehouse_request_dedup.sql.

create table if not exists warehouse_request_dedup (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  request_type varchar(60) not null,
  request_key varchar(80) not null,
  business_id varchar(120) null,
  created_at timestamp not null default current_timestamp,
  constraint uk_warehouse_request_dedup unique (tenant_id, request_type, request_key),
  constraint fk_warehouse_request_dedup_tenant
    foreign key (tenant_id) references tenant(id)
);

create index if not exists idx_warehouse_request_business
  on warehouse_request_dedup (tenant_id, request_type, business_id);
