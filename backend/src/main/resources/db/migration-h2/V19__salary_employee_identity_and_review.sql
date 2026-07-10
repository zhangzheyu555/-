alter table salary_record add column if not exists employee_id varchar(120) null;
alter table salary_record add column if not exists status varchar(40) not null default 'DRAFT';
alter table salary_record add column if not exists submitted_by bigint null;
alter table salary_record add column if not exists reviewed_by bigint null;
alter table salary_record add column if not exists reviewed_at timestamp null;

-- MySQL 版是 update ... join 语法，H2 不支持，改写为相关子查询（employee 有 (tenant_id, store_id, name) 唯一键）。
update salary_record sr
set employee_id = (
      select e.id from employee e
      where e.tenant_id = sr.tenant_id
        and e.store_id = sr.store_id
        and e.name = sr.employee_name
    )
where sr.employee_id is null
  and exists (
    select 1 from employee e
    where e.tenant_id = sr.tenant_id
      and e.store_id = sr.store_id
      and e.name = sr.employee_name
  );

create index if not exists idx_salary_employee_identity on salary_record(tenant_id, employee_id, month);
create index if not exists idx_salary_review_status on salary_record(tenant_id, status, month);
