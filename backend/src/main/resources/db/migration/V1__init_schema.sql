create table if not exists kv_storage (
  storage_key varchar(120) not null primary key,
  storage_value longtext not null,
  updated_at timestamp not null default current_timestamp on update current_timestamp
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists auth_user (
  id bigint not null auto_increment primary key,
  username varchar(80) not null unique,
  password_hash varchar(255) not null,
  display_name varchar(120) not null,
  role varchar(40) not null,
  store_id varchar(64) null,
  enabled tinyint(1) not null default 1,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  index idx_auth_user_role (role),
  index idx_auth_user_store (store_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists brand (
  id bigint not null auto_increment primary key,
  code varchar(40) not null unique,
  name varchar(120) not null unique,
  color varchar(40) null,
  sort_order int not null default 0,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists store_branch (
  id varchar(64) not null primary key,
  brand_id bigint null,
  code varchar(80) null,
  name varchar(160) not null,
  area varchar(160) null,
  manager varchar(120) null,
  open_date date null,
  status varchar(40) not null default '营业中',
  note text null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  unique key uk_store_code (code),
  index idx_store_brand (brand_id),
  index idx_store_status (status),
  constraint fk_store_brand foreign key (brand_id) references brand(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists profit_entry (
  id bigint not null auto_increment primary key,
  store_id varchar(64) not null,
  month char(7) not null,
  sales decimal(14,2) not null default 0,
  refund decimal(14,2) not null default 0,
  discount decimal(14,2) not null default 0,
  material decimal(14,2) not null default 0,
  packaging decimal(14,2) not null default 0,
  loss decimal(14,2) not null default 0,
  cost_other decimal(14,2) not null default 0,
  rent decimal(14,2) not null default 0,
  labor decimal(14,2) not null default 0,
  utility decimal(14,2) not null default 0,
  property decimal(14,2) not null default 0,
  commission decimal(14,2) not null default 0,
  promo decimal(14,2) not null default 0,
  repair decimal(14,2) not null default 0,
  equip decimal(14,2) not null default 0,
  exp_other decimal(14,2) not null default 0,
  note text null,
  created_by bigint null,
  updated_by bigint null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  unique key uk_profit_store_month (store_id, month),
  index idx_profit_month (month),
  constraint fk_profit_store foreign key (store_id) references store_branch(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists salary_record (
  id varchar(120) not null primary key,
  store_id varchar(64) not null,
  month char(7) not null,
  employee_name varchar(120) not null,
  position varchar(80) null,
  attendance varchar(80) null,
  gross decimal(14,2) not null default 0,
  normal_hours decimal(10,2) not null default 0,
  ot_hours decimal(10,2) not null default 0,
  work_hours decimal(10,2) not null default 0,
  vacation_left decimal(10,2) not null default 0,
  vacation_note varchar(255) null,
  base decimal(14,2) not null default 0,
  social decimal(14,2) not null default 0,
  post decimal(14,2) not null default 0,
  meal decimal(14,2) not null default 0,
  full_attendance decimal(14,2) not null default 0,
  commission decimal(14,2) not null default 0,
  overtime decimal(14,2) not null default 0,
  seniority decimal(14,2) not null default 0,
  late_night decimal(14,2) not null default 0,
  subsidy decimal(14,2) not null default 0,
  performance decimal(14,2) not null default 0,
  deduct_uniform decimal(14,2) not null default 0,
  return_uniform decimal(14,2) not null default 0,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  index idx_salary_store_month (store_id, month),
  index idx_salary_name (employee_name),
  constraint fk_salary_store foreign key (store_id) references store_branch(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists expense_claim (
  id varchar(120) not null primary key,
  store_id varchar(64) not null,
  month char(7) null,
  amount decimal(14,2) not null default 0,
  category varchar(80) null,
  reason text null,
  status varchar(40) not null default '待审核',
  image_url varchar(500) null,
  submitted_by bigint null,
  reviewed_by bigint null,
  reviewed_at timestamp null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  index idx_expense_store_month (store_id, month),
  index idx_expense_status (status),
  constraint fk_expense_store foreign key (store_id) references store_branch(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists inspection_record (
  id varchar(120) not null primary key,
  store_id varchar(64) not null,
  inspection_date date not null,
  inspector varchar(120) null,
  brand varchar(120) null,
  full_score decimal(8,2) not null default 100,
  score decimal(8,2) not null default 100,
  passed tinyint(1) not null default 1,
  deductions_json longtext null,
  redlines_json longtext null,
  photos_json longtext null,
  note text null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  index idx_inspection_store_date (store_id, inspection_date),
  constraint fk_inspection_store foreign key (store_id) references store_branch(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists operation_log (
  id bigint not null auto_increment primary key,
  operator_id bigint null,
  operator_name varchar(120) null,
  action varchar(80) not null,
  target_type varchar(80) not null,
  target_id varchar(120) null,
  store_id varchar(64) null,
  month char(7) null,
  before_json longtext null,
  after_json longtext null,
  reason varchar(255) null,
  created_at timestamp not null default current_timestamp,
  index idx_log_created_at (created_at),
  index idx_log_target (target_type, target_id),
  index idx_log_store_month (store_id, month)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
