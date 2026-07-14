create table if not exists expense_supplement (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  expense_id varchar(120) not null,
  note text null,
  submitted_by bigint not null,
  submitted_by_name varchar(120) not null,
  submitted_at timestamp not null default current_timestamp,
  constraint fk_expense_supplement_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_expense_supplement_expense foreign key (expense_id) references expense_claim(id) on delete cascade
);

create index if not exists idx_expense_supplement_expense
  on expense_supplement (tenant_id, expense_id, submitted_at);

create table if not exists expense_supplement_attachment (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  supplement_id bigint not null,
  expense_id varchar(120) not null,
  file_name varchar(255) not null,
  content_type varchar(120) not null,
  file_size bigint not null,
  storage_key varchar(160) not null unique,
  uploaded_by bigint not null,
  uploaded_at timestamp not null default current_timestamp,
  constraint fk_expense_supplement_attachment_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_expense_supplement_attachment_supplement foreign key (supplement_id) references expense_supplement(id) on delete cascade,
  constraint fk_expense_supplement_attachment_expense foreign key (expense_id) references expense_claim(id) on delete cascade
);

create index if not exists idx_expense_supplement_attachment
  on expense_supplement_attachment (tenant_id, expense_id, supplement_id);
