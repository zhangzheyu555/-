-- V67: 2026 标准工资政策与员工工资档案种子（口径来源：桌面《2026年3月工资总合计.xlsx》，已与系统 2026-03 数据 114 人核对一致）。
-- 政策：保底开启（全勤 26 天为条件）、加班 20 元/时；岗位工资包/保底额/提成档位常量在 SalaryGenerationService 中。
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
