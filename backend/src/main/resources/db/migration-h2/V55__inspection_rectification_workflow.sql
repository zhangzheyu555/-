create table if not exists inspection_rectification (
  id varchar(120) not null primary key,
  tenant_id bigint not null,
  inspection_record_id varchar(120) not null,
  store_id varchar(64) not null,
  status varchar(32) not null,
  manager_note text null,
  submitted_by bigint null,
  submitted_by_name varchar(120) null,
  submitted_at timestamp null,
  review_note text null,
  reviewed_by bigint null,
  reviewed_by_name varchar(120) null,
  reviewed_at timestamp null,
  version bigint not null default 0,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  unique (tenant_id, inspection_record_id),
  constraint fk_inspection_rectification_tenant
    foreign key (tenant_id) references tenant(id)
);

create index if not exists idx_inspection_rectification_queue
  on inspection_rectification(tenant_id, status, store_id, updated_at);

create table if not exists inspection_rectification_action (
  id varchar(120) not null primary key,
  tenant_id bigint not null,
  rectification_id varchar(120) not null,
  inspection_record_id varchar(120) not null,
  action varchar(40) not null,
  status varchar(32) not null,
  note text null,
  actor_user_id bigint null,
  actor_name varchar(120) null,
  actor_role varchar(40) null,
  created_at timestamp not null default current_timestamp,
  constraint fk_inspection_rectification_action_tenant
    foreign key (tenant_id) references tenant(id),
  constraint fk_inspection_rectification_action_rectification
    foreign key (rectification_id) references inspection_rectification(id)
);

create index if not exists idx_inspection_rectification_action_record
  on inspection_rectification_action(tenant_id, inspection_record_id, created_at);
create index if not exists idx_inspection_rectification_action_rectification
  on inspection_rectification_action(tenant_id, rectification_id, created_at);
