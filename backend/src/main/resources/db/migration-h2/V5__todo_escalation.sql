create table if not exists todo_escalation (
  id varchar(120) not null primary key,
  tenant_id bigint not null,
  source_role varchar(40) not null,
  source_module varchar(80) not null,
  source_id varchar(120) not null,
  source_todo_id varchar(160) not null,
  reason text not null,
  severity varchar(40) not null,
  reported_by_user_id bigint null,
  reported_by_name varchar(120) null,
  boss_todo_id varchar(160) not null,
  status varchar(40) not null default 'OPEN',
  created_at timestamp not null default current_timestamp,
  resolved_at timestamp null,
  index idx_todo_escalation_tenant_status (tenant_id, status, created_at),
  index idx_todo_escalation_source (tenant_id, source_role, source_todo_id),
  constraint fk_todo_escalation_tenant foreign key (tenant_id) references tenant(id)
);
