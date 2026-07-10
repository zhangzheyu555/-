alter table salary_record
  add column if not exists review_note varchar(500) null after reviewed_at,
  add column if not exists paid_at timestamp null after review_note,
  add column if not exists version int not null default 1 after paid_at;

create index if not exists idx_salary_version on salary_record(tenant_id, employee_id, month, version);
