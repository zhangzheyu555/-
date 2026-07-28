create table qmai_operating_sync_lease (
  tenant_id bigint not null,
  brand_code varchar(40) not null,
  owner_token varchar(64) null,
  locked_until timestamp null,
  updated_at timestamp not null default current_timestamp,
  primary key (tenant_id, brand_code),
  constraint fk_qmai_operating_sync_lease_tenant
    foreign key (tenant_id) references tenant(id)
);
