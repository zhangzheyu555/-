-- 企迈开放平台凭证配置（按租户单条）。
-- open_key 为签名密钥，仅后端使用，绝不下发前端；读取接口只返回是否已配置与掩码。
create table if not exists qmai_platform_config (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  open_id varchar(200) not null default '',
  grant_code varchar(200) not null default '',
  open_key varchar(200) not null default '',
  base_url varchar(300) not null default '',
  version varchar(40) not null default '',
  shops text null,
  updated_by_user_id bigint null,
  updated_by_name varchar(120) null,
  updated_at timestamp not null default current_timestamp on update current_timestamp,
  created_at timestamp not null default current_timestamp,
  constraint uk_qmai_config_tenant unique (tenant_id),
  constraint fk_qmai_config_tenant foreign key (tenant_id) references tenant(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
