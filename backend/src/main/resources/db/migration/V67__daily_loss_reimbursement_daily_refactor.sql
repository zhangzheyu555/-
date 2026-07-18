-- V63: 每日报损与店长每日报销阶段。
-- 只新增配置、日报头和兼容字段；Excel 示例日报行不得写入正式业务表。

create table if not exists loss_item_config (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null default 1,
  item_code varchar(120) not null,
  item_name varchar(160) not null,
  category varchar(80) not null,
  unit varchar(40) not null,
  unit_price decimal(18,4) not null,
  source_sheet varchar(120) not null,
  active tinyint(1) not null default 1,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null on update current_timestamp,
  unique key uk_loss_item_config_code (tenant_id, item_code),
  index idx_loss_item_config_active (tenant_id, active, source_sheet, item_code),
  constraint fk_loss_item_config_tenant foreign key (tenant_id) references tenant(id),
  constraint chk_loss_item_config_price check (unit_price >= 0)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists daily_loss_report (
  id varchar(120) not null primary key,
  tenant_id bigint not null,
  store_id varchar(64) not null,
  loss_date date not null,
  status varchar(32) not null default 'DRAFT',
  submitted_by bigint null,
  submitted_at timestamp null,
  reviewed_by bigint null,
  reviewed_at timestamp null,
  review_note varchar(500) null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null on update current_timestamp,
  unique key uk_daily_loss_report_store_day (tenant_id, store_id, loss_date),
  index idx_daily_loss_report_month (tenant_id, store_id, loss_date, status),
  index idx_daily_loss_report_status (tenant_id, status, submitted_at),
  constraint chk_daily_loss_report_status check (status in ('DRAFT', 'SUBMITTED', 'REVIEWED', 'REJECTED')),
  constraint fk_daily_loss_report_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_daily_loss_report_store foreign key (store_id) references store_branch(id),
  constraint fk_daily_loss_report_submitter foreign key (submitted_by) references auth_user(id),
  constraint fk_daily_loss_report_reviewer foreign key (reviewed_by) references auth_user(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

set @sql := if(
  exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'daily_loss_record' and column_name = 'report_id'),
  'select 1',
  'alter table daily_loss_record add column report_id varchar(120) null after id'
);
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(
  exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'daily_loss_record' and column_name = 'item_config_id'),
  'select 1',
  'alter table daily_loss_record add column item_config_id bigint null after item_id'
);
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(
  exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'daily_loss_record' and column_name = 'item_id' and is_nullable = 'YES'),
  'select 1',
  'alter table daily_loss_record modify column item_id bigint null'
);
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(
  exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'daily_loss_record' and index_name = 'idx_daily_loss_report'),
  'select 1',
  'alter table daily_loss_record add index idx_daily_loss_report (tenant_id, report_id, id)'
);
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(
  exists(select 1 from information_schema.table_constraints where table_schema = database() and table_name = 'daily_loss_record' and constraint_name = 'fk_daily_loss_record_report'),
  'select 1',
  'alter table daily_loss_record add constraint fk_daily_loss_record_report foreign key (report_id) references daily_loss_report(id) on delete cascade'
);
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(
  exists(select 1 from information_schema.table_constraints where table_schema = database() and table_name = 'daily_loss_record' and constraint_name = 'fk_daily_loss_record_config'),
  'select 1',
  'alter table daily_loss_record add constraint fk_daily_loss_record_config foreign key (item_config_id) references loss_item_config(id)'
);
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(
  exists(select 1 from information_schema.columns where table_schema = database() and table_name = 'expense_claim' and column_name = 'expense_date'),
  'select 1',
  'alter table expense_claim add column expense_date date null after month'
);
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if(
  exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'expense_claim' and index_name = 'idx_expense_tenant_store_date'),
  'select 1',
  'alter table expense_claim add index idx_expense_tenant_store_date (tenant_id, store_id, expense_date, status)'
);
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

insert into permission_catalog(
  permission_code, module_code, permission_name, description, risk_level, enabled, sort_order
)
values
  ('daily_loss.read', 'DAILY_LOSS', '查看每日报损', '店长查看本店，督导和老板查看授权门店，财务只读查看。', 'MEDIUM', 1, 615),
  ('daily_loss.create', 'DAILY_LOSS', '提交每日报损', '店长为所属门店提交多品类每日报损。', 'MEDIUM', 1, 616),
  ('daily_loss.review', 'DAILY_LOSS', '复核每日报损', '督导复核授权门店每日报损并可导出当月照片包。', 'HIGH', 1, 617)
on duplicate key update
  module_code = values(module_code),
  permission_name = values(permission_name),
  description = values(description),
  risk_level = values(risk_level),
  enabled = values(enabled),
  sort_order = values(sort_order);

delete from role_permission
where permission_code in ('daily_loss.read', 'daily_loss.create', 'daily_loss.review')
  and upper(role_code) in ('OPERATIONS', 'WAREHOUSE', 'FINANCE', 'SUPERVISOR', 'STORE_MANAGER');

insert into role_permission(tenant_id, role_code, permission_code, created_at)
select tenant.id, template.role_code, template.permission_code, current_timestamp
from tenant
join (
  select 'STORE_MANAGER' as role_code, 'daily_loss.read' as permission_code
  union all select 'STORE_MANAGER', 'daily_loss.create'
  union all select 'SUPERVISOR', 'daily_loss.read'
  union all select 'SUPERVISOR', 'daily_loss.review'
  union all select 'FINANCE', 'daily_loss.read'
) template on 1 = 1
where not exists (
  select 1 from role_permission existing
  where existing.tenant_id = tenant.id
    and existing.role_code = template.role_code
    and existing.permission_code = template.permission_code
);

update auth_user
set permission_version = permission_version + 1,
    updated_at = current_timestamp
where upper(role) in ('OPERATIONS', 'WAREHOUSE', 'FINANCE', 'SUPERVISOR', 'STORE_MANAGER');

delete token
from auth_token token
join auth_user account
  on account.tenant_id = token.tenant_id
 and account.id = token.user_id
where upper(account.role) in ('OPERATIONS', 'WAREHOUSE', 'FINANCE', 'SUPERVISOR', 'STORE_MANAGER');
