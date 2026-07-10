alter table salary_record add column if not exists review_note varchar(500) null;
alter table salary_record add column if not exists paid_at timestamp null;
alter table salary_record add column if not exists version int not null default 1;

create index if not exists idx_salary_version on salary_record(tenant_id, employee_id, month, version);
