-- V33: Monthly attendance and immutable salary calculation snapshots.

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
  updated_at timestamp null default null,
  unique key uk_employee_attendance_month (tenant_id, store_id, employee_id, month),
  index idx_employee_attendance_scope (tenant_id, month, store_id),
  constraint fk_employee_attendance_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_employee_attendance_store foreign key (store_id) references store_branch(id),
  constraint fk_employee_attendance_employee foreign key (employee_id) references employee(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

alter table salary_record add column policy_id varchar(120) null after employee_id;
alter table salary_record add column policy_version int null after policy_id;
alter table salary_record add column policy_snapshot_json longtext null after policy_version;
alter table salary_record add column calculation_snapshot_json longtext null after policy_snapshot_json;
alter table salary_record add column net_pay decimal(14,2) null after gross;

create index idx_salary_record_policy on salary_record (tenant_id, policy_id, policy_version);
