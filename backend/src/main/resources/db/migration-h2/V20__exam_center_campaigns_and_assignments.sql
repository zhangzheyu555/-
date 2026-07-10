create table if not exists training_exam_campaign (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  paper_id bigint not null,
  title varchar(160) not null,
  status varchar(40) not null default 'PUBLISHED',
  start_at datetime not null,
  due_at datetime not null,
  target_roles varchar(255) null,
  created_by bigint not null,
  published_by bigint not null,
  published_at datetime not null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  index idx_exam_campaign_status (tenant_id, status, start_at, due_at),
  index idx_exam_campaign_paper (tenant_id, paper_id),
  constraint fk_exam_campaign_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_exam_campaign_paper foreign key (paper_id) references training_exam_paper(id),
  constraint fk_exam_campaign_creator foreign key (created_by) references auth_user(id),
  constraint fk_exam_campaign_publisher foreign key (published_by) references auth_user(id)
);

create table if not exists training_exam_assignment (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  campaign_id bigint not null,
  user_id bigint not null,
  examinee_name varchar(120) not null,
  examinee_role varchar(40) not null,
  store_id varchar(64) not null,
  store_name varchar(160) not null,
  status varchar(40) not null default 'ASSIGNED',
  assigned_at datetime not null,
  due_at datetime not null,
  completed_at datetime null,
  attempt_id bigint null,
  score decimal(8,2) null,
  passed tinyint(1) null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  unique key uk_exam_assignment_user (tenant_id, campaign_id, user_id),
  index idx_exam_assignment_user_status (tenant_id, user_id, status, due_at),
  index idx_exam_assignment_store_status (tenant_id, store_id, status, due_at),
  constraint fk_exam_assignment_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_exam_assignment_campaign foreign key (campaign_id) references training_exam_campaign(id),
  constraint fk_exam_assignment_user foreign key (user_id) references auth_user(id),
  constraint fk_exam_assignment_store foreign key (store_id) references store_branch(id)
);

alter table training_exam_attempt add column if not exists campaign_id bigint null;
alter table training_exam_attempt add column if not exists assignment_id bigint null;

create index if not exists idx_exam_attempt_campaign on training_exam_attempt(tenant_id, campaign_id, submitted_at);
create index if not exists idx_exam_attempt_assignment on training_exam_attempt(tenant_id, assignment_id);

alter table training_exam_attempt add constraint if not exists fk_exam_attempt_campaign foreign key (campaign_id) references training_exam_campaign(id);
alter table training_exam_attempt add constraint if not exists fk_exam_attempt_assignment foreign key (assignment_id) references training_exam_assignment(id);

alter table training_exam_assignment add constraint if not exists fk_exam_assignment_attempt foreign key (attempt_id) references training_exam_attempt(id);
