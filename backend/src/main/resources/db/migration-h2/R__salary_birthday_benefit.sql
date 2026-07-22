alter table salary_record add column if not exists birthday_benefit decimal(14,2) default 0 not null;
comment on column salary_record.birthday_benefit is '员工福利（生日）金额';
