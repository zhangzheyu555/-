create table if not exists tenant (
  id bigint not null auto_increment primary key,
  name varchar(160) not null,
  industry varchar(80) null,
  scale varchar(80) null,
  status varchar(40) not null default 'ACTIVE',
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  index idx_tenant_status (status)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

insert into tenant(id, name, industry, scale, status, created_at)
values (1, 'Default Tenant', 'chain_store', 'demo', 'ACTIVE', current_timestamp)
on duplicate key update
  name = values(name),
  industry = values(industry),
  scale = values(scale),
  status = values(status),
  updated_at = current_timestamp;

set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'auth_user' and column_name = 'tenant_id'), 'select 1', 'alter table auth_user add column tenant_id bigint not null default 1 after id');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'auth_token' and column_name = 'tenant_id'), 'select 1', 'alter table auth_token add column tenant_id bigint not null default 1 after token');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'role_permission' and column_name = 'tenant_id'), 'select 1', 'alter table role_permission add column tenant_id bigint not null default 1 after id');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'user_store_scope' and column_name = 'tenant_id'), 'select 1', 'alter table user_store_scope add column tenant_id bigint not null default 1 after id');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'brand' and column_name = 'tenant_id'), 'select 1', 'alter table brand add column tenant_id bigint not null default 1 after id');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'store_branch' and column_name = 'tenant_id'), 'select 1', 'alter table store_branch add column tenant_id bigint not null default 1 after id');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'profit_entry' and column_name = 'tenant_id'), 'select 1', 'alter table profit_entry add column tenant_id bigint not null default 1 after id');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'salary_record' and column_name = 'tenant_id'), 'select 1', 'alter table salary_record add column tenant_id bigint not null default 1 after id');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'expense_claim' and column_name = 'tenant_id'), 'select 1', 'alter table expense_claim add column tenant_id bigint not null default 1 after id');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'inspection_record' and column_name = 'tenant_id'), 'select 1', 'alter table inspection_record add column tenant_id bigint not null default 1 after id');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'operation_log' and column_name = 'tenant_id'), 'select 1', 'alter table operation_log add column tenant_id bigint not null default 1 after id');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'platform_account' and column_name = 'tenant_id'), 'select 1', 'alter table platform_account add column tenant_id bigint not null default 1 after id');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'auth_user' and index_name = 'idx_auth_user_tenant'), 'select 1', 'alter table auth_user add index idx_auth_user_tenant (tenant_id)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'auth_token' and index_name = 'idx_auth_token_tenant_user'), 'select 1', 'alter table auth_token add index idx_auth_token_tenant_user (tenant_id, user_id)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'role_permission' and index_name = 'idx_role_permission_tenant'), 'select 1', 'alter table role_permission add index idx_role_permission_tenant (tenant_id)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'user_store_scope' and index_name = 'idx_user_store_scope_tenant'), 'select 1', 'alter table user_store_scope add index idx_user_store_scope_tenant (tenant_id)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'user_store_scope' and index_name = 'idx_user_store_scope_user'), 'select 1', 'alter table user_store_scope add index idx_user_store_scope_user (user_id)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'brand' and index_name = 'idx_brand_tenant'), 'select 1', 'alter table brand add index idx_brand_tenant (tenant_id)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'store_branch' and index_name = 'idx_store_tenant'), 'select 1', 'alter table store_branch add index idx_store_tenant (tenant_id)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'profit_entry' and index_name = 'idx_profit_tenant_month'), 'select 1', 'alter table profit_entry add index idx_profit_tenant_month (tenant_id, month)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'profit_entry' and index_name = 'idx_profit_store'), 'select 1', 'alter table profit_entry add index idx_profit_store (store_id)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'salary_record' and index_name = 'idx_salary_tenant_store_month'), 'select 1', 'alter table salary_record add index idx_salary_tenant_store_month (tenant_id, store_id, month)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'expense_claim' and index_name = 'idx_expense_tenant_store_month'), 'select 1', 'alter table expense_claim add index idx_expense_tenant_store_month (tenant_id, store_id, month)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'inspection_record' and index_name = 'idx_inspection_tenant_store_date'), 'select 1', 'alter table inspection_record add index idx_inspection_tenant_store_date (tenant_id, store_id, inspection_date)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'operation_log' and index_name = 'idx_log_tenant_created_at'), 'select 1', 'alter table operation_log add index idx_log_tenant_created_at (tenant_id, created_at)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'platform_account' and index_name = 'idx_platform_account_tenant_store'), 'select 1', 'alter table platform_account add index idx_platform_account_tenant_store (tenant_id, store_id)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'auth_user' and index_name = 'username'), 'alter table auth_user drop index username', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'brand' and index_name = 'code'), 'alter table brand drop index code', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'brand' and index_name = 'name'), 'alter table brand drop index name', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'store_branch' and index_name = 'uk_store_code'), 'alter table store_branch drop index uk_store_code', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'profit_entry' and index_name = 'uk_profit_store_month'), 'alter table profit_entry drop index uk_profit_store_month', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'user_store_scope' and index_name = 'uk_user_store_scope'), 'alter table user_store_scope drop index uk_user_store_scope', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'role_permission' and index_name = 'uk_role_permission'), 'alter table role_permission drop index uk_role_permission', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'auth_user' and index_name = 'uk_auth_user_tenant_username'), 'select 1', 'alter table auth_user add unique key uk_auth_user_tenant_username (tenant_id, username)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'brand' and index_name = 'uk_brand_tenant_code'), 'select 1', 'alter table brand add unique key uk_brand_tenant_code (tenant_id, code)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'brand' and index_name = 'uk_brand_tenant_name'), 'select 1', 'alter table brand add unique key uk_brand_tenant_name (tenant_id, name)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'store_branch' and index_name = 'uk_store_tenant_code'), 'select 1', 'alter table store_branch add unique key uk_store_tenant_code (tenant_id, code)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'profit_entry' and index_name = 'uk_profit_tenant_store_month'), 'select 1', 'alter table profit_entry add unique key uk_profit_tenant_store_month (tenant_id, store_id, month)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'user_store_scope' and index_name = 'uk_user_store_scope_tenant'), 'select 1', 'alter table user_store_scope add unique key uk_user_store_scope_tenant (tenant_id, user_id, store_id)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'role_permission' and index_name = 'uk_role_permission_tenant'), 'select 1', 'alter table role_permission add unique key uk_role_permission_tenant (tenant_id, role_code, permission_code)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(exists(select 1 from information_schema.table_constraints where table_schema = database() and table_name = 'auth_user' and constraint_name = 'fk_auth_user_tenant'), 'select 1', 'alter table auth_user add constraint fk_auth_user_tenant foreign key (tenant_id) references tenant(id)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.table_constraints where table_schema = database() and table_name = 'auth_token' and constraint_name = 'fk_auth_token_tenant'), 'select 1', 'alter table auth_token add constraint fk_auth_token_tenant foreign key (tenant_id) references tenant(id)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.table_constraints where table_schema = database() and table_name = 'role_permission' and constraint_name = 'fk_role_permission_tenant'), 'select 1', 'alter table role_permission add constraint fk_role_permission_tenant foreign key (tenant_id) references tenant(id)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.table_constraints where table_schema = database() and table_name = 'user_store_scope' and constraint_name = 'fk_user_scope_tenant'), 'select 1', 'alter table user_store_scope add constraint fk_user_scope_tenant foreign key (tenant_id) references tenant(id)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.table_constraints where table_schema = database() and table_name = 'brand' and constraint_name = 'fk_brand_tenant'), 'select 1', 'alter table brand add constraint fk_brand_tenant foreign key (tenant_id) references tenant(id)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.table_constraints where table_schema = database() and table_name = 'store_branch' and constraint_name = 'fk_store_tenant'), 'select 1', 'alter table store_branch add constraint fk_store_tenant foreign key (tenant_id) references tenant(id)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.table_constraints where table_schema = database() and table_name = 'profit_entry' and constraint_name = 'fk_profit_tenant'), 'select 1', 'alter table profit_entry add constraint fk_profit_tenant foreign key (tenant_id) references tenant(id)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.table_constraints where table_schema = database() and table_name = 'salary_record' and constraint_name = 'fk_salary_tenant'), 'select 1', 'alter table salary_record add constraint fk_salary_tenant foreign key (tenant_id) references tenant(id)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.table_constraints where table_schema = database() and table_name = 'expense_claim' and constraint_name = 'fk_expense_tenant'), 'select 1', 'alter table expense_claim add constraint fk_expense_tenant foreign key (tenant_id) references tenant(id)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.table_constraints where table_schema = database() and table_name = 'inspection_record' and constraint_name = 'fk_inspection_tenant'), 'select 1', 'alter table inspection_record add constraint fk_inspection_tenant foreign key (tenant_id) references tenant(id)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.table_constraints where table_schema = database() and table_name = 'operation_log' and constraint_name = 'fk_log_tenant'), 'select 1', 'alter table operation_log add constraint fk_log_tenant foreign key (tenant_id) references tenant(id)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql := if(exists(select 1 from information_schema.table_constraints where table_schema = database() and table_name = 'platform_account' and constraint_name = 'fk_platform_account_tenant'), 'select 1', 'alter table platform_account add constraint fk_platform_account_tenant foreign key (tenant_id) references tenant(id)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
