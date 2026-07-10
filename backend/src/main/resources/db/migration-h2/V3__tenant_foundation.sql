create table if not exists tenant (
  id bigint not null auto_increment primary key,
  name varchar(160) not null,
  industry varchar(80) null,
  scale varchar(80) null,
  status varchar(40) not null default 'ACTIVE',
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  index idx_tenant_status (status)
);

insert into tenant(id, name, industry, scale, status, created_at)
values (1, 'Default Tenant', 'chain_store', 'demo', 'ACTIVE', current_timestamp)
on duplicate key update
  name = values(name),
  industry = values(industry),
  scale = values(scale),
  status = values(status),
  updated_at = current_timestamp;

alter table auth_user add column if not exists tenant_id bigint not null default 1;
alter table auth_token add column if not exists tenant_id bigint not null default 1;
alter table role_permission add column if not exists tenant_id bigint not null default 1;
alter table user_store_scope add column if not exists tenant_id bigint not null default 1;
alter table brand add column if not exists tenant_id bigint not null default 1;
alter table store_branch add column if not exists tenant_id bigint not null default 1;
alter table profit_entry add column if not exists tenant_id bigint not null default 1;
alter table salary_record add column if not exists tenant_id bigint not null default 1;
alter table expense_claim add column if not exists tenant_id bigint not null default 1;
alter table inspection_record add column if not exists tenant_id bigint not null default 1;
alter table operation_log add column if not exists tenant_id bigint not null default 1;
alter table platform_account add column if not exists tenant_id bigint not null default 1;

create index if not exists idx_auth_user_tenant on auth_user (tenant_id);
create index if not exists idx_auth_token_tenant_user on auth_token (tenant_id, user_id);
create index if not exists idx_role_permission_tenant on role_permission (tenant_id);
create index if not exists idx_user_store_scope_tenant on user_store_scope (tenant_id);
create index if not exists idx_user_store_scope_user on user_store_scope (user_id);
create index if not exists idx_brand_tenant on brand (tenant_id);
create index if not exists idx_store_tenant on store_branch (tenant_id);
create index if not exists idx_profit_tenant_month on profit_entry (tenant_id, month);
create index if not exists idx_profit_store on profit_entry (store_id);
create index if not exists idx_salary_tenant_store_month on salary_record (tenant_id, store_id, month);
create index if not exists idx_expense_tenant_store_month on expense_claim (tenant_id, store_id, month);
create index if not exists idx_inspection_tenant_store_date on inspection_record (tenant_id, store_id, inspection_date);
create index if not exists idx_log_tenant_created_at on operation_log (tenant_id, created_at);
create index if not exists idx_platform_account_tenant_store on platform_account (tenant_id, store_id);

-- MySQL 版这里会删掉列级 unique 自动生成的索引（username/code/name 等）再换成租户级唯一键。
-- H2 里列级 unique 生成的是匿名约束，没有这些名字；保留原有列级唯一对单租户本地库无影响。
drop index if exists username;
drop index if exists code;
drop index if exists name;
alter table store_branch drop constraint if exists uk_store_code;
alter table profit_entry drop constraint if exists uk_profit_store_month;
alter table user_store_scope drop constraint if exists uk_user_store_scope;
alter table role_permission drop constraint if exists uk_role_permission;

alter table auth_user add constraint if not exists uk_auth_user_tenant_username unique (tenant_id, username);
alter table brand add constraint if not exists uk_brand_tenant_code unique (tenant_id, code);
alter table brand add constraint if not exists uk_brand_tenant_name unique (tenant_id, name);
alter table store_branch add constraint if not exists uk_store_tenant_code unique (tenant_id, code);
alter table profit_entry add constraint if not exists uk_profit_tenant_store_month unique (tenant_id, store_id, month);
alter table user_store_scope add constraint if not exists uk_user_store_scope_tenant unique (tenant_id, user_id, store_id);
alter table role_permission add constraint if not exists uk_role_permission_tenant unique (tenant_id, role_code, permission_code);

alter table auth_user add constraint if not exists fk_auth_user_tenant foreign key (tenant_id) references tenant(id);
alter table auth_token add constraint if not exists fk_auth_token_tenant foreign key (tenant_id) references tenant(id);
alter table role_permission add constraint if not exists fk_role_permission_tenant foreign key (tenant_id) references tenant(id);
alter table user_store_scope add constraint if not exists fk_user_scope_tenant foreign key (tenant_id) references tenant(id);
alter table brand add constraint if not exists fk_brand_tenant foreign key (tenant_id) references tenant(id);
alter table store_branch add constraint if not exists fk_store_tenant foreign key (tenant_id) references tenant(id);
alter table profit_entry add constraint if not exists fk_profit_tenant foreign key (tenant_id) references tenant(id);
alter table salary_record add constraint if not exists fk_salary_tenant foreign key (tenant_id) references tenant(id);
alter table expense_claim add constraint if not exists fk_expense_tenant foreign key (tenant_id) references tenant(id);
alter table inspection_record add constraint if not exists fk_inspection_tenant foreign key (tenant_id) references tenant(id);
alter table operation_log add constraint if not exists fk_log_tenant foreign key (tenant_id) references tenant(id);
alter table platform_account add constraint if not exists fk_platform_account_tenant foreign key (tenant_id) references tenant(id);
