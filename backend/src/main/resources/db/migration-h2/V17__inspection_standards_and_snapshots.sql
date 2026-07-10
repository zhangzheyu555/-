create table if not exists inspection_standard_version (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  version varchar(64) not null,
  title varchar(160) not null,
  full_score decimal(8,2) not null default 100.00,
  effective_date date null,
  status varchar(24) not null default 'ACTIVE',
  created_by bigint null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  unique key uk_inspection_standard_version (tenant_id, version),
  index idx_inspection_standard_active (tenant_id, status, effective_date),
  constraint fk_inspection_standard_version_tenant foreign key (tenant_id) references tenant(id)
);

create table if not exists inspection_standard_item (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  standard_version_id bigint not null,
  dimension varchar(120) not null,
  code varchar(80) null,
  title varchar(500) not null,
  description text null,
  suggested_score decimal(8,2) not null default 0.00,
  red_line tinyint(1) not null default 0,
  enabled tinyint(1) not null default 1,
  sort_order int not null default 0,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  index idx_inspection_standard_item_version (tenant_id, standard_version_id, red_line, sort_order),
  constraint fk_inspection_standard_item_version foreign key (standard_version_id) references inspection_standard_version(id),
  constraint fk_inspection_standard_item_tenant foreign key (tenant_id) references tenant(id)
);

create table if not exists inspection_record_standard_snapshot (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  inspection_record_id varchar(120) not null,
  standard_id bigint null,
  standard_version varchar(64) null,
  dimension varchar(120) null,
  standard_title varchar(500) null,
  standard_description text null,
  suggested_score decimal(8,2) not null default 0.00,
  actual_deduction_score decimal(8,2) not null default 0.00,
  red_line tinyint(1) not null default 0,
  problem_description text null,
  sort_order int not null default 0,
  created_at timestamp not null default current_timestamp,
  index idx_inspection_snapshot_record (tenant_id, inspection_record_id, sort_order),
  index idx_inspection_snapshot_standard (tenant_id, standard_id)
);
