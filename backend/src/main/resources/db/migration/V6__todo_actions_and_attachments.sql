create table if not exists todo_action (
  id varchar(120) not null primary key,
  tenant_id bigint not null,
  todo_id varchar(160) not null,
  action_type varchar(40) not null,
  status varchar(40) not null,
  note text not null,
  actor_user_id bigint null,
  actor_name varchar(120) null,
  actor_role varchar(40) not null,
  created_at timestamp not null default current_timestamp,
  index idx_todo_action_tenant_todo (tenant_id, todo_id, created_at),
  index idx_todo_action_tenant_status (tenant_id, status, created_at),
  constraint fk_todo_action_tenant foreign key (tenant_id) references tenant(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists todo_action_attachment (
  id varchar(120) not null primary key,
  tenant_id bigint not null,
  action_id varchar(120) not null,
  todo_id varchar(160) not null,
  file_name varchar(240) not null,
  content_type varchar(120) null,
  size_bytes bigint not null,
  content longblob not null,
  created_at timestamp not null default current_timestamp,
  index idx_todo_attachment_action (tenant_id, action_id),
  index idx_todo_attachment_todo (tenant_id, todo_id),
  constraint fk_todo_attachment_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_todo_attachment_action foreign key (action_id) references todo_action(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
