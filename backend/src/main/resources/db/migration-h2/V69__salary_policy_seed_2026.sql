-- V69: 2026 标准工资政策与员工工资档案种子（口径来源：桌面《2026年3月工资总合计.xlsx》，已与系统 2026-03 数据 114 人核对一致）。
-- 政策：保底开启（全勤 26 天为条件）、加班 20 元/时；岗位工资包/保底额/提成档位常量在 SalaryGenerationService 中。
-- H2 迁移此前没有 V25 的工资表定义；在本迁移中补齐测试数据库所需表结构。
create table if not exists salary_policy (
  id varchar(120) not null primary key,
  tenant_id bigint not null default 1,
  name varchar(160) not null,
  version int not null default 1,
  effective_from date,
  effective_to date,
  status varchar(40) not null default 'DRAFT',
  guarantee_enabled boolean not null default false,
  guarantee_full_attendance_days decimal(4,1),
  guarantee_included_items varchar(500),
  guarantee_excluded_items varchar(500),
  guarantee_feb_exclude boolean not null default true,
  overtime_hour_rate decimal(14,2),
  overtime_hour_rate_source varchar(40),
  created_at timestamp not null default current_timestamp,
  updated_at timestamp,
  constraint fk_salary_policy_tenant foreign key (tenant_id) references tenant(id)
);
create index if not exists idx_salary_policy_tenant on salary_policy(tenant_id);
create index if not exists idx_salary_policy_status on salary_policy(tenant_id, status);

create table if not exists employee_salary_profile (
  id varchar(120) not null primary key,
  tenant_id bigint not null default 1,
  employee_id varchar(120) not null,
  policy_id varchar(120),
  base_salary decimal(14,2) not null default 0,
  guarantee_salary decimal(14,2),
  overtime_hour_rate decimal(14,2),
  performance_type varchar(40),
  commission_type varchar(40),
  effective_from date not null,
  effective_to date,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp,
  constraint uk_profile_employee unique (tenant_id, employee_id),
  constraint fk_profile_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_profile_employee foreign key (employee_id) references employee(id),
  constraint fk_profile_policy foreign key (policy_id) references salary_policy(id)
);
create index if not exists idx_profile_policy on employee_salary_profile(tenant_id, policy_id);

create table if not exists salary_record_item (
  id varchar(120) not null primary key,
  tenant_id bigint not null default 1,
  salary_record_id varchar(120) not null,
  item_code varchar(40) not null,
  item_name varchar(120) not null,
  item_type varchar(20) not null,
  quantity decimal(14,4),
  unit_price decimal(14,4),
  amount decimal(14,2) not null default 0,
  source varchar(40) not null default 'MANUAL',
  calculation_note varchar(500),
  sort_order int not null default 0,
  created_at timestamp not null default current_timestamp,
  constraint fk_sri_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_sri_record foreign key (salary_record_id) references salary_record(id)
);
create index if not exists idx_sri_record on salary_record_item(salary_record_id);
create index if not exists idx_sri_type on salary_record_item(salary_record_id, item_type);

insert into salary_policy (id, tenant_id, name, version, effective_from, status,
  guarantee_enabled, guarantee_full_attendance_days, guarantee_feb_exclude, overtime_hour_rate, overtime_hour_rate_source)
select 'policy-std-2026', t.id, '2026标准工资政策(3月表口径)', 1, '2026-01-01', 'ACTIVE', 1, 26, 1, 20, 'MANUAL_INPUT'
from tenant t
where not exists (
  select 1 from salary_policy p where p.tenant_id = t.id and p.id = 'policy-std-2026'
);

-- 非兼职员工补默认工资档案：底薪取 employee.base_salary，无值则按 3 月表统一基本工资 1900。
insert into employee_salary_profile (id, tenant_id, employee_id, policy_id, base_salary, effective_from)
select concat('esp-', e.id), e.tenant_id, e.id, 'policy-std-2026',
       case when e.base_salary is null or e.base_salary <= 0 then 1900 else e.base_salary end,
       '2026-01-01'
from employee e
where (e.employment_type is null or e.employment_type <> '兼职')
  and not exists (
    select 1 from employee_salary_profile p
    where p.tenant_id = e.tenant_id and p.employee_id = e.id
  );
