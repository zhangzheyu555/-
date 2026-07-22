-- H2 equivalent of V24: EmployeeRepository writes the source for manual and Excel updates.
alter table employee add column if not exists data_source varchar(40) null;
update employee set data_source = 'MANUAL_ENTRY' where data_source is null;
comment on column employee.data_source is '数据来源';
