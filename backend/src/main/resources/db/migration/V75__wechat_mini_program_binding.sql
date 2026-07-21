create table wechat_mini_program_binding (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  auth_user_id bigint not null,
  app_id varchar(64) not null,
  openid varchar(128) not null,
  unionid varchar(128) null,
  bound_at timestamp not null default current_timestamp,
  updated_at timestamp null default null on update current_timestamp,
  unique key uk_wechat_binding_tenant_app_openid (tenant_id, app_id, openid),
  unique key uk_wechat_binding_tenant_user_app (tenant_id, auth_user_id, app_id),
  index idx_wechat_binding_user (tenant_id, auth_user_id),
  constraint fk_wechat_binding_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_wechat_binding_user foreign key (auth_user_id) references auth_user(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
