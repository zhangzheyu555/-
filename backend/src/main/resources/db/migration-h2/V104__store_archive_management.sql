-- H2 contract equivalent of the MySQL V104 additive migration.
alter table store_branch
  add column manager_employee_id varchar(120) null;

alter table store_branch
  add column cost_account_store_id varchar(64) null;

alter table store_branch
  add column version bigint not null default 0;

create unique index uk_store_tenant_name
  on store_branch(tenant_id, name);

create index idx_store_manager_employee
  on store_branch(tenant_id, manager_employee_id);

create index idx_store_cost_account
  on store_branch(tenant_id, cost_account_store_id);

alter table store_branch
  add constraint fk_store_manager_employee
    foreign key (manager_employee_id) references employee(id);

alter table store_branch
  add constraint fk_store_cost_account
    foreign key (cost_account_store_id) references store_branch(id);
