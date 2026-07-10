alter table salary_record
  add column employee_id varchar(120) null after month,
  add column status varchar(40) not null default 'DRAFT' after return_uniform,
  add column submitted_by bigint null after status,
  add column reviewed_by bigint null after submitted_by,
  add column reviewed_at timestamp null after reviewed_by;

update salary_record sr
join employee e
  on e.tenant_id = sr.tenant_id
 and e.store_id = sr.store_id
 and e.name = sr.employee_name
set sr.employee_id = e.id
where sr.employee_id is null;

create index idx_salary_employee_identity on salary_record(tenant_id, employee_id, month);
create index idx_salary_review_status on salary_record(tenant_id, status, month);
