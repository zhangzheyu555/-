-- V25: Salary policy & calculation rules foundation
-- Three core tables for configurable, auditable salary calculation
-- All DDL uses MySQL 5.5+ compatible syntax

-- ============================================================
-- salary_policy: named, versioned wage policies
-- ============================================================
create table if not exists salary_policy (
  id varchar(120) not null primary key,
  tenant_id bigint not null default 1,
  name varchar(160) not null comment 'Policy name, e.g. "2026 standard wage rules"',
  version int not null default 1,
  effective_from date null comment 'Policy becomes active from this date',
  effective_to date null comment 'Policy expires after this date (null = open-ended)',
  status varchar(40) not null default 'DRAFT' comment 'DRAFT | ACTIVE | ARCHIVED',
  guarantee_enabled tinyint(1) not null default 0 comment 'Whether guarantee salary is enabled',
  guarantee_full_attendance_days decimal(4,1) null comment 'Min attendance days for guarantee eligibility',
  guarantee_included_items varchar(500) null comment 'Comma-separated item codes included in guarantee calculation',
  guarantee_excluded_items varchar(500) null comment 'Comma-separated item codes excluded from guarantee calculation',
  guarantee_feb_exclude tinyint(1) not null default 1 comment 'Exclude February from guarantee calculation',
  overtime_hour_rate decimal(14,2) null comment 'Default overtime rate per hour (overridden by employee profile)',
  overtime_hour_rate_source varchar(40) null comment 'ATTENDANCE_SYSTEM | MANUAL_INPUT | PROFILE_ONLY',
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  index idx_salary_policy_tenant (tenant_id),
  index idx_salary_policy_status (tenant_id, status),
  constraint fk_salary_policy_tenant foreign key (tenant_id) references tenant(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

-- ============================================================
-- employee_salary_profile: per-employee salary configuration
-- ============================================================
create table if not exists employee_salary_profile (
  id varchar(120) not null primary key,
  tenant_id bigint not null default 1,
  employee_id varchar(120) not null comment 'Links to employee.id',
  policy_id varchar(120) null comment 'Links to salary_policy.id (null = use default)',
  base_salary decimal(14,2) not null default 0 comment 'Monthly base salary',
  guarantee_salary decimal(14,2) null comment 'Guarantee monthly minimum (null = no guarantee)',
  overtime_hour_rate decimal(14,2) null comment 'Overtime rate per hour for this employee',
  performance_type varchar(40) null comment 'FIXED_PERCENT | TIERED | NONE',
  commission_type varchar(40) null comment 'REVENUE_PCT | PROFIT_PCT | FIXED | NONE',
  effective_from date not null,
  effective_to date null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  unique key uk_profile_employee (tenant_id, employee_id),
  index idx_profile_policy (tenant_id, policy_id),
  constraint fk_profile_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_profile_employee foreign key (employee_id) references employee(id),
  constraint fk_profile_policy foreign key (policy_id) references salary_policy(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

-- ============================================================
-- salary_record_item: line-item breakdown of salary calculation
-- ============================================================
create table if not exists salary_record_item (
  id varchar(120) not null primary key,
  tenant_id bigint not null default 1,
  salary_record_id varchar(120) not null,
  item_code varchar(40) not null comment 'E.g. BASE, OVERTIME, COMMISSION, SOCIAL_INSURANCE',
  item_name varchar(120) not null comment 'Chinese display name',
  item_type varchar(20) not null comment 'EARNING | DEDUCTION | EMPLOYER_COST | INFORMATION',
  quantity decimal(14,4) null comment 'E.g. overtime hours, attendance days',
  unit_price decimal(14,4) null comment 'E.g. hourly rate, daily rate',
  amount decimal(14,2) not null default 0 comment 'Final amount for this line item',
  source varchar(40) not null default 'MANUAL' comment 'MANUAL | ATTENDANCE | PERFORMANCE | CALCULATED | IMPORT',
  calculation_note varchar(500) null comment 'Human-readable explanation of calculation',
  sort_order int not null default 0,
  created_at timestamp not null default current_timestamp,
  index idx_sri_record (salary_record_id),
  index idx_sri_type (salary_record_id, item_type),
  constraint fk_sri_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_sri_record foreign key (salary_record_id) references salary_record(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
