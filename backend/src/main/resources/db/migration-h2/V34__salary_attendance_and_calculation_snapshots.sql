create table if not exists employee_month_attendance (
  id varchar(120) not null primary key,
  tenant_id bigint not null,
  store_id varchar(64) not null,
  employee_id varchar(120) not null,
  month char(7) not null,
  attendance_days decimal(6,2) not null default 0,
  normal_hours decimal(10,2) not null default 0,
  overtime_hours decimal(10,2) not null default 0,
  total_hours decimal(10,2) not null default 0,
  vacation_balance decimal(10,2) not null default 0,
  source varchar(40) not null default 'MANUAL',
  status varchar(32) not null default 'CONFIRMED',
  confirmed_by bigint null,
  confirmed_at timestamp null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null,
  constraint uk_employee_attendance_month unique (tenant_id, store_id, employee_id, month)
);

alter table salary_record add column if not exists policy_id varchar(120) null;
alter table salary_record add column if not exists policy_version int null;
alter table salary_record add column if not exists policy_snapshot_json clob null;
alter table salary_record add column if not exists calculation_snapshot_json clob null;
alter table salary_record add column if not exists net_pay decimal(14,2) null;

create index if not exists idx_salary_record_policy on salary_record (tenant_id, policy_id, policy_version);
