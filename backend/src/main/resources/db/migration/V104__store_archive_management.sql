-- V104: Store archive management requires stable references for the selected manager and cost ledger,
-- plus an optimistic version. Existing rows remain compatible: null references are interpreted
-- by the application as legacy values until the archive is edited.
alter table store_branch
  add column manager_employee_id varchar(120) null after manager,
  add column cost_account_store_id varchar(64) null after manager_phone,
  add column version bigint not null default 0 after note;

create unique index uk_store_tenant_name
  on store_branch(tenant_id, name);

create index idx_store_manager_employee
  on store_branch(tenant_id, manager_employee_id);

create index idx_store_cost_account
  on store_branch(tenant_id, cost_account_store_id);

alter table store_branch
  add constraint fk_store_manager_employee
    foreign key (manager_employee_id) references employee(id),
  add constraint fk_store_cost_account
    foreign key (cost_account_store_id) references store_branch(id);
