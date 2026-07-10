create table if not exists auth_token (
  token varchar(96) not null primary key,
  user_id bigint not null,
  expires_at datetime not null,
  created_at timestamp not null default current_timestamp,
  index idx_auth_token_user (user_id),
  index idx_auth_token_expires (expires_at),
  constraint fk_auth_token_user foreign key (user_id) references auth_user(id)
);

create table if not exists role_permission (
  id bigint not null auto_increment primary key,
  role_code varchar(40) not null,
  permission_code varchar(120) not null,
  created_at timestamp not null default current_timestamp,
  unique key uk_role_permission (role_code, permission_code)
);

create table if not exists user_store_scope (
  id bigint not null auto_increment primary key,
  user_id bigint not null,
  store_id varchar(64) not null,
  created_at timestamp not null default current_timestamp,
  unique key uk_user_store_scope (user_id, store_id),
  index idx_user_store_scope_store (store_id),
  constraint fk_user_scope_user foreign key (user_id) references auth_user(id),
  constraint fk_user_scope_store foreign key (store_id) references store_branch(id)
);

create table if not exists platform_account (
  id bigint not null auto_increment primary key,
  store_id varchar(64) not null,
  platform_name varchar(120) not null,
  login_url varchar(500) null,
  username varchar(160) null,
  password_cipher varchar(500) null,
  note text null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  index idx_platform_account_store (store_id),
  constraint fk_platform_account_store foreign key (store_id) references store_branch(id)
);
