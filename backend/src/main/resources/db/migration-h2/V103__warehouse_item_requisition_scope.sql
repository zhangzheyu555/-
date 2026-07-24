-- H2 contract equivalent of the MySQL V103 additive migration.
-- Missing policy rows remain the backward-compatible “all stores” state.

create table warehouse_item_requisition_policy (
  tenant_id bigint not null,
  item_id bigint not null,
  scope_mode varchar(24) not null,
  campaign_name varchar(160) null,
  starts_at timestamp null,
  ends_at timestamp null,
  updated_by bigint null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  primary key (tenant_id, item_id),
  constraint chk_item_req_policy_mode check (scope_mode in ('ALL', 'SELECTED')),
  constraint chk_item_req_policy_window check (
    starts_at is null or ends_at is null or starts_at < ends_at
  ),
  constraint fk_item_req_policy_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_item_req_policy_item foreign key (item_id) references warehouse_item(id)
);

create index idx_item_req_policy_window
  on warehouse_item_requisition_policy(tenant_id, starts_at, ends_at);

create table warehouse_item_requisition_target (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  item_id bigint not null,
  target_type varchar(24) not null,
  target_value varchar(64) not null,
  created_at timestamp not null default current_timestamp,
  unique (tenant_id, item_id, target_type, target_value),
  constraint chk_item_req_target_type check (target_type in ('REGION', 'STORE')),
  constraint fk_item_req_target_policy foreign key (tenant_id, item_id)
    references warehouse_item_requisition_policy(tenant_id, item_id) on delete cascade
);

create index idx_item_req_target_lookup
  on warehouse_item_requisition_target(tenant_id, target_type, target_value, item_id);
